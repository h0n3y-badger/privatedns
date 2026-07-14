package com.privatedns

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Reads/writes Android's Private DNS (DNS-over-TLS) global settings.
 * Writing requires WRITE_SECURE_SETTINGS, granted once over adb:
 *   adb shell pm grant com.privatedns android.permission.WRITE_SECURE_SETTINGS
 */
object DnsController {
    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"

    const val MODE_OFF = "off"
    const val MODE_AUTO = "opportunistic"
    const val MODE_HOSTNAME = "hostname"

    data class State(val mode: String, val hostname: String)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun read(context: Context): State {
        val cr = context.contentResolver
        val mode = Settings.Global.getString(cr, KEY_MODE) ?: MODE_AUTO
        val host = Settings.Global.getString(cr, KEY_SPECIFIER) ?: ""
        return State(mode, host)
    }

    /** Set hostname (strict DoT) mode. Returns null on success, error message otherwise. */
    fun setHostname(context: Context, hostname: String): String? = write(context) { cr ->
        Settings.Global.putString(cr, KEY_SPECIFIER, hostname)
        Settings.Global.putString(cr, KEY_MODE, MODE_HOSTNAME)
    }

    fun setAutomatic(context: Context): String? = write(context) { cr ->
        Settings.Global.putString(cr, KEY_MODE, MODE_AUTO)
    }

    fun setOff(context: Context): String? = write(context) { cr ->
        Settings.Global.putString(cr, KEY_MODE, MODE_OFF)
    }

    private inline fun write(
        context: Context,
        block: (android.content.ContentResolver) -> Unit,
    ): String? {
        if (!hasPermission(context)) return "WRITE_SECURE_SETTINGS not granted"
        return try {
            block(context.contentResolver)
            null
        } catch (e: SecurityException) {
            e.message ?: "SecurityException"
        }
    }
}
