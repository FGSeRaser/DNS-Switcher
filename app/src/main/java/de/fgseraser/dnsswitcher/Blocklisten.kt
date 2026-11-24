package de.fgseraser.dnsswitcher

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Eine HaGeZi-Blockliste (https://github.com/hagezi/dns-blocklists). */
data class Blockliste(
    val id: String,
    val name: String,
    /** Dateiname im HaGeZi-Repo ohne "-onlydomains.txt". */
    val datei: String,
    /** Ungefähre Zahl der Einträge (ändert sich mit jedem Build der Liste). */
    val eintraege: Int,
    val hinweis: String = ""
) {
    val url: String
        get() = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/$datei-onlydomains.txt"
}

/** Auswahl, Download und Ablage der Blocklisten (Dateien in filesDir/blocklisten). */
object Blocklisten {

    val alle = listOf(
        Blockliste("light", "Light – Grundschutz", "light", 93_945, "kaum Fehlblockierungen"),
        Blockliste("normal", "Normal – Allround", "multi", 233_475),
        Blockliste("pro.mini", "Pro mini – fürs Handy empfohlen", "pro.mini", 105_100, "klein und schnell"),
        Blockliste("pro", "Pro – erweitert", "pro", 281_640, "HaGeZi-Empfehlung"),
        Blockliste("proplus.mini", "Pro++ mini", "pro.plus.mini", 109_543),
        Blockliste("proplus", "Pro++ – aggressiv", "pro.plus", 293_324, "kann Seiten/Apps stören"),
        Blockliste("ultimate.mini", "Ultimate mini", "ultimate.mini", 117_581),
        Blockliste("ultimate", "Ultimate – maximal", "ultimate", 325_094, "kann Seiten/Apps stören"),
        Blockliste("tif.mini", "Bedrohungen (TIF) mini", "tif.mini", 199_169, "Malware, Phishing"),
        Blockliste("tif.medium", "Bedrohungen (TIF) mittel", "tif.medium", 390_306, "Malware, Phishing"),
        Blockliste("popup", "Pop-up-Werbung", "popupads", 73_234),
        Blockliste("fake", "Fake-Shops und Abofallen", "fake", 10_774),
        Blockliste("gambling.mini", "Glücksspiel mini", "gambling.mini", 57_828),
        Blockliste("social", "Soziale Netzwerke", "social", 8_336, "Facebook, TikTok, X …"),
        Blockliste("nsfw", "Erwachsenen-Inhalte", "nsfw", 87_389),
        Blockliste("piracy", "Anti-Piracy", "anti.piracy", 64_647)
    )

    fun datei(context: Context, id: String): File {
        val ordner = File(context.filesDir, "blocklisten")
        ordner.mkdirs()
        return File(ordner, "$id.txt")
    }

    fun vorhanden(context: Context, id: String): Boolean {
        val f = datei(context, id)
        return f.exists() && f.length() > 0
    }

    /** Lädt die Liste herunter (blockierend – auf einem Hintergrund-Thread aufrufen). */
    fun herunterladen(context: Context, liste: Blockliste): Boolean {
        val ziel = datei(context, liste.id)
        val tmp = File(ziel.path + ".tmp")
        return try {
            val verbindung = URL(liste.url).openConnection() as HttpURLConnection
            verbindung.connectTimeout = 15_000
            verbindung.readTimeout = 60_000
            verbindung.inputStream.use { eingang ->
                tmp.outputStream().use { ausgang -> eingang.copyTo(ausgang) }
            }
            if (tmp.length() < 100) {
                tmp.delete()
                false
            } else {
                ziel.delete()
                tmp.renameTo(ziel)
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
