package com.neotun.app

import android.net.VpnService

object NeoTunXrayBridge {
    init {
        System.loadLibrary("neotun_xray")
    }

    external fun nativeInit(vpnService: VpnService)
    external fun nativePrepare(dnsServer: String?): String?
    external fun nativeInvoke(request: String): String
    external fun nativeResetDns()
}
