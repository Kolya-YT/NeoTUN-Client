package com.neotun.app

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NeoTunDiagnostics {
    private const val TAG = "NeoTUN"
    private const val PREFS = "neotun"
    private const val KEY_LOG = "diagnostic_log"
    private const val MAX_LOG = 16000

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOG)
            .commit()
    }

    @Synchronized
    fun log(context: Context, message: String) {
        val line = "[${format.format(Date())}] $message"
        Log.i(TAG, line)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_LOG, "") ?: ""
        val updated = (old + line + "\n").takeLast(MAX_LOG)
        prefs.edit().putString(KEY_LOG, updated).commit()
    }

    @Synchronized
    fun error(context: Context, message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) message else {
            val stack = throwable.stackTraceToString()
            "$message\n${throwable::class.java.simpleName}: ${throwable.message ?: ""}\n$stack"
        }
        Log.e(TAG, details, throwable)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_LOG, "") ?: ""
        val line = "[${format.format(Date())}] ERROR: $details\n"
        prefs.edit().putString(KEY_LOG, (old + line).takeLast(MAX_LOG)).commit()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "")
            .orEmpty()
}
