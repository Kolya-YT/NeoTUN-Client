package com.neotun.app

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings shortcut; connection lifecycle stays inside MainActivity. */
class NeoTunQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_WIDGET_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivityAndCollapse(intent)
    }

    private fun syncTile() {
        val connected = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .getBoolean(NeoTunVpnService.KEY_RUNNING, false)
        qsTile?.apply {
            label = if (connected) "NeoTUN · Вкл." else "NeoTUN"
            state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
