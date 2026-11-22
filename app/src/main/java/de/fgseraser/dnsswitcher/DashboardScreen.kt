@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.fgseraser.dnsswitcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.fgseraser.dnsswitcher.ui.Palette
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Nerd-Dashboard: live protection check, network internals, AdGuard account statistics,
 * local usage, DoT benchmark and block test. von FGSeRaser – https://github.com/FGSeRaser
 */

private object Neon {
    val Cyan = Color(0xFF35E0C8)
    val Lila = Color(0xFF9B7BFF)
    val Pink = Color(0xFFFF5C9A)
    val Gelb = Color(0xFFFFC94D)
    val reihe = listOf(Palette.Accent, Cyan, Lila, Pink, Gelb, Palette.Good, Color(0xFFFF8A4D))
}

/** Results survive tab changes while the app is open. */
private object NerdZustand {
    var live by mutableStateOf<LiveCheck?>(null)
    var liveLaeuft by mutableStateOf(false)
    var bench by mutableStateOf<List<BenchErgebnis>?>(null)
    var benchLaeuft by mutableStateOf(false)
    var block by mutableStateOf<BlockTest?>(null)
    var blockLaeuft by mutableStateOf(false)
    var ag by mutableStateOf<AdGuardStats?>(null)
    var agSpanne by mutableStateOf(AdGuardKonto.SPANNE_24H)
    var agLaeuft by mutableStateOf(false)
    var agFehler by mutableStateOf("")
}

private val zahl: NumberFormat = NumberFormat.getIntegerInstance(Locale.GERMANY)
private fun z(n: Long) = zahl.format(n)
private fun prozent(f: Float) = "${(f * 100).toInt()} %"

