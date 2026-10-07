package com.neotun.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NeoTunDiagnostics {
    private const val TAG = "NeoTUN"
    private const val MAX_LOG = 16000
    private const val FILE_NAME = "neotun_diagnostics.log"

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun append(context: Context, line: String) {
        val target = file(context)
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { raf ->
            raf.channel.lock().use {
                raf.seek(raf.length())
                raf.write(line.toByteArray(Charsets.UTF_8))
                trimLocked(raf)
            }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            val target = file(context)
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { raf ->
                raf.channel.lock().use {
                    raf.setLength(0)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear diagnostics", t)
        }
    }

    @Synchronized
    fun log(context: Context, message: String) {
        val line = "[" + format.format(Date()) + "] " + message + "\n"
        Log.i(TAG, line.trimEnd())
        try {
            append(context, line)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write diagnostics", t)
        }
    }

    @Synchronized
    fun error(context: Context, message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) {
            message
        } else {
            message + "\n" + throwable.stackTraceToString()
        }
        Log.e(TAG, details, throwable)
        val line = "[" + format.format(Date()) + "] ERROR: " + details + "\n"
        try {
            append(context, line)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write diagnostics", t)
        }
    }

    fun read(context: Context): String {
        return try {
            val target = file(context)
            if (!target.exists()) {
                return ""
            }

            RandomAccessFile(target, "r").use { raf ->
                raf.channel.lock(0L, Long.MAX_VALUE, true).use {
                    val length = raf.length().coerceAtMost(MAX_LOG.toLong()).toInt()
                    val bytes = ByteArray(length)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read diagnostics", t)
            ""
        }
    }

    private fun trimLocked(raf: RandomAccessFile) {
        val length = raf.length()
        if (length <= MAX_LOG) {
            return
        }

        val keep = ByteArray(MAX_LOG)
        raf.seek(length - MAX_LOG)
        raf.readFully(keep)
        raf.setLength(0)
        raf.seek(0)
        raf.write(keep)
    }
}
