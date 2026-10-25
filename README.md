# DNS Switcher

Android-App, die das **private DNS automatisch umstellt**, je nachdem, welche App oder welches Spiel gerade offen ist.
Normalerweise ist **AdGuard** aktiv. Für ausgewählte Spiele schaltet die App auf **Normal** (Automatisch) und beim Verlassen wieder zurück.

Entwickelt von **FGSeRaser** – https://github.com/FGSeRaser

## Bauen

1. Ordner in **Android Studio** öffnen (Ladybug oder neuer). Gradle und alle Abhängigkeiten werden automatisch geladen.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, oder das Handy anschließen und ▶ drücken.
3. Mindestens Android 9, denn privates DNS gibt es erst ab Android 9.

## Einrichten (einmalig)

1. **Berechtigung per ADB erteilen.** Android lässt Apps das private DNS nur mit dieser Systemberechtigung ändern:
   ```
   adb shell pm grant de.fgseraser.dnsswitcher android.permission.WRITE_SECURE_SETTINGS
   ```
   Das geht auch über die ADB ToolBox → Konsole. Auf gerooteten Handys reicht der Knopf „Mit Root freischalten“ in der App. Die Berechtigung bleibt bestehen, auch nach einem Neustart oder App-Update.
2. **Bedienungshilfe einschalten:** Einstellungen → Bedienungshilfen → Installierte Apps → **DNS Switcher**.
   Ist der Schalter ausgegraut („Eingeschränkte Einstellung“), weil die APK nicht aus dem Play Store kommt: App-Info → ⋮ → **„Eingeschränkte Einstellungen zulassen“**. Wer die App per `adb install` installiert, hat dieses Problem nicht.
3. In der App ein Spiel antippen und **Normal** wählen. Fertig.

## Funktionen

- Liste aller Apps mit Suche und Filter (Spiele / Mit Regel / Alle). Spiele werden automatisch erkannt und markiert.
- Für jede App wählbar: Standard, AdGuard, Normal, Aus oder ein eigenes Profil.
- Der Standard für alle anderen Apps ist frei wählbar und steht ab Werk auf AdGuard.
- Eigene DNS-Profile mit Vorlagen: AdGuard Familie, AdGuard ungefiltert, Mullvad Werbeblocker, Cloudflare, Google, Quad9.
- Schnellkachel **„DNS-Automatik“** zum Pausieren. Pausiert bleibt der Standard aktiv.
- Kurze Einblendung beim Wechsel (abschaltbar) und ein Verlauf der letzten 200 Umschaltungen.
- Wenn die Bedienungshilfe ausgeschaltet wird, stellt die App wieder den Standard ein.

## Datenschutz

Die App liest keine Bildschirminhalte. Sie bekommt von Android nur den Paketnamen der App, die gerade in den Vordergrund kommt. Sie hat keine Internet-Berechtigung, also verlassen keine Daten das Handy.

## Tipps

- Hatte ein Spiel schon beim Start AdGuard aktiv (z. B. weil die Bedienungshilfe aus war), das Spiel einmal ganz schließen und neu öffnen.
- Samsung: Einstellungen → Apps → DNS Switcher → Akku → „Nicht eingeschränkt“, damit One UI die Bedienungshilfe nicht beendet.
