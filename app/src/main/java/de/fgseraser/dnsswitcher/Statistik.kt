package de.fgseraser.dnsswitcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Local usage statistics (from the app switches), AdGuard DNS account statistics and the update check.
 * von FGSeRaser – https://github.com/FGSeRaser
 */

/** One foreground change: which app, which profile was active, did the DNS change. */
data class Ereignis(val zeit: Long, val pkg: String, val profil: String, val gewechselt: Boolean)

object Ereignisse {
    private const val MAX = 2500
    private const val TAGE = 30
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dns_switcher", Context.MODE_PRIVATE)

    @Synchronized
    fun merken(context: Context, pkg: String, profil: String, gewechselt: Boolean) {
        val alt = JSONArray(prefs(context).getString("events", "[]"))
        val grenze = System.currentTimeMillis() - TAGE * 86_400_000L
        val neu = JSONArray()
        val start = maxOf(0, alt.length() - MAX + 1)
        for (i in start until alt.length()) {
            val o = alt.getJSONArray(i)
            if (o.getLong(0) >= grenze) neu.put(o)
        }
        neu.put(JSONArray().put(System.currentTimeMillis()).put(pkg).put(profil).put(if (gewechselt) 1 else 0))
        prefs(context).edit().putString("events", neu.toString()).apply()
    }

    fun alle(context: Context): List<Ereignis> {
        val a = JSONArray(prefs(context).getString("events", "[]"))
        return (0 until a.length()).map { val o = a.getJSONArray(it); Ereignis(o.getLong(0), o.getString(1), o.getString(2), o.optInt(3) == 1) }
    }

    fun loeschen(context: Context) = prefs(context).edit().putString("events", "[]").apply()
}

data class Nutzung(
    val wechselHeute: Int,
    val wechsel7: Int,
    val proTag: List<Pair<String, Int>>,          // last 7 days: label -> DNS switches
    val proStunde: IntArray,                      // 24 h: foreground changes per hour (7 days)
    val profilZeit: List<Pair<String, Long>>,     // profile name -> ms (7 days)
    val topApps: List<Triple<String, Long, String>>, // package, ms, profile name
    val geschuetztAnteil: Float,
    val erfasstMs: Long
)

object NutzungsStatistik {
    private val ohneFilter = setOf("one.one.one.one", "dns.google", "unfiltered.adguard-dns.com", "1dot1dot1dot1.cloudflare-dns.com")

    fun filtert(p: DnsProfile?) = p != null && p.mode == DnsController.MODE_HOSTNAME && p.host !in ohneFilter

    fun berechnen(context: Context): Nutzung {
        val jetzt = System.currentTimeMillis()
        val ab = jetzt - 7 * 86_400_000L
        val ev = Ereignisse.alle(context).filter { it.zeit >= ab }.sortedBy { it.zeit }
        val profile = Store.profiles(context).associateBy { it.id }
        val heute = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        val tage = (6 downTo 0).map { d ->
            val start = heute - d * 86_400_000L
            val label = if (d == 0) "heute" else java.text.SimpleDateFormat("EE", java.util.Locale.GERMANY).format(java.util.Date(start))
            label to ev.count { it.gewechselt && it.zeit >= start && it.zeit < start + 86_400_000L }
        }
        val stunden = IntArray(24)
        val cal = Calendar.getInstance()
        ev.forEach { cal.timeInMillis = it.zeit; stunden[cal.get(Calendar.HOUR_OF_DAY)]++ }

        val profilMs = HashMap<String, Long>()
        val appMs = HashMap<String, Long>()
        val appProfil = HashMap<String, String>()
        var geschuetzt = 0L
        var gesamt = 0L
        for (i in ev.indices) {
            val e = ev[i]
            // Longer than 3 h without a change = phone probably off/locked, cap it
            val dauer = minOf((if (i + 1 < ev.size) ev[i + 1].zeit else jetzt) - e.zeit, 3 * 3_600_000L)
            val p = profile[e.profil]
            val name = p?.name ?: e.profil
            profilMs[name] = (profilMs[name] ?: 0) + dauer
            if (e.pkg.isNotEmpty()) { appMs[e.pkg] = (appMs[e.pkg] ?: 0) + dauer; appProfil[e.pkg] = name }
            if (filtert(p)) geschuetzt += dauer
            gesamt += dauer
        }
        return Nutzung(
            wechselHeute = ev.count { it.gewechselt && it.zeit >= heute },
            wechsel7 = ev.count { it.gewechselt },
            proTag = tage,
            proStunde = stunden,
            profilZeit = profilMs.entries.sortedByDescending { it.value }.map { it.key to it.value },
            topApps = appMs.entries.sortedByDescending { it.value }.take(6).map { Triple(it.key, it.value, appProfil[it.key] ?: "") },
            geschuetztAnteil = if (gesamt == 0L) 0f else geschuetzt / gesamt.toFloat(),
            erfasstMs = gesamt
        )
    }
}

