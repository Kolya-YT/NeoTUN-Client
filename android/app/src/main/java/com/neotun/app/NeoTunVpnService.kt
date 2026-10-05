package com.neotun.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class NeoTunVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (vpnInterface == null) {
            vpnInterface = Builder()
                .setSession("NeoTUN")
                .addAddress("10.10.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