@Composable
internal fun DashboardScreen(stamp: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = NerdZustand
    val netz = remember(stamp) { NetzDaten.lesen(context) }
    val nutzung = remember(stamp) { NutzungsStatistik.berechnen(context) }

    fun liveStarten() {
        if (s.liveLaeuft) return
        s.liveLaeuft = true
        scope.launch { s.live = try { LivePruefung.pruefen(context) } catch (e: Exception) { null }; s.liveLaeuft = false }
    }
    fun agLaden() {
        if (AdGuardKonto.schluessel(context).isBlank() || s.agLaeuft) return
        s.agLaeuft = true; s.agFehler = ""
        scope.launch {
            try { s.ag = AdGuardKonto.laden(context, s.agSpanne) } catch (e: Exception) { s.agFehler = e.message ?: "Fehler" }
            s.agLaeuft = false
        }
    }
    LaunchedEffect(stamp) { liveStarten() }
    LaunchedEffect(Unit) { if (s.ag == null) agLaden() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nerd-Dashboard", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                Spacer(Modifier.width(10.dp))
                Puls(if ((s.live?.quote ?: 0f) > 0.5f) Palette.Good else Neon.Gelb)
            }
            Text("> dns --live --stats  ·  von FGSeRaser", fontSize = 12.sp, color = Neon.Cyan, fontFamily = FontFamily.Monospace)
        }

        // ---------------------------------------------------------- Hero: live protection
        Kachel("Schutz jetzt", "Live-Abfrage über das System-DNS, ohne Cache") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val l = s.live
                Ring(l?.quote ?: 0f, if (l == null) "…" else prozent(l.quote), "geblockt",
                    if ((l?.quote ?: 0f) >= 0.7f) Palette.Good else if ((l?.quote ?: 0f) >= 0.3f) Neon.Gelb else Palette.Danger, 120.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Wert("Werbe-Domains", if (l == null) "…" else "${l.geblockt} / ${l.getestet} blockiert")
                    Wert("Normale Seiten", if (l == null) "…" else "${l.kontrolleOk} / ${l.kontrolleGesamt} erreichbar",
                        if (l != null && l.kontrolleOk < l.kontrolleGesamt) Palette.Danger else Palette.Text)
                    Wert("Antwortzeit", if (l == null || l.latenzMs == 0L) "…" else "${l.latenzMs} ms")
                    Wert("Stand", l?.let { uhrzeit(it.zeit) } ?: "…")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DnsController.describeCurrent(context), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.liveLaeuft) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { liveStarten() }) { Text("Neu prüfen") }
            }
        }

        // ---------------------------------------------------------- AdGuard account
        AdGuardKarte(s, ::agLaden)

        // ---------------------------------------------------------- local usage
        Kachel("Deine Nutzung", "Letzte 7 Tage · lokal aus den App-Wechseln") {
            if (nutzung.erfasstMs == 0L) {
                Text("Noch keine Daten. Sobald die Automatik läuft, füllt sich das hier.", color = Palette.Muted, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(nutzung.geschuetztAnteil, prozent(nutzung.geschuetztAnteil), "mit Filter", Neon.Cyan, 96.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Wert("DNS-Wechsel heute", "${nutzung.wechselHeute}")
                        Wert("DNS-Wechsel 7 Tage", "${nutzung.wechsel7}")
                        Wert("Erfasste Zeit", dauerText(nutzung.erfasstMs))
                    }
                }
                Unter("Wechsel pro Tag")
                TagesBalken(nutzung.proTag)
                Unter("Aktivität nach Uhrzeit")
                Heatmap(nutzung.proStunde)
                Unter("Zeit pro DNS-Profil")
                Donut(nutzung.profilZeit.take(6).mapIndexed { i, (n, ms) -> Triple(n, ms.toFloat(), Neon.reihe[i % Neon.reihe.size]) })
                Unter("Meistgenutzte Apps")
                val max = nutzung.topApps.maxOfOrNull { it.second } ?: 1L
                nutzung.topApps.forEachIndexed { i, (pkg, ms, profil) ->
                    Balken(appName(context, pkg), ms.toFloat() / max, "${dauerText(ms)} · $profil", Neon.reihe[i % Neon.reihe.size])
                }
            }
        }

        // ---------------------------------------------------------- benchmark
        Kachel("DNS-Benchmark", "DNS-over-TLS direkt auf Port 853 · 8 Abfragen je Server") {
            val b = s.bench
            if (b == null && !s.benchLaeuft) Text("Misst, welcher Server von deinem Netz aus am schnellsten antwortet.", color = Palette.Muted, fontSize = 13.sp)
            if (b != null) {
                val max = (b.filter { it.fehler == null }.maxOfOrNull { it.max } ?: 1L).coerceAtLeast(1L)
                b.forEachIndexed { i, r -> BenchZeile(r, max, i == 0 && r.fehler == null) }
            }
            Spacer(Modifier.height(6.dp))
            Knopf(if (b == null) "Benchmark starten" else "Nochmal messen", s.benchLaeuft) {
                s.benchLaeuft = true
                scope.launch { s.bench = try { Benchmark.messen(context) } catch (e: Exception) { emptyList() }; s.benchLaeuft = false }
            }
        }

        // ---------------------------------------------------------- block test
        Kachel("Blockier-Test", "${BlockListe.WERBUNG.size} Werbe-/Tracker-Domains gegen jeden Server") {
            val t = s.block
            if (t == null && !s.blockLaeuft) Text("Vergleicht deine Profile: Wie viel Werbung, Tracker und Spiele-Werbung filtert welcher Server? Cloudflare dient als ungefilterte Referenz.",
                color = Palette.Muted, fontSize = 13.sp)
            if (t != null) BlockErgebnisse(t)
            Spacer(Modifier.height(6.dp))
            Knopf(if (t == null) "Test starten" else "Nochmal testen", s.blockLaeuft) {
                s.blockLaeuft = true
                scope.launch { s.block = try { BlockListe.testen(context) } catch (e: Exception) { null }; s.blockLaeuft = false }
            }
        }

        // ---------------------------------------------------------- network
        Kachel("Netzwerk-Interna", "LinkProperties des aktiven Netzes") {
            netz.werte.forEach { (k, v) -> Wert(k, v) }
        }
    }
}

// ======================================================================
// AdGuard card
// ======================================================================

