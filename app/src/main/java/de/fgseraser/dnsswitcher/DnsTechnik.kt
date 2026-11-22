package de.fgseraser.dnsswitcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

/**
 * Nerd stuff: DNS messages by hand, DNS-over-TLS (the protocol Android's private DNS uses),
 * benchmark, block test, live check of the system resolver and network info.
 * von FGSeRaser – https://github.com/FGSeRaser
 */

/** Answer of one DNS query. rcode: 0 = OK, 3 = NXDOMAIN, 5 = REFUSED, -1 = error/timeout. */
data class DnsAntwort(val rcode: Int, val ips: List<String>, val ms: Long) {
    /** Typical answers of a filtering DNS server for a blocked domain. */
    val geblockt: Boolean
        get() = rcode == 3 || rcode == 5 || (rcode == 0 && (ips.isEmpty() || ips.all { it in NULL_IPS }))
    val fehler: Boolean get() = rcode < 0 || rcode == 2

    companion object {
        val NULL_IPS = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}

// ======================================================================
// DNS messages (RFC 1035)
// ======================================================================

object DnsNachricht {
    const val TYP_A = 1

    fun anfrage(id: Int, name: String, typ: Int = TYP_A): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100) // recursion desired
        d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        for (teil in name.trimEnd('.').split('.')) {
            val b = teil.toByteArray(Charsets.US_ASCII)
            d.writeByte(b.size); d.write(b)
        }
        d.writeByte(0); d.writeShort(typ); d.writeShort(1)
        return out.toByteArray()
    }

    /** Returns rcode and the A/AAAA addresses. */
    fun lesen(b: ByteArray): Pair<Int, List<String>> {
        if (b.size < 12) return -1 to emptyList()
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        val rcode = u16(2) and 0x0F
        val qd = u16(4)
        val an = u16(6)
        var p = 12
        fun nameUeberspringen() {
            while (p < b.size) {
                val l = b[p].toInt() and 0xFF
                if (l == 0) { p++; return }
                if (l and 0xC0 == 0xC0) { p += 2; return }
                p += l + 1
            }
        }
        repeat(qd) { nameUeberspringen(); p += 4 }
        val ips = mutableListOf<String>()
        repeat(an) {
            if (p >= b.size) return@repeat
            nameUeberspringen()
            if (p + 10 > b.size) return@repeat
            val typ = u16(p); val len = u16(p + 8)
            p += 10
            if (p + len > b.size) return@repeat
            if ((typ == 1 && len == 4) || (typ == 28 && len == 16)) {
                ips.add(InetAddress.getByAddress(b.copyOfRange(p, p + len)).hostAddress ?: "")
            }
            p += len
        }
        return rcode to ips
    }
}

// ======================================================================
// DNS-over-TLS (port 853)
// ======================================================================

data class TlsInfo(
    val serverIp: String,
    val protokoll: String,
    val cipher: String,
    val zertifikat: String,
    val aussteller: String,
    val gueltigBis: String,
    val tcpMs: Long,
    val tlsMs: Long
)

class DotVerbindung(private val host: String) : AutoCloseable {
    private var socket: SSLSocket? = null
    private var ein: DataInputStream? = null
    private var aus: DataOutputStream? = null
    private var id = (Math.random() * 60000).toInt()
    var info: TlsInfo? = null
        private set

    fun oeffnen(): TlsInfo {
        val t0 = System.nanoTime()
        val roh = Socket()
        roh.connect(InetSocketAddress(host, 853), 4000)
        val t1 = System.nanoTime()
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(roh, host, 853, true) as SSLSocket
        s.soTimeout = 4000
        s.startHandshake()
        val t2 = System.nanoTime()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
            s.close(); throw SecurityException("Zertifikat passt nicht zu $host")
        }
        socket = s
        ein = DataInputStream(s.inputStream.buffered())
        aus = DataOutputStream(s.outputStream.buffered())
        val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
        fun cn(dn: String?) = dn?.split(",")?.map { it.trim() }?.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: (dn ?: "?")
        val i = TlsInfo(
            serverIp = roh.inetAddress?.hostAddress ?: "?",
            protokoll = s.session.protocol,
            cipher = s.session.cipherSuite,
            zertifikat = cn(cert?.subjectX500Principal?.name),
            aussteller = cn(cert?.issuerX500Principal?.name),
            gueltigBis = cert?.notAfter?.let { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(it) } ?: "?",
            tcpMs = (t1 - t0) / 1_000_000,
            tlsMs = (t2 - t1) / 1_000_000
        )
        info = i
        return i
    }

    fun frage(name: String): DnsAntwort {
        val o = aus ?: throw IllegalStateException("nicht verbunden")
        val e = ein!!
        id = (id + 1) and 0xFFFF
        val msg = DnsNachricht.anfrage(id, name)
        val t0 = System.nanoTime()
        o.writeShort(msg.size); o.write(msg); o.flush()
        val len = e.readUnsignedShort()
        val buf = ByteArray(len)
        e.readFully(buf)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val (rcode, ips) = DnsNachricht.lesen(buf)
        return DnsAntwort(rcode, ips, ms)
    }

    override fun close() { try { socket?.close() } catch (e: Exception) { } }
}

