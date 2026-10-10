package com.neotun.app

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class NeoTunStringIterator(
    private val values: List<String>,
) : StringIterator {
    private var index = 0
    override fun len(): Int = values.size
    override fun hasNext(): Boolean = index < values.size
    override fun next(): String = values[index++]
}

class NeoTunNetworkInterfaceIterator(
    private val values: Iterator<LibNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): LibNetworkInterface = values.next()
}

class NeoTunPlatform(private val vpn: VpnService) : PlatformInterface {
    private val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
    private val defaultNetworkCallbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        vpn.protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun openTun(options: TunOptions): Int {
        NeoTunDiagnostics.log(
            vpn,
            "sing-box: openTun requested; mtu=" + options.mtu +
                "; autoRoute=" + options.autoRoute
        )
        try {
        val builder = vpn.Builder()
            .setSession("NeoTUN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) {
                builder.addDnsServer(dns.next())
            }

            val r4 = options.inet4RouteRange
            while (r4.hasNext()) {
                val prefix = r4.next()
                builder.addRoute(prefix.address(), prefix.prefix())
            }

            val r6 = options.inet6RouteRange
            while (r6.hasNext()) {
                val prefix = r6.next()
                builder.addRoute(prefix.address(), prefix.prefix())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val e4 = options.inet4RouteExcludeAddress
                while (e4.hasNext()) {
                    val prefix = e4.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
                }

                val e6 = options.inet6RouteExcludeAddress
                while (e6.hasNext()) {
                    val prefix = e6.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
                }
            }
        }

        val descriptor = (builder.establish() ?: error("Не удалось создать TUN")).detachFd()
        NeoTunDiagnostics.log(vpn, "sing-box: openTun established; fd=" + descriptor)
        return descriptor
        } catch (t: Throwable) {
            NeoTunDiagnostics.error(vpn, "sing-box: openTun failed", t)
            throw t
        }
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Определение владельца соединения доступно с Android 10")
        }
        return runCatching {
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            val packages = if (uid != Process.INVALID_UID) {
                vpn.packageManager.getPackagesForUid(uid).orEmpty()
            } else {
                emptyArray()
            }
            ConnectionOwner().apply {
                userId = uid
                userName = packages.firstOrNull() ?: ""
                setAndroidPackageNames(NeoTunStringIterator(packages.toList()))
            }
        }.getOrElse {
            // UDP/QUIC flows (notably Hysteria2) can arrive without a resolvable
            // Android owner. Never throw from the libbox callback: a Kotlin
            // exception crossing the gomobile boundary can terminate the app.
            android.util.Log.w("NeoTUN", "Connection owner lookup failed", it)
            ConnectionOwner().apply {
                userId = Process.INVALID_UID
                userName = ""
                setAndroidPackageNames(NeoTunStringIterator(emptyList()))
            }
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateDefaultInterface(listener, network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateDefaultInterface(listener, network, capabilities)
            }

            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                if (active != null) updateDefaultInterface(listener, active)
                else listener.updateDefaultInterface("", -1, false, false)
            }
        }

        defaultNetworkCallbacks[listener] = callback
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.onFailure {
            defaultNetworkCallbacks.remove(listener)
            throw it
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallbacks.remove(listener)?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private fun updateDefaultInterface(
        listener: InterfaceUpdateListener,
        network: Network,
        capabilities: NetworkCapabilities? = connectivity.getNetworkCapabilities(network),
    ) {
        val linkProperties = connectivity.getLinkProperties(network)
        val interfaceName = linkProperties?.interfaceName.orEmpty()
        if (interfaceName.isBlank()) return
        val expensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val interfaceIndex = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }
            .getOrDefault(-1)
        listener.updateDefaultInterface(interfaceName, interfaceIndex, expensive, false)
        listener.updateNetworkPath(network.toString())
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .map { ni ->
                LibNetworkInterface().apply {
                    name = ni.name
                    index = ni.index
                    mtu = runCatching { ni.mtu }.getOrDefault(1500)
                    addresses = NeoTunStringIterator(
                        ni.interfaceAddresses.map {
                            it.address.hostAddress + "/" + it.networkPrefixLength
                        },
                    )
                    flags = if (ni.isUp) OsConstants.IFF_UP else 0
                    if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                    if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                    if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                    type = io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                    dnsServer = NeoTunStringIterator(emptyList())
                    gateway = NeoTunStringIterator(emptyList())
                    metered = false
                }
            }
        return NeoTunNetworkInterfaceIterator(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = Unit
    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("shell unavailable")

    override fun lookupUser(username: String): PlatformUser = PlatformUser().apply {
        this.username = username
        uid = Process.myUid()
        gid = Process.myUid()
        homeDir = vpn.filesDir.path
        shell = ""
    }

    override fun lookupSFTPServer(): String = error("unsupported")
    override fun readSystemSSHHostKey(): String = error("unsupported")
    override fun tailscaleHostname(): String = Build.MANUFACTURER + " " + Build.MODEL
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions): BridgeSession = error("unsupported")
}
