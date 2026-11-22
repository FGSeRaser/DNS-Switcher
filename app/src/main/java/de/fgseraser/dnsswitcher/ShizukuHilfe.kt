package de.fgseraser.dnsswitcher

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Grants WRITE_SECURE_SETTINGS through Shizuku – no PC, no ADB cable, no root.
 * Shizuku runs as the shell user, which is allowed to run "pm grant".
 * von FGSeRaser – https://github.com/FGSeRaser
 */
object ShizukuHilfe {
    const val CODE = 4711

    /** Is the Shizuku app installed and its service running? */
    fun laeuft(): Boolean = try { Shizuku.pingBinder() && !Shizuku.isPreV11() } catch (e: Throwable) { false }

    fun hatErlaubnis(): Boolean = try { laeuft() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }

    fun anfragen() { try { Shizuku.requestPermission(CODE) } catch (e: Throwable) { } }

    /** Runs "pm grant" as shell user. Returns true when the permission is there afterwards. */
    fun freischalten(context: Context): Boolean = try {
        val cmd = arrayOf("sh", "-c", "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        // Shizuku.newProcess is hidden in API 13 but still there – it is the simplest way for one shell command
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        p.waitFor()
        DnsController.hasPermission(context)
    } catch (e: Throwable) {
        false
    }
}