// ======================================================================
// Benchmark
// ======================================================================

data class BenchErgebnis(
    val name: String,
    val host: String,
    val tls: TlsInfo?,
    val zeiten: List<Long>,
    val fehler: String? = null
) {
    val min get() = zeiten.minOrNull() ?: 0
    val max get() = zeiten.maxOrNull() ?: 0
    val schnitt get() = if (zeiten.isEmpty()) 0 else zeiten.average().toLong()
    val jitter get() = max - min
}

object Benchmark {
    private val testNamen = listOf("google.com", "wikipedia.org", "github.com", "cloudflare.com", "amazon.de", "youtube.com", "whatsapp.net", "example.com")

    /** All private-DNS servers of the profiles, plus Cloudflare as reference. */
    fun ziele(context: Context): List<Pair<String, String>> {
        val l = Store.profiles(context).filter { it.mode == DnsController.MODE_HOSTNAME && it.host.isNotBlank() }
            .map { it.name to it.host }.toMutableList()
        if (l.none { it.second == "one.one.one.one" }) l.add("Cloudflare (Referenz)" to "one.one.one.one")
        return l.distinctBy { it.second }
    }

    suspend fun messen(context: Context): List<BenchErgebnis> = withContext(Dispatchers.IO) {
        coroutineScope {
            ziele(context).map { (name, host) ->
                async {
                    try {
                        DotVerbindung(host).use { v ->
                            val tls = v.oeffnen()
                            v.frage("example.com") // warm-up
                            val z = testNamen.map { v.frage(it).ms }
                            BenchErgebnis(name, host, tls, z)
                        }
                    } catch (e: Exception) {
                        BenchErgebnis(name, host, null, emptyList(), e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll().sortedWith(compareBy({ it.fehler != null }, { it.schnitt }))
        }
    }
}

// ======================================================================
// Block test
// ======================================================================

data class TestDomain(val name: String, val kategorie: String)

data class BlockErgebnis(
    val name: String,
    val host: String,
    val antworten: Map<String, DnsAntwort>,
    val fehler: String? = null
)

data class BlockTest(
    val referenz: Map<String, DnsAntwort>,
    val server: List<BlockErgebnis>,
    val zeit: Long = System.currentTimeMillis()
) {
    /** Domains that really exist (the unfiltered reference resolves them). */
    val gueltig: List<TestDomain>
        get() = BlockListe.WERBUNG.filter { referenz[it.name]?.let { a -> !a.geblockt && !a.fehler } == true }

    fun quote(e: BlockErgebnis, kategorie: String? = null): Float {
        val l = gueltig.filter { kategorie == null || it.kategorie == kategorie }
        if (l.isEmpty()) return 0f
        return l.count { e.antworten[it.name]?.geblockt == true } / l.size.toFloat()
    }

    fun fehlblockiert(e: BlockErgebnis): List<String> =
        BlockListe.KONTROLLE.filter { e.antworten[it]?.geblockt == true }
}

object BlockListe {
    const val WERBE = "Werbung"
    const val TRACKER = "Tracker"
    const val SPIELE = "Spiele-Werbung"

    val WERBUNG = listOf(
        TestDomain("doubleclick.net", WERBE), TestDomain("googleadservices.com", WERBE),
        TestDomain("googlesyndication.com", WERBE), TestDomain("pagead2.googlesyndication.com", WERBE),
        TestDomain("adservice.google.com", WERBE), TestDomain("ads.yahoo.com", WERBE),
        TestDomain("adnxs.com", WERBE), TestDomain("criteo.com", WERBE),
        TestDomain("taboola.com", WERBE), TestDomain("outbrain.com", WERBE),
        TestDomain("amazon-adsystem.com", WERBE), TestDomain("ads.pubmatic.com", WERBE),
        TestDomain("rubiconproject.com", WERBE),

        TestDomain("app-measurement.com", TRACKER), TestDomain("google-analytics.com", TRACKER),
        TestDomain("scorecardresearch.com", TRACKER), TestDomain("static.hotjar.com", TRACKER),
        TestDomain("api.mixpanel.com", TRACKER), TestDomain("app.adjust.com", TRACKER),
        TestDomain("t.appsflyer.com", TRACKER), TestDomain("bat.bing.com", TRACKER),
        TestDomain("analytics.tiktok.com", TRACKER), TestDomain("connect.facebook.net", TRACKER),

        TestDomain("config.unityads.unity3d.com", SPIELE), TestDomain("auction.unityads.unity3d.com", SPIELE),
        TestDomain("d.applovin.com", SPIELE), TestDomain("rt.applovin.com", SPIELE),
        TestDomain("ms.applovin.com", SPIELE), TestDomain("outcome-ssp.supersonicads.com", SPIELE),
        TestDomain("init.supersonicads.com", SPIELE), TestDomain("api.vungle.com", SPIELE),
        TestDomain("adc3-launch.adcolony.com", SPIELE), TestDomain("live.chartboost.com", SPIELE),
        TestDomain("googleads.g.doubleclick.net", SPIELE), TestDomain("an.facebook.com", SPIELE),
        TestDomain("ads.mopub.com", SPIELE), TestDomain("connect.tapjoy.com", SPIELE)
    )

    /** Must work – if blocked, the filter is too strict. */
    val KONTROLLE = listOf("google.com", "wikipedia.org", "github.com", "whatsapp.net", "play.googleapis.com")

    val KATEGORIEN = listOf(WERBE, TRACKER, SPIELE)

    private val alle get() = WERBUNG.map { it.name } + KONTROLLE

    private fun pruefen(host: String): Map<String, DnsAntwort> {
        val r = LinkedHashMap<String, DnsAntwort>()
        var v: DotVerbindung? = null
        try {
            for (d in alle) {
                var a: DnsAntwort? = null
                for (versuch in 0..1) {
                    try {
                        if (v == null) v = DotVerbindung(host).also { it.oeffnen() }
                        a = v!!.frage(d)
                        break
                    } catch (e: Exception) {
                        v?.close(); v = null
                        if (versuch == 1) throw e
                    }
                }
                r[d] = a ?: DnsAntwort(-1, emptyList(), 0)
            }
        } finally { v?.close() }
        return r
    }

    suspend fun testen(context: Context): BlockTest = withContext(Dispatchers.IO) {
        coroutineScope {
            val ref = async { try { pruefen("one.one.one.one") } catch (e: Exception) { emptyMap() } }
            val server = Benchmark.ziele(context).filter { it.second != "one.one.one.one" }.map { (name, host) ->
                async {
                    try { BlockErgebnis(name, host, pruefen(host)) }
                    catch (e: Exception) { BlockErgebnis(name, host, emptyMap(), e.message ?: e.javaClass.simpleName) }
                }
            }
            val liste = server.awaitAll()
            BlockTest(ref.await(), liste.sortedWith(compareBy({ it.fehler != null }, { -(it.antworten.count { a -> a.value.geblockt }) })))
        }
    }
}

// ======================================================================
// Live check of what the phone really uses right now (system resolver)
// ======================================================================

data class LiveCheck(
    val geblockt: Int,
    val getestet: Int,
    val kontrolleOk: Int,
    val kontrolleGesamt: Int,
    val latenzMs: Long,
    val zeit: Long = System.currentTimeMillis()
) {
    val quote get() = if (getestet == 0) 0f else geblockt / getestet.toFloat()
}

object LivePruefung {
    private val probe = listOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google.com", "app-measurement.com",
        "config.unityads.unity3d.com", "d.applovin.com", "outcome-ssp.supersonicads.com", "api.vungle.com",
        "scorecardresearch.com", "ads.yahoo.com", "googleads.g.doubleclick.net", "app.adjust.com"
    )
    private val pool = Executors.newFixedThreadPool(6)

    private suspend fun system(context: Context, name: String): DnsAntwort {
        val t0 = System.nanoTime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val netz = cm.activeNetwork
            val a = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<DnsAntwort> { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    DnsResolver.getInstance().query(netz, name, DnsResolver.TYPE_A, DnsResolver.FLAG_NO_CACHE_LOOKUP, pool, cancel,
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                if (cont.isActive) cont.resume(DnsAntwort(rcode, answer.mapNotNull { it.hostAddress }, (System.nanoTime() - t0) / 1_000_000))
                            }
                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(DnsAntwort(-1, emptyList(), (System.nanoTime() - t0) / 1_000_000))
                            }
                        })
                }
            }
            return a ?: DnsAntwort(-1, emptyList(), 5000)
        }
        return withContext(Dispatchers.IO) {
            try {
                val ips = InetAddress.getAllByName(name).mapNotNull { it.hostAddress }
                DnsAntwort(0, ips, (System.nanoTime() - t0) / 1_000_000)
            } catch (e: java.net.UnknownHostException) {
                DnsAntwort(3, emptyList(), (System.nanoTime() - t0) / 1_000_000)
            }
        }
    }

    suspend fun pruefen(context: Context): LiveCheck = coroutineScope {
        val sem = Semaphore(6)
        val werbung = probe.map { async { sem.withPermit { system(context, it) } } }.awaitAll()
        val kontrolle = BlockListe.KONTROLLE.map { async { sem.withPermit { system(context, it) } } }.awaitAll()
        val ok = kontrolle.filter { !it.geblockt && !it.fehler }
        LiveCheck(
            geblockt = werbung.count { it.geblockt },
            getestet = werbung.count { !it.fehler || it.rcode == 3 },
            kontrolleOk = ok.size,
            kontrolleGesamt = kontrolle.size,
            latenzMs = if (ok.isEmpty()) 0 else ok.map { it.ms }.sorted()[ok.size / 2]
        )
    }
}