fun dauerText(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "< 1 Min."
        m < 60 -> "$m Min."
        else -> "${m / 60} Std. ${m % 60} Min."
    }
}

// ======================================================================
// AdGuard DNS account (api.adguard-dns.io) – the only way to really count blocked queries,
// because the private DNS runs inside Android and no app can see the queries.
// ======================================================================

data class AdGuardStats(
    val anfragen: Long,
    val geblockt: Long,
    val verlauf: List<Triple<Long, Long, Long>>,  // time, queries, blocked
    val topGeblockt: List<Pair<String, Long>>,
    val firmen: List<Pair<String, Long>>,
    val kategorien: List<Pair<String, Long>>
)

object AdGuardKonto {
    const val SPANNE_24H = "LAST_HOUR_24"
    const val SPANNE_7T = "LAST_DAYS_7"
    const val SPANNE_30T = "LAST_DAYS_30"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dns_switcher", Context.MODE_PRIVATE)
    fun schluessel(c: Context) = prefs(c).getString("adguard_key", "") ?: ""
    fun setSchluessel(c: Context, k: String) = prefs(c).edit().putString("adguard_key", k.trim()).apply()

    private fun holen(key: String, pfad: String): String {
        val c = URL("https://api.adguard-dns.io$pfad").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("Authorization", "ApiKey $key")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("API-Schlüssel ungültig")
            if (code !in 200..299) throw IllegalStateException("AdGuard antwortet mit $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private fun stat(o: JSONObject) = o.optLong("queries") to o.optLong("blocked")

    suspend fun laden(context: Context, spanne: String): AdGuardStats = withContext(Dispatchers.IO) {
        val key = schluessel(context)
        require(key.isNotBlank()) { "Kein API-Schlüssel" }
        val zeit = JSONObject(holen(key, "/oapi/v1/stats/time?timespan=$spanne")).getJSONArray("stats")
        val verlauf = (0 until zeit.length()).map {
            val o = zeit.getJSONObject(it); val (q, b) = stat(o.getJSONObject("value"))
            Triple(o.getLong("time_millis"), q, b)
        }.sortedBy { it.first }
        val domains = try {
            val a = JSONObject(holen(key, "/oapi/v1/stats/domains?timespan=$spanne&limit=200")).getJSONArray("stats")
            (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("domain") to stat(o.getJSONObject("value")).second }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
        } catch (e: Exception) { emptyList() }
        val firmen = try {
            val a = JSONObject(holen(key, "/oapi/v1/stats/companies?timespan=$spanne&limit=50")).getJSONArray("stats")
            (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("company_name") to stat(o.getJSONObject("value")).second }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
        } catch (e: Exception) { emptyList() }
        val kategorien = try {
            val a = JSONObject(holen(key, "/oapi/v1/stats/categories?timespan=$spanne")).getJSONArray("stats")
            (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("category_type") to o.optLong("queries") }
                .sortedByDescending { it.second }
        } catch (e: Exception) { emptyList() }
        AdGuardStats(verlauf.sumOf { it.second }, verlauf.sumOf { it.third }, verlauf, domains, firmen, kategorien)
    }

    fun kategorieName(k: String) = when (k) {
        "ADS" -> "Werbung"; "TRACKERS" -> "Tracker"; "SOCIAL_MEDIA" -> "Soziale Netze"; "CDN" -> "CDN"; else -> "Sonstiges"
    }
}

// ======================================================================
// Update check (GitHub releases)
// ======================================================================

object UpdatePruefung {
    const val SEITE = "https://github.com/FGSeRaser/DNS-Switcher/releases/latest"

    /** Returns the newer version (e.g. "1.1.4") or null if up to date. */
    suspend fun neuere(): String? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/FGSeRaser/DNS-Switcher/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@withContext null
            val tag = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).optString("tag_name").removePrefix("v")
            if (vergleich(tag, BuildConfig.VERSION_NAME) > 0) tag else null
        } finally { c.disconnect() }
    }

    private fun vergleich(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
