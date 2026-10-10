package com.neotun.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Compact launcher widget. It delegates connection changes to MainActivity so
 * Android VPN consent and the normal profile validation flow remain intact.
 */
class NeoTunHomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.neotun.app.WIDGET_REFRESH"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NeoTunHomeWidget::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val running = context.getSharedPreferences(NeoTunVpnService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(NeoTunVpnService.KEY_RUNNING, false)
            val views = RemoteViews(context.packageName, R.layout.widget_neotun)
            views.setTextViewText(R.id.widget_status, if (running) "Подключено" else "Не подключено")
            views.setTextViewText(R.id.widget_action, if (running) "Отключить" else "Подключить")
            val intent = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_WIDGET_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, 7100 + id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
