package de.fgseraser.dnsswitcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One DNS setting.
 * mode: "hostname" (own DNS server like AdGuard), "opportunistic" (Android default "Automatisch") or "off".
 */
data class DnsProfile(
    val id: String,
    val name: String,
    val mode: String,
    val host: String = "",
    val builtIn: Boolean = false
) {
    val description: String
        get() = when (mode) {
            DnsController.MODE_HOSTNAME -> host
            DnsController.MODE_OFF -> "Privates DNS aus"
            else -> "Automatisch (DNS vom WLAN / Mobilfunk)"
        }
}

/** Settings, rules per app and the switch history (SharedPreferences). */
object Store {

    const val PROFILE_ADGUARD = "adguard"
    const val PROFILE_NORMAL = "normal"
    const val PROFILE_OFF = "off"

    /** Ready-made servers for "Profil hinzufügen". */
    val presets = listOf(
        "AdGuard Familie" to "family.adguard-dns.com",
        "AdGuard ungefiltert" to "unfiltered.adguard-dns.com",
        "Mullvad Werbeblocker" to "adblock.dns.mullvad.net",
        "Cloudflare" to "one.one.one.one",
        "Google" to "dns.google",
        "Quad9" to "dns.quad9.net"
    )

    private val builtIns = listOf(
        DnsProfile(PROFILE_ADGUARD, "AdGuard (Werbeblocker)", DnsController.MODE_HOSTNAME, "dns.adguard-dns.com", true),
        DnsProfile(PROFILE_NORMAL, "Normal", DnsController.MODE_AUTOMATIC, "", true),
        DnsProfile(PROFILE_OFF, "Aus", DnsController.MODE_OFF, "", true)
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("dns_switcher", Context.MODE_PRIVATE)

    // ---------- Profiles ----------

    fun profiles(context: Context): List<DnsProfile> {
        val result = builtIns.toMutableList()
        val array = JSONArray(prefs(context).getString("profiles", "[]"))
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            result.add(DnsProfile(o.getString("id"), o.getString("name"), DnsController.MODE_HOSTNAME, o.getString("host")))
        }
        return result
    }

    fun profile(context: Context, id: String?): DnsProfile? = profiles(context).firstOrNull { it.id == id }

    fun addProfile(context: Context, name: String, host: String): DnsProfile {
        val profile = DnsProfile("p" + System.currentTimeMillis(), name.trim(), DnsController.MODE_HOSTNAME, host.trim().lowercase(Locale.ROOT))
        val array = JSONArray(prefs(context).getString("profiles", "[]"))
        array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("host", profile.host))
        prefs(context).edit().putString("profiles", array.toString()).apply()
        return profile
    }

    fun deleteProfile(context: Context, id: String) {
        val array = JSONArray(prefs(context).getString("profiles", "[]"))
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("id") != id) kept.put(array.getJSONObject(i))
        }
        prefs(context).edit().putString("profiles", kept.toString()).apply()
        // Rules that used this profile fall back to the default
        val rules = rules(context).filterValues { it != id }
        saveRules(context, rules)
        if (defaultProfileId(context) == id) setDefaultProfileId(context, PROFILE_ADGUARD)
    }

    // ---------- Default and rules ----------

    /** Profile for all apps without a rule (normally AdGuard). */
    fun defaultProfileId(context: Context): String = prefs(context).getString("default", PROFILE_ADGUARD) ?: PROFILE_ADGUARD

    fun setDefaultProfileId(context: Context, id: String) = prefs(context).edit().putString("default", id).apply()

    fun defaultProfile(context: Context): DnsProfile = profile(context, defaultProfileId(context)) ?: builtIns[0]

    /** Package name -> profile id. */
    fun rules(context: Context): Map<String, String> {
        val o = JSONObject(prefs(context).getString("rules", "{}") ?: "{}")
        val result = HashMap<String, String>()
        o.keys().forEach { key -> result[key] = o.getString(key) }
        return result
    }

    private fun saveRules(context: Context, rules: Map<String, String>) {
        val o = JSONObject()
        rules.forEach { (k, v) -> o.put(k, v) }
        prefs(context).edit().putString("rules", o.toString()).apply()
    }

    /** profileId = null removes the rule (the app then uses the default). */
    fun setRule(context: Context, packageName: String, profileId: String?) {
        val rules = rules(context).toMutableMap()
        if (profileId == null) rules.remove(packageName) else rules[packageName] = profileId
        saveRules(context, rules)
    }

    /** The profile that should be active while this app is in the foreground. */
    fun profileFor(context: Context, packageName: String): DnsProfile =
        profile(context, rules(context)[packageName]) ?: defaultProfile(context)

    // ---------- Options ----------

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("enabled", value).apply()

    fun showToast(context: Context) = prefs(context).getBoolean("toast", true)
    fun setShowToast(context: Context, value: Boolean) = prefs(context).edit().putBoolean("toast", value).apply()

    // ---------- History ----------

    fun log(context: Context, text: String) {
        val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        val array = JSONArray(prefs(context).getString("log", "[]"))
        val updated = JSONArray().put("$time  $text")
        for (i in 0 until minOf(array.length(), 199)) updated.put(array.getString(i))
        prefs(context).edit().putString("log", updated.toString()).apply()
    }

    fun history(context: Context): List<String> {
        val array = JSONArray(prefs(context).getString("log", "[]"))
        return (0 until array.length()).map { array.getString(it) }
    }

    fun clearHistory(context: Context) = prefs(context).edit().putString("log", "[]").apply()
}
