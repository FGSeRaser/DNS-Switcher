package de.fgseraser.dnsswitcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Watches which app comes to the foreground and switches the private DNS
 * to the profile of that app (or the default profile).
 * Only window changes are read – no screen content.
 */
class SwitchService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastPackage = ""
    private var keyboards = emptySet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keyboards = try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        Store.log(this, "Automatik gestartet")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        if (pkg in IGNORED || pkg in keyboards) return
        if (!isAppWindow(pkg, cls)) return
        if (pkg == lastPackage) return
        lastPackage = pkg
        currentPackage = pkg
        // Short delay: when apps open/close quickly only the last one counts
        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable { switchFor(pkg) }
        pending = task
        handler.postDelayed(task, 350)
    }

    /** Activities count, dialogs, toasts and overlays (e.g. volume) do not. */
    private fun isAppWindow(pkg: String, cls: String): Boolean {
        if (cls.isEmpty()) return false
        return try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (e: Exception) {
            // Not visible to us or not an activity: framework classes are dialogs/toasts
            !cls.startsWith("android.") && !cls.startsWith("androidx.") && !cls.startsWith("com.android.internal.")
        }
    }

    private fun switchFor(pkg: String) {
        if (!Store.isEnabled(this)) return
        val profile = Store.profileFor(this, pkg)
        try {
            if (DnsController.apply(this, profile)) {
                val label = appLabel(pkg)
                Store.log(this, "$label → ${profile.name}")
                if (Store.showToast(this)) Toast.makeText(this, "DNS: ${profile.name}", Toast.LENGTH_SHORT).show()
                sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
            }
        } catch (e: SecurityException) {
            Store.log(this, "Keine Berechtigung – bitte den ADB-Befehl in der App ausführen")
        } catch (e: Exception) {
            Store.log(this, "Fehler: ${e.message}")
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Applies the rule for the current app again (after a rule or the default was changed). */
    fun reapply() {
        val pkg = lastPackage
        handler.post { if (pkg.isNotEmpty()) switchFor(pkg) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        restoreDefault(this, "Automatik beendet")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHANGED = "de.fgseraser.dnsswitcher.CHANGED"

        /** System windows that should not change the DNS (notification shade, keyboard, etc.). */
        private val IGNORED = setOf(
            "com.android.systemui", "android", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.aodservice", "com.samsung.android.game.gametools"
        )

        @Volatile
        var instance: SwitchService? = null
            private set

        @Volatile
        var currentPackage: String = ""
            private set

        val isRunning: Boolean get() = instance != null

        /** Back to the default profile (when pausing or when the service stops). */
        fun restoreDefault(context: Context, reason: String) {
            try {
                val profile = Store.defaultProfile(context)
                if (DnsController.apply(context, profile)) Store.log(context, "$reason → ${profile.name}")
            } catch (e: Exception) {
                // no permission – nothing we can do
            }
        }
    }
}
