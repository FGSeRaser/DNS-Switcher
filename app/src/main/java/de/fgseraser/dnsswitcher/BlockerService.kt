package de.fgseraser.dnsswitcher

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Komplett-Blocker: lokaler DNS-Filter über einen VPN-Tunnel.
 * Nur die Adresse des (virtuellen) DNS-Servers läuft durch den Tunnel, der übrige Verkehr bleibt normal.
 * Anfragen für Domains aus den gewählten HaGeZi-Listen (inkl. Subdomains) werden mit NXDOMAIN beantwortet,
 * alle anderen an einen normalen DNS-Server weitergereicht.
 */
class BlockerService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var leser: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var domains: Set<String> = emptySet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Android hält den VPN-Dienst gebunden, stopService() allein beendet ihn nicht.
            // Darum wird der Tunnel hier selbst geschlossen.
            beenden()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) starten()
        return START_NOT_STICKY
    }

    private fun starten() {
        val fd = try {
            Builder()
                .setSession("DNS Switcher Blocker")
                .addAddress(CLIENT_IP, 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .setMtu(1500)
                .setBlocking(false) // sonst hält ein wartendes read() den Tunnel beim Beenden offen
                .establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            Store.log(this, "Blocker: VPN konnte nicht gestartet werden")
            stopSelf()
            return
        }
        tun = fd
        stopping = false
        running = true
        pool = Executors.newFixedThreadPool(8)

        // Privates DNS aus, sonst würde Android am VPN-DNS vorbeigehen
        try {
            Store.profile(this, Store.PROFILE_OFF)?.let { DnsController.apply(this, it) }
        } catch (e: Exception) {
            Store.log(this, "Blocker: Privates DNS konnte nicht ausgeschaltet werden (Berechtigung fehlt?)")
        }
        Store.log(this, "Komplett-Blocker gestartet")

        Thread { listenLaden() }.start()
        leser = Thread { lesen(fd) }.also { it.start() }
    }

    private fun listenLaden() {
        try {
            val neu = HashSet<String>()
            for (id in Store.blockerListen(this)) {
                val datei = Blocklisten.datei(this, id)
                if (!datei.exists()) continue
                datei.bufferedReader().useLines { zeilen ->
                    zeilen.forEach { zeile ->
                        val t = zeile.trim()
                        if (t.isNotEmpty() && t[0] != '#') neu.add(t.removePrefix("*.").lowercase(Locale.ROOT))
                    }
                }
            }
            domains = neu
            Store.log(this, "Blocker: ${neu.size} Domains geladen")
        } catch (e: OutOfMemoryError) {
            Store.log(this, "Blocker: zu wenig Speicher – bitte kleinere Liste wählen")
            stopSelf()
        } catch (e: Exception) {
            Store.log(this, "Blocker: Listen konnten nicht gelesen werden")
        }
    }

    private fun lesen(fd: ParcelFileDescriptor) {
        val eingang = FileInputStream(fd.fileDescriptor)
        val ausgang = FileOutputStream(fd.fileDescriptor)
        val puffer = ByteArray(32767)
        try {
            while (!stopping) {
                val n = eingang.read(puffer)
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(15) // nichts da, kurz warten
                    continue
                }
                if (n < 28) continue
                if (((puffer[0].toInt() shr 4) and 0xF) != 4) continue
                val ihl = (puffer[0].toInt() and 0xF) * 4
                if (puffer[9].toInt() != 17 || n < ihl + 8) continue
                val zielPort = ((puffer[ihl + 2].toInt() and 0xFF) shl 8) or (puffer[ihl + 3].toInt() and 0xFF)
                if (zielPort != 53) continue
                val paket = puffer.copyOf(n)
                pool?.execute { beantworten(paket, ihl, ausgang) }
            }
        } catch (e: Exception) {
            // Tunnel wurde geschlossen oder Thread beendet
        }
    }

    private fun beantworten(paket: ByteArray, ihl: Int, ausgang: FileOutputStream) {
        try {
            val anfrage = paket.copyOfRange(ihl + 8, paket.size)
            if (anfrage.size < 12) return
            val frage = frageLesen(anfrage)
            val antwort: ByteArray? =
                if (frage != null && istGeblockt(frage.name)) blockAntwort(anfrage, frage.ende)
                else weiterleiten(anfrage)
            if (antwort == null) return
            val ip = paketBauen(paket, ihl, antwort)
            synchronized(ausgang) { ausgang.write(ip) }
        } catch (e: Exception) {
            // Anfrage verwerfen, die App fragt erneut
        }
    }

    private class Frage(val name: String, val ende: Int)

    /** Liest den Namen aus dem Fragenteil einer DNS-Anfrage. */
    private fun frageLesen(q: ByteArray): Frage? {
        var i = 12
        val sb = StringBuilder()
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) {
                val ende = i + 1 + 4 // Null-Byte + Typ + Klasse
                return if (sb.isEmpty() || ende > q.size) null else Frage(sb.toString(), ende)
            }
            if ((len and 0xC0) != 0 || i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) sb.append((q[i + k].toInt() and 0xFF).toChar().lowercaseChar())
            i += len + 1
        }
        return null
    }

    private fun istGeblockt(name: String): Boolean {
        val menge = domains
        if (menge.isEmpty()) return false
        var d = name
        while (true) {
            if (d in menge) return true
            val punkt = d.indexOf('.')
            if (punkt < 0) return false
            d = d.substring(punkt + 1)
        }
    }

    /** Antwort "gibt es nicht" (NXDOMAIN), nur mit dem Fragenteil. */
    private fun blockAntwort(q: ByteArray, ende: Int): ByteArray {
        val r = q.copyOf(ende)
        r[2] = (r[2].toInt() or 0x80).toByte() // Antwort-Flag
        r[3] = ((r[3].toInt() and 0x70) or 0x80 or 0x03).toByte() // RA + NXDOMAIN
        for (k in 6..11) r[k] = 0 // keine Antwort-/Zusatzeinträge
        return r
    }

    /** Gibt die Anfrage an einen normalen DNS-Server weiter. */
    private fun weiterleiten(q: ByteArray): ByteArray? {
        for (server in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s) // am VPN vorbei
                    s.soTimeout = 3000
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
                    val puffer = ByteArray(4096)
                    val antwort = DatagramPacket(puffer, puffer.size)
                    s.receive(antwort)
                    return puffer.copyOf(antwort.length)
                }
            } catch (e: Exception) {
                // nächster Server
            }
        }
        return null
    }

    /** Baut aus der DNS-Antwort ein IPv4/UDP-Paket zurück an die App. */
    private fun paketBauen(anfrage: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val gesamt = 20 + udpLen
        val out = ByteArray(gesamt)
        out[0] = 0x45
        out[2] = (gesamt shr 8).toByte()
        out[3] = gesamt.toByte()
        out[6] = 0x40 // Don't Fragment
        out[8] = 64
        out[9] = 17
        System.arraycopy(anfrage, 16, out, 12, 4) // Absender = bisheriges Ziel
        System.arraycopy(anfrage, 12, out, 16, 4) // Ziel = bisheriger Absender
        var summe = 0
        for (i in 0 until 20 step 2) summe += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
        while ((summe shr 16) != 0) summe = (summe and 0xFFFF) + (summe shr 16)
        val pruef = summe.inv() and 0xFFFF
        out[10] = (pruef shr 8).toByte()
        out[11] = pruef.toByte()
        out[20] = 0
        out[21] = 53
        out[22] = anfrage[ihl]
        out[23] = anfrage[ihl + 1]
        out[24] = (udpLen shr 8).toByte()
        out[25] = udpLen.toByte()
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun beenden() {
        if (tun == null && !running) return
        stopping = true
        running = false
        // Zuerst den Tunnel schließen – erst dadurch trennt Android die VPN-Verbindung
        try {
            tun?.close()
        } catch (e: Exception) {
            Store.log(this, "Blocker: Tunnel schließen fehlgeschlagen: ${e.message}")
        }
        tun = null
        pool?.shutdownNow()
        pool = null
        leser?.interrupt()
        domains = emptySet()
        Store.log(this, "Komplett-Blocker beendet")
        // Spiele-DNS-Automatik läuft wieder
        SwitchService.restoreDefault(this, "Blocker beendet")
        SwitchService.instance?.reapply()
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        beenden()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "de.fgseraser.dnsswitcher.BLOCKER_STOP"
        private const val CLIENT_IP = "10.77.0.1"
        private const val DNS_IP = "10.77.0.2"

        /** Normale (ungefilterte) Server, an die erlaubte Anfragen gehen. */
        private val UPSTREAMS = listOf("94.140.14.140", "1.1.1.1")

        @Volatile
        var running = false
            private set

        fun starten(context: Context) {
            context.startService(Intent(context, BlockerService::class.java))
        }

        fun stoppen(context: Context) {
            context.startService(Intent(context, BlockerService::class.java).setAction(ACTION_STOP))
        }
    }
}
