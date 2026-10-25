package de.fgseraser.dnsswitcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Reads and changes Android's "Privates DNS" (Settings.Global private_dns_mode / private_dns_specifier).
 * Changing it needs WRITE_SECURE_SETTINGS, which is granted once via ADB or root.
 */
object DnsController {

    const val MODE_HOSTNAME = "hostname"
    const val MODE_AUTOMATIC = "opportunistic"
    const val MODE_OFF = "off"

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_HOST = "private_dns_specifier"

    fun adbCommand(context: Context) =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun currentMode(context: Context): String =
        Settings.Global.getString(context.contentResolver, KEY_MODE) ?: MODE_AUTOMATIC

    fun currentHost(context: Context): String =
        Settings.Global.getString(context.contentResolver, KEY_HOST) ?: ""

    /** Is this profile already active? */
    fun isActive(context: Context, profile: DnsProfile): Boolean {
        val mode = currentMode(context)
        if (mode != profile.mode) return false
        return mode != MODE_HOSTNAME || currentHost(context).equals(profile.host, ignoreCase = true)
    }

    /** Text for the status card. */
    fun describeCurrent(context: Context): String {
        val mode = currentMode(context)
        val host = currentHost(context)
        val match = Store.profiles(context).firstOrNull { isActive(context, it) }
        val technical = when (mode) {
            MODE_HOSTNAME -> host
            MODE_OFF -> "Aus"
            else -> "Automatisch"
        }
        return if (match != null) "${match.name} ($technical)" else technical
    }

    /**
     * Switches to the profile. Returns true if something was changed.
     * Throws SecurityException if the permission is missing.
     */
    fun apply(context: Context, profile: DnsProfile): Boolean {
        if (isActive(context, profile)) return false
        val resolver = context.contentResolver
        // Set the host name first, then the mode – so Android never uses an old server
        if (profile.mode == MODE_HOSTNAME) Settings.Global.putString(resolver, KEY_HOST, profile.host)
        Settings.Global.putString(resolver, KEY_MODE, profile.mode)
        return true
    }

    /** Grants the permission with root (only on rooted phones). */
    fun grantWithRoot(context: Context): Boolean = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        )
        process.waitFor() == 0 && hasPermission(context)
    } catch (e: Exception) {
        false
    }

    /** Is our accessibility service switched on in the Android settings? */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, SwitchService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