@Composable
private fun AdGuardKarte(s: NerdZustand, laden: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(AdGuardKonto.schluessel(context)) }
    var einrichten by remember { mutableStateOf(false) }
    val hatKey = AdGuardKonto.schluessel(context).isNotBlank()

    Kachel("Geblockt – AdGuard-Konto", if (hatKey) "Echte Zahlen von api.adguard-dns.io" else "Optional · für echte Zähler") {
        if (!hatKey || einrichten) {
            Text("Android lässt keine App sehen, was das private DNS abfragt. Echte Zähler gibt es nur mit einem kostenlosen AdGuard-DNS-Konto: Dort bekommst du eine eigene Adresse (xxxx.d.adguard-dns.com), die du hier als Profil einträgst, und einen API-Schlüssel.",
                color = Palette.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(key, { key = it }, label = { Text("API-Schlüssel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                Button(onClick = { AdGuardKonto.setSchluessel(context, key); einrichten = false; s.ag = null; laden() }, enabled = key.isNotBlank()) { Text("Speichern") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://adguard-dns.io/de/dashboard/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("AdGuard-Konto öffnen") }
            }
            Text("Schlüssel erstellen: AdGuard DNS → Einstellungen → API-Schlüssel. Er bleibt nur auf diesem Handy.", color = Palette.Muted, fontSize = 11.sp)
            return@Kachel
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AdGuardKonto.SPANNE_24H to "24 Std.", AdGuardKonto.SPANNE_7T to "7 Tage", AdGuardKonto.SPANNE_30T to "30 Tage").forEach { (id, t) ->
                FilterChip(selected = s.agSpanne == id, onClick = { s.agSpanne = id; laden() }, label = { Text(t) })
            }
        }
        if (s.agLaeuft && s.ag == null) Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator() }
        if (s.agFehler.isNotBlank()) Text("⚠ ${s.agFehler}", color = Palette.Danger, fontSize = 13.sp)
        s.ag?.let { a ->
            val quote = if (a.anfragen == 0L) 0f else a.geblockt / a.anfragen.toFloat()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Ring(quote, prozent(quote), "geblockt", Neon.Pink, 110.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GrosseZahl(z(a.geblockt), "geblockt", Neon.Pink)
                    GrosseZahl(z(a.anfragen), "Anfragen", Palette.Accent)
                }
            }
            if (a.verlauf.size > 1) {
                Unter("Verlauf  ·  Anfragen / geblockt")
                Verlauf(a.verlauf)
            }
            if (a.kategorien.isNotEmpty()) {
                Unter("Kategorien")
                val max = a.kategorien.maxOf { it.second }.coerceAtLeast(1)
                a.kategorien.forEachIndexed { i, (k, n) -> Balken(AdGuardKonto.kategorieName(k), n.toFloat() / max, z(n), Neon.reihe[i % Neon.reihe.size]) }
            }
            if (a.topGeblockt.isNotEmpty()) {
                Unter("Top geblockte Domains")
                val max = a.topGeblockt.first().second.coerceAtLeast(1)
                a.topGeblockt.forEach { (d, n) -> Balken(d, n.toFloat() / max, z(n), Neon.Pink, mono = true) }
            }
            if (a.firmen.isNotEmpty()) {
                Unter("Wer am meisten mitlesen wollte")
                val max = a.firmen.first().second.coerceAtLeast(1)
                a.firmen.forEach { (f, n) -> Balken(f, n.toFloat() / max, z(n), Neon.Lila) }
            }
        }
        Row {
            TextButton(onClick = laden) { Text(if (s.agLaeuft) "Lädt …" else "Aktualisieren") }
            TextButton(onClick = { einrichten = true }) { Text("Schlüssel ändern") }
        }
    }
}

// ======================================================================
// Benchmark / block test rows
// ======================================================================

