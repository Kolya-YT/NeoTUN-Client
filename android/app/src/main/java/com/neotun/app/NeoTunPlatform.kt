package com.neotun.app

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
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

class NeoTunStringIterator(private val values: Iterator<String>) : StringIterator {
    override fun len(): Int = 0
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): String = values.next()
}

class NeoTunNetworkInterfaceIterator(
    private val values: Iterator<LibNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): LibNetworkInterface = values.next()
}

class NeoTunPlatform(private val vpn: VpnService) : PlatformInterface {
    private val connectivity = vpn.getSystemService(ConnectivityManager::class.java)

    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { vpn.protect(fd) }
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun openTun(options: TunOptions): Int {
        val builder = VpnService.Builder(vpn)
            .setSession("NeoTUN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

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
            while (dns.hasNext()) builder.addDnsServer(dns.next())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.inet4RouteAddress
                if (r4.hasNext()) {
                    while (r4.hasNext()) {
                        val prefix = r4.next()
                        builder.addRoute(prefix.address(), prefix.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                val r6 = options.inet6RouteAddress
                while (r6.hasNext()) {
                    val prefix = r6.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }

                val e4 = options.inet4RouteExcludeAddress
                while (e4.hasNext()) {
                    val prefix = e4.next()
                    builder.excludeRoute(prefix.address(), prefix.prefix())
                }
                val e6 = options.inet6RouteExcludeAddress
                while (e6.hasNext()) {
                    val prefix = e6.next()
                    builder.excludeRoute(prefix.address(), prefix.prefix())
                }
            } else {
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
            }
        }

        val fd = builder.establish() ?: error("Не удалось создать TUN")
        return fd.detachFd()
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("unsupported")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("owner not found")
        val packages = vpn.packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(NeoTunStringIterator(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            LibNetworkInterface().apply {
                name = ni.name
                index = ni.index
                mtu = runCatching { ni.mtu }.getOrDefault(1500)
                addresses = NeoTunStringIterator(
                    ni.interfaceAddresses.map { it.address.hostAddress + "/" + it.networkPrefixLength }.iterator()
                )
                flags = if (ni.isUp) OsConstants.IFF_UP else 0
                if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                type = io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                dnsServer = NeoTunStringIterator(emptyList<String>().iterator())
                dnsSearchDomain = NeoTunStringIterator(emptyList<String>().iterator())
                gateway = NeoTunStringIterator(emptyList<String>().iterator())
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
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(options: ByteArray, handler: AutoRedirectHandler): AutoRedirectSession =
        error("unsupported")
}