// ======================================================================
// Network info
// ======================================================================

data class NetzInfo(val werte: List<Pair<String, String>>)

object NetzDaten {
    fun lesen(context: Context): NetzInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val netz = cm.activeNetwork ?: return NetzInfo(listOf("Netz" to "keine Verbindung"))
        val lp = cm.getLinkProperties(netz)
        val caps = cm.getNetworkCapabilities(netz)
        val art = when {
            caps == null -> "?"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilfunk"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Kabel"
            else -> "Sonstiges"
        }
        val w = mutableListOf<Pair<String, String>>()
        w += "Netz" to art
        w += "Interface" to (lp?.interfaceName ?: "?")
        if (lp != null) {
            w += "Privates DNS" to if (lp.isPrivateDnsActive) "aktiv (DoT :853)" else "inaktiv – Klartext :53"
            lp.privateDnsServerName?.let { w += "DoT-Server" to it }
            w += "DNS-Server" to lp.dnsServers.mapNotNull { it.hostAddress }.joinToString("\n").ifBlank { "–" }
            val v4 = lp.linkAddresses.map { it.address }.filter { it is java.net.Inet4Address }.mapNotNull { it.hostAddress }
            val v6 = lp.linkAddresses.map { it.address }.filter { it is java.net.Inet6Address && !it.isLinkLocalAddress }
            w += "IPv4" to v4.joinToString("\n").ifBlank { "–" }
            w += "IPv6" to if (v6.isEmpty()) "nein" else "ja (${v6.size} Adr.)"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && lp.mtu > 0) w += "MTU" to "${lp.mtu}"
        }
        if (caps != null) {
            w += "Bandbreite" to "↓ ${caps.linkDownstreamBandwidthKbps / 1000} / ↑ ${caps.linkUpstreamBandwidthKbps / 1000} Mbit/s (geschätzt)"
            w += "Getaktet" to if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "nein" else "ja"
        }
        w += "Einstellung" to DnsController.describeCurrent(context)
        return NetzInfo(w)
    }
}

fun uhrzeit(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(ms))