@Composable
private fun BenchZeile(r: BenchErgebnis, max: Long, sieger: Boolean) {
    var offen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { offen = !offen }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (sieger) "🏆 " else "") + r.name, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.fehler != null) "Fehler" else "Ø ${r.schnitt} ms", color = if (r.fehler != null) Palette.Danger else if (sieger) Palette.Good else Palette.Text,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (r.fehler == null) {
            // whisker: min .. max, dot = average
            Canvas(Modifier.fillMaxWidth().height(16.dp).padding(top = 6.dp)) {
                val w = size.width
                drawRoundRect(Palette.Border, Offset(0f, size.height / 2 - 2f), Size(w, 4f), CornerRadius(2f))
                val x1 = w * r.min / max; val x2 = w * r.max / max; val xa = w * r.schnitt / max
                drawRoundRect(Brush.horizontalGradient(listOf(Neon.Cyan, Palette.Accent)), Offset(x1, size.height / 2 - 3f),
                    Size((x2 - x1).coerceAtLeast(4f), 6f), CornerRadius(3f))
                drawCircle(Color.White, 5f, Offset(xa, size.height / 2))
            }
            Text("min ${r.min} · max ${r.max} · Jitter ${r.jitter} ms · TCP ${r.tls?.tcpMs} + TLS ${r.tls?.tlsMs} ms",
                color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text(r.fehler, color = Palette.Muted, fontSize = 11.sp)
        }
        AnimatedVisibility(offen && r.tls != null) {
            val t = r.tls!!
            Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Back).padding(10.dp)) {
                Wert("Host", r.host); Wert("Server-IP", t.serverIp); Wert("Protokoll", t.protokoll)
                Wert("Cipher", t.cipher); Wert("Zertifikat", t.zertifikat); Wert("Aussteller", t.aussteller); Wert("Gültig bis", t.gueltigBis)
            }
        }
    }
}

