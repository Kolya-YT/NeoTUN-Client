package com.neotun.app

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            var hasIpv4Route = false
            while (r4.hasNext()) {
                val prefix = r4.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv4Route = true
            }
            // Avoid creating an auto-routed TUN without an IPv4 route if libbox supplies an empty iterator.
            if (!hasIpv4Route) {
                builder.addRoute("0.0.0.0", 0)
                NeoTunDiagnostics.log(vpn, "sing-box: no IPv4 routes supplied; using 0.0.0.0/0")
            }

            val r6 = options.inet6RouteRange
            var hasIpv6Route = false
            while (r6.hasNext()) {
                val prefix = r6.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv6Route = true
            }
            if (!hasIpv6Route && options.inet6Address.hasNext()) {
                builder.addRoute("::", 0)
                NeoTunDiagnostics.log(vpn, "sing-box: no IPv6 routes supplied; using ::/0")
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
                val replacement = bestPhysicalNetwork()
                if (replacement != null) updateDefaultInterface(listener, replacement)
                else listener.updateDefaultInterface("", -1, false, false)
            }
        }

        // libbox may initialize remote rule-sets immediately after this callback is
        // registered. Android delivers onAvailable asynchronously, which can leave
        // sing-box without a selected physical interface during its first DNS/HTTPS
        // request ("dial UDP connection: no available network interface"). Publish
        // the current physical network synchronously before starting the monitor.
        val initialNetwork = bestPhysicalNetwork()
        if (initialNetwork != null) {
            updateDefaultInterface(listener, initialNetwork)
            NeoTunDiagnostics.log(
                vpn,
                "sing-box: initial physical network published before monitor registration"
            )
        } else {
            NeoTunDiagnostics.log(
                vpn,
                "sing-box: no physical Internet network available at monitor startup"
            )
        }

        defaultNetworkCallbacks[listener] = callback
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            val handler = Handler(Looper.getMainLooper())
            when {
                // Android P+ may report the VPN itself as the default network.
                // Request the best underlying Internet network instead.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    connectivity.requestNetwork(request, callback, handler)
                else ->
                    connectivity.registerDefaultNetworkCallback(callback)
            }
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
        val caps = capabilities ?: connectivity.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val linkProperties = connectivity.getLinkProperties(network) ?: return
        val interfaceName = linkProperties.interfaceName.orEmpty()
        if (interfaceName.isBlank()) return
        val expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val interfaceIndex = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }
            .getOrDefault(-1)
        if (interfaceIndex < 0) return
        listener.updateDefaultInterface(interfaceName, interfaceIndex, expensive, false)
        listener.updateNetworkPath(network.toString())
    }

    private fun bestPhysicalNetwork(): Network? {
        return connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }
                val name = connectivity.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
                if (name.isBlank() || NetworkInterface.getByName(name) == null) return@mapNotNull null
                val score = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 10 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 8 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 5 else 0)
                network to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        // Use Android's connected networks and their real metadata. Never advertise our own TUN as an outbound.
        val result = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@mapNotNull null
            }
            val links = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val name = links.interfaceName ?: return@mapNotNull null
            val ni = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return@mapNotNull null
            LibNetworkInterface().apply {
                this.name = name
                index = ni.index
                mtu = runCatching { ni.mtu }.getOrDefault(1500)
                addresses = NeoTunStringIterator(
                    ni.interfaceAddresses.mapNotNull { address ->
                        val host = address.address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        "$host/${address.networkPrefixLength}"
                    },
                )
                dnsServer = NeoTunStringIterator(links.dnsServers.mapNotNull { it.hostAddress })
                gateway = NeoTunStringIterator(
                    links.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }
                )
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                    else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                }
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
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