@Composable
private fun BlockErgebnisse(t: BlockTest) {
    var matrix by remember { mutableStateOf(false) }
    if (t.gueltig.isEmpty()) {
        Text("Referenz (Cloudflare) nicht erreichbar – Test nicht aussagekräftig.", color = Palette.Danger, fontSize = 13.sp)
        return
    }
    t.server.forEachIndexed { i, e ->
        Column(Modifier.padding(vertical = 6.dp)) {
            if (e.fehler != null) {
                Text("${e.name}: ${e.fehler}", color = Palette.Danger, fontSize = 13.sp)
                return@Column
            }
            val q = t.quote(e)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(prozent(q), color = farbeFuer(q), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Fortschritt(q, farbeFuer(q))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                BlockListe.KATEGORIEN.forEach { k -> Text("$k ${prozent(t.quote(e, k))}", color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            val fehl = t.fehlblockiert(e)
            if (fehl.isNotEmpty()) Text("⚠ blockt auch normale Seiten: ${fehl.joinToString()}", color = Neon.Gelb, fontSize = 11.sp)
        }
    }
    TextButton(onClick = { matrix = !matrix }) { Text(if (matrix) "Domain-Matrix ausblenden" else "Domain-Matrix zeigen") }
    AnimatedVisibility(matrix) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Text("Domain", Modifier.width(220.dp), color = Palette.Muted, fontSize = 11.sp)
                t.server.filter { it.fehler == null }.forEach { Text(it.name.take(10), Modifier.width(78.dp), color = Palette.Muted, fontSize = 11.sp, maxLines = 1) }
            }
            t.gueltig.forEach { d ->
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text(d.name, Modifier.width(220.dp), color = Palette.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    t.server.filter { it.fehler == null }.forEach { e ->
                        val g = e.antworten[d.name]?.geblockt == true
                        Text(if (g) "■ blockt" else "· frei", Modifier.width(78.dp), color = if (g) Palette.Good else Palette.Danger, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
    Text("Stand ${uhrzeit(t.zeit)} · ${t.gueltig.size} existierende Test-Domains", color = Palette.Muted, fontSize = 11.sp)
}

private fun farbeFuer(q: Float) = when {
    q >= 0.75f -> Palette.Good
    q >= 0.35f -> Neon.Gelb
    else -> Palette.Danger
}

private fun appName(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { pkg }

// ======================================================================
// Building blocks
// ======================================================================

@Composable
private fun Kachel(titel: String, untertitel: String, inhalt: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Palette.SurfaceHigh, Palette.Surface)))
            .border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(titel.uppercase(), color = Neon.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
        Text(untertitel, color = Palette.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        inhalt()
    }
}

@Composable
private fun Unter(t: String) = Text(t, color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun Wert(k: String, v: String, farbe: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(k, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.width(118.dp))
        Text(v, color = farbe, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GrosseZahl(wert: String, label: String, farbe: Color) {
    Column {
        Text(wert, color = farbe, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Knopf(text: String, laeuft: Boolean, klick: () -> Unit) {
    OutlinedButton(onClick = klick, enabled = !laeuft, modifier = Modifier.fillMaxWidth()) {
        if (laeuft) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Läuft …") }
        else Text(text)
    }
}

@Composable
private fun Puls(farbe: Color) {
    val t = rememberInfiniteTransition(label = "puls")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).clip(CircleShape).background(farbe.copy(alpha = a)))
}

@Composable
private fun Ring(wert: Float, mitte: String, unten: String, farbe: Color, groesse: Dp) {
    val anim by animateFloatAsState(wert.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(Modifier.size(groesse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = size.minDimension * 0.10f
            val inset = st / 2
            val sz = Size(size.width - st, size.height - st)
            drawArc(Palette.Border, 135f, 270f, false, Offset(inset, inset), sz, style = Stroke(st, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(farbe.copy(alpha = 0.5f), farbe, farbe.copy(alpha = 0.5f))), 135f, 270f * anim, false,
                Offset(inset, inset), sz, style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mitte, color = Palette.Text, fontSize = (groesse.value / 5.2f).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(unten, color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Fortschritt(wert: Float, farbe: Color) {
    val anim by animateFloatAsState(wert.coerceIn(0f, 1f), tween(800), label = "fort")
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Border)) {
        Box(Modifier.fillMaxWidth(anim).fillMaxHeight().clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(farbe.copy(alpha = 0.6f), farbe))))
    }
}

@Composable
private fun Balken(label: String, wert: Float, rechts: String, farbe: Color, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, color = Palette.Text, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rechts, color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Fortschritt(wert, farbe)
    }
}

@Composable
private fun TagesBalken(tage: List<Pair<String, Int>>) {
    val max = (tage.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        tage.forEach { (label, n) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", color = Palette.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val h by animateFloatAsState(n / max.toFloat(), tween(800), label = "tag")
                Box(Modifier.fillMaxWidth().height((70 * h).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(Brush.verticalGradient(listOf(Neon.Cyan, Palette.Accent))))
                Text(label, color = Palette.Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Heatmap(stunden: IntArray) {
    val max = (stunden.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stunden.forEach { n ->
            val a = 0.08f + 0.92f * (n / max.toFloat())
            Box(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(3.dp)).background(Neon.Lila.copy(alpha = a)))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
            Text(t, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(if (i == 4) 0.5f else 1f))
        }
    }
}

@Composable
private fun Donut(teile: List<Triple<String, Float, Color>>) {
    val summe = teile.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(96.dp)) {
            var start = -90f
            val st = size.minDimension * 0.18f
            val sz = Size(size.width - st, size.height - st)
            teile.forEach { (_, v, c) ->
                val sweep = 360f * v / summe
                drawArc(c, start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(st / 2, st / 2), sz, style = Stroke(st))
                start += sweep
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            teile.forEach { (n, v, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text("$n  ${(100 * v / summe).toInt()} %", color = Palette.Text, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Queries (blue area) and blocked (pink line) over time. */
@Composable
private fun Verlauf(punkte: List<Triple<Long, Long, Long>>) {
    val max = (punkte.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L).toFloat()
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width; val h = size.height
        val dx = w / (punkte.size - 1).coerceAtLeast(1)
        fun pfad(wert: (Triple<Long, Long, Long>) -> Long, schliessen: Boolean) = Path().apply {
            punkte.forEachIndexed { i, p ->
                val x = i * dx; val y = h - h * wert(p) / max
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            if (schliessen) { lineTo(w, h); lineTo(0f, h); close() }
        }
        for (g in 1..3) drawLine(Palette.Border, Offset(0f, h * g / 4), Offset(w, h * g / 4), 1f)
        drawPath(pfad({ it.second }, true), Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.45f), Color.Transparent)))
        drawPath(pfad({ it.second }, false), Palette.Accent, style = Stroke(3f))
        drawPath(pfad({ it.third }, true), Brush.verticalGradient(listOf(Neon.Pink.copy(alpha = 0.5f), Color.Transparent)))
        drawPath(pfad({ it.third }, false), Neon.Pink, style = Stroke(3f))
    }
    Row {
        Text("■ Anfragen", color = Palette.Accent, fontSize = 11.sp); Spacer(Modifier.width(12.dp))
        Text("■ geblockt", color = Neon.Pink, fontSize = 11.sp); Spacer(Modifier.weight(1f))
        Text("max ${z(max.toLong())}", color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
