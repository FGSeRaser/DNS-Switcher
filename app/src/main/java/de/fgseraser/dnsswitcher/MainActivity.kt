@file:OptIn(ExperimentalMaterial3Api::class)

package de.fgseraser.dnsswitcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import de.fgseraser.dnsswitcher.ui.DnsSwitcherTheme
import de.fgseraser.dnsswitcher.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** One launchable app for the rule list. */
data class AppEntry(val pkg: String, val label: String, val isGame: Boolean, val icon: ImageBitmap?)

class MainActivity : ComponentActivity() {

    /** Increased in onResume so the status is read again (e.g. after granting the permission). */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DnsSwitcherTheme {
                MainScreen(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

// ======================================================================
// Layout
// ======================================================================

private enum class Tab(val title: String, val icon: ImageVector) {
    Apps("Apps", Icons.Filled.Apps),
    Profiles("DNS-Profile", Icons.Filled.Dns),
    History("Verlauf", Icons.Filled.History),
    About("Info", Icons.Filled.Info)
}

@Composable
private fun MainScreen(tick: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.Apps) }
    // Every change in the app increases this, so all screens read the store again
    var version by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { version++ }
    val stamp = tick * 100000 + version

    Scaffold(
        containerColor = Palette.Back,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Apps -> AppsScreen(stamp, refresh)
                Tab.Profiles -> ProfilesScreen(stamp, refresh)
                Tab.History -> HistoryScreen(stamp, refresh)
                Tab.About -> AboutScreen()
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Text(subtitle, fontSize = 13.sp, color = Palette.Muted)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String, action: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) Palette.Good else Palette.Danger,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        action()
    }
}

private fun Context.notify(text: String) =
    android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

// ======================================================================
// Apps
// ======================================================================

private enum class AppFilter(val title: String) { Games("Spiele"), Rules("Mit Regel"), All("Alle Apps") }

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { info ->
            val app = info.activityInfo.applicationInfo
            @Suppress("DEPRECATION")
            val game = app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val icon = try {
                info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppEntry(info.activityInfo.packageName, info.loadLabel(pm).toString(), game, icon)
        }
        .sortedBy { it.label.lowercase(Locale.GERMANY) }
}

@Composable
private fun AppsScreen(stamp: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.Games) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    val rules = remember(stamp) { Store.rules(context) }
    val profiles = remember(stamp) { Store.profiles(context) }
    val defaultProfile = remember(stamp) { Store.defaultProfile(context) }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Header("DNS Switcher", "Automatisch das richtige DNS für jedes Spiel") }
        item { StatusCard(stamp, refresh) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("App oder Spiel suchen …") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                }
            }
        }
        val list = apps
        if (list == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            val shown = list.filter { app ->
                val matchesFilter = when (filter) {
                    AppFilter.Games -> app.isGame || rules.containsKey(app.pkg)
                    AppFilter.Rules -> rules.containsKey(app.pkg)
                    AppFilter.All -> true
                }
                matchesFilter && (query.isBlank() || app.label.contains(query, ignoreCase = true) || app.pkg.contains(query, ignoreCase = true))
            }.sortedWith(compareByDescending<AppEntry> { rules.containsKey(it.pkg) }.thenBy { it.label.lowercase(Locale.GERMANY) })
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (filter == AppFilter.Games) "Keine Spiele erkannt. Manche Spiele melden sich nicht als Spiel – schau unter 'Alle Apps'."
                        else "Keine Apps gefunden.",
                        color = Palette.Muted, modifier = Modifier.padding(24.dp)
                    )
                }
            }
            items(shown, key = { it.pkg }) { app ->
                val rule = rules[app.pkg]?.let { id -> profiles.firstOrNull { it.id == id } }
                AppRow(app, rule, defaultProfile) { editing = app }
            }
        }
    }

    editing?.let { app ->
        RuleDialog(
            app = app,
            current = rules[app.pkg],
            profiles = profiles,
            defaultProfile = defaultProfile,
            onDismiss = { editing = null },
            onChoose = { id ->
                Store.setRule(context, app.pkg, id)
                val chosen = id?.let { Store.profile(context, it) } ?: defaultProfile
                Store.log(context, "Regel: ${app.label} → ${chosen.name}")
                if (SwitchService.currentPackage == app.pkg) SwitchService.instance?.reapply()
                editing = null
                refresh()
            }
        )
    }
}

@Composable
private fun AppRow(app: AppEntry, rule: DnsProfile?, defaultProfile: DnsProfile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (app.isGame) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.SportsEsports, contentDescription = "Spiel", tint = Palette.Muted, modifier = Modifier.size(16.dp))
                }
            }
            Text(app.pkg, color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        ProfileChip(rule ?: defaultProfile, isRule = rule != null)
    }
}

@Composable
private fun ProfileChip(profile: DnsProfile, isRule: Boolean) {
    val color = when (profile.mode) {
        DnsController.MODE_HOSTNAME -> Palette.Accent
        DnsController.MODE_OFF -> Palette.Danger
        else -> Palette.Warning
    }
    val text = if (isRule) profile.name else "Standard"
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isRule) color.copy(alpha = 0.18f) else Palette.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (isRule) color else Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun RuleDialog(
    app: AppEntry,
    current: String?,
    profiles: List<DnsProfile>,
    defaultProfile: DnsProfile,
    onDismiss: () -> Unit,
    onChoose: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text(app.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Welches DNS soll aktiv sein, solange diese App geöffnet ist?", color = Palette.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(current == null, "Standard", "folgt dem Standard: ${defaultProfile.name}") { onChoose(null) }
                profiles.forEach { p ->
                    ChoiceRow(current == p.id, p.name, p.description) { onChoose(p.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, color = Palette.Text, fontSize = 15.sp)
            Text(subtitle, color = Palette.Muted, fontSize = 12.sp)
        }
    }
}

// ======================================================================
// Status (permission, accessibility service, current DNS)
// ======================================================================

@Composable
private fun StatusCard(stamp: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val permission = remember(stamp) { DnsController.hasPermission(context) }
    val service = remember(stamp) { DnsController.isServiceEnabled(context) }
    val current = remember(stamp) { DnsController.describeCurrent(context) }
    val enabled = remember(stamp) { Store.isEnabled(context) }

    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatik", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !permission || !service -> "Noch nicht eingerichtet"
                        enabled -> "Aktiv – DNS wechselt beim App-Wechsel"
                        else -> "Pausiert – Standard-DNS bleibt aktiv"
                    },
                    color = if (permission && service && enabled) Palette.Good else Palette.Muted, fontSize = 13.sp
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                PauseTileService.setAutomatic(context, it)
                refresh()
            })
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Palette.Border)
        StatusLine(permission, if (permission) "DNS-Berechtigung erteilt" else "DNS-Berechtigung fehlt (einmalig per ADB)") {
            if (!permission) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(DnsController.adbCommand(context)))
                    context.notify("ADB-Befehl kopiert")
                }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Befehl kopieren", tint = Palette.Accent) }
            }
        }
        if (!permission) {
            Text(
                DnsController.adbCommand(context),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Back).padding(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                val ok = DnsController.grantWithRoot(context)
                context.notify(if (ok) "Berechtigung mit Root erteilt" else "Root nicht verfügbar – bitte den ADB-Befehl verwenden")
                refresh()
            }) { Text("Mit Root freischalten") }
        }
        StatusLine(service, if (service) "Bedienungshilfe eingeschaltet" else "Bedienungshilfe ist aus") {
            if (!service) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Einschalten") }
            }
        }
        if (!service) {
            Text(
                "In den Bedienungshilfen 'Installierte Apps' → 'DNS Switcher' einschalten. Ist der Schalter ausgegraut: App-Info → ⋮ → 'Eingeschränkte Einstellungen zulassen'.",
                color = Palette.Muted, fontSize = 12.sp
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Palette.Border)
        Text("Aktuelles DNS", color = Palette.Muted, fontSize = 12.sp)
        Text(current, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ======================================================================
// Profiles
// ======================================================================

@Composable
private fun ProfilesScreen(stamp: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val profiles = remember(stamp) { Store.profiles(context) }
    val defaultId = remember(stamp) { Store.defaultProfileId(context) }
    val toast = remember(stamp) { Store.showToast(context) }
    val rules = remember(stamp) { Store.rules(context) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Header("DNS-Profile", "Standard für alle Apps und eigene DNS-Server") }
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Standard", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Gilt für alle Apps ohne eigene Regel – auch für Startbildschirm und Browser.", color = Palette.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                profiles.forEach { p ->
                    ChoiceRow(p.id == defaultId, p.name, p.description) {
                        Store.setDefaultProfileId(context, p.id)
                        Store.log(context, "Standard → ${p.name}")
                        SwitchService.instance?.reapply()
                        refresh()
                    }
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hinweis beim Wechsel", color = Palette.Text, fontSize = 15.sp)
                        Text("Kurze Einblendung 'DNS: …', wenn umgeschaltet wird", color = Palette.Muted, fontSize = 12.sp)
                    }
                    Switch(checked = toast, onCheckedChange = {
                        Store.setShowToast(context, it)
                        refresh()
                    })
                }
            }
        }
        item {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Alle Profile", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
        }
        items(profiles, key = { it.id }) { p ->
            val used = rules.values.count { it == p.id }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Palette.AccentSoft),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Dns, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Palette.Text, fontSize = 15.sp)
                    Text(p.description + if (used > 0) " · $used App(s)" else "", color = Palette.Muted, fontSize = 12.sp)
                }
                if (!p.builtIn) {
                    IconButton(onClick = {
                        Store.deleteProfile(context, p.id)
                        SwitchService.instance?.reapply()
                        refresh()
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = Palette.Muted) }
                }
            }
        }
    }

    if (adding) {
        AddProfileDialog(
            onDismiss = { adding = false },
            onAdd = { name, host ->
                val p = Store.addProfile(context, name, host)
                Store.log(context, "Profil angelegt: ${p.name} (${p.host})")
                adding = false
                refresh()
            }
        )
    }
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    val hostValid = host.trim().matches(Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$"))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("DNS-Profil hinzufügen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text("Hostname des DNS-Anbieters") },
                    placeholder = { Text("z. B. dns.adguard-dns.com") }, singleLine = true, isError = host.isNotBlank() && !hostValid,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Nur Hostnamen (DNS-over-TLS) – keine IP-Adressen. Das verlangt Android beim privaten DNS.", color = Palette.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Vorlagen", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Store.presets.forEach { (presetName, presetHost) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            name = presetName
                            host = presetHost
                        }.padding(vertical = 8.dp)
                    ) {
                        Text(presetName, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(presetHost, color = Palette.Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && hostValid, onClick = { onAdd(name, host) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

// ======================================================================
// History
// ======================================================================

@Composable
private fun HistoryScreen(stamp: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val entries = remember(stamp) { Store.history(context) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Header("Verlauf", "Die letzten 200 Umschaltungen") }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = {
                        Store.clearHistory(context)
                        refresh()
                    }, modifier = Modifier.padding(end = 8.dp)) { Text("Leeren") }
                }
            }
        }
        if (entries.isEmpty()) {
            item { Text("Noch keine Einträge. Öffne ein Spiel mit Regel – hier siehst du dann jeden Wechsel.", color = Palette.Muted, modifier = Modifier.padding(16.dp)) }
        }
        items(entries) { line ->
            Text(line, color = Palette.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.Surface)
        }
    }
}

// ======================================================================
// Info / credits
// ======================================================================

@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Header("Info", "So funktioniert der DNS Switcher")
        Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Step("1", "Berechtigung (einmalig)", "Handy per USB mit dem PC verbinden (USB-Debugging an) und ausführen:\n${DnsController.adbCommand(context)}\nGeht auch mit der ADB ToolBox → Konsole. Mit Root reicht der Knopf in der App.")
            Step("2", "Bedienungshilfe einschalten", "Einstellungen → Bedienungshilfen → Installierte Apps → DNS Switcher. Darüber erkennt die App, welche App gerade offen ist – Bildschirminhalte werden nicht gelesen.")
            Step("3", "Regeln festlegen", "Standard ist AdGuard. Tippe ein Spiel an und wähle 'Normal' – solange es offen ist, wird AdGuard ausgeschaltet. Beim Verlassen kommt automatisch wieder AdGuard.")
            Step("4", "Pausieren", "Über den Schalter oben oder die Schnellkachel 'DNS-Automatik' (Benachrichtigungsleiste bearbeiten → Kachel hinzufügen).")
        }
        Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Gut zu wissen", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "• Normal = privates DNS auf 'Automatisch' (DNS vom WLAN bzw. Mobilfunk).\n" +
                    "• Ein Spiel, das schon beim Start Werbung oder Login geblockt bekam, einmal komplett schließen und neu öffnen.\n" +
                    "• Manche Spiele laden Belohnungs-Werbung nur ohne Werbeblocker – genau dafür ist die Regel 'Normal' da.\n" +
                    "• Bei Samsung: Akku → 'Nicht optimiert' für DNS Switcher, damit die Bedienungshilfe nicht beendet wird.",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
                    Text("FG", color = Palette.Accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("DNS Switcher $version", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("von FGSeRaser", color = Palette.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FGSeRaser")))
                    } catch (e: Exception) {
                        context.notify("Kein Browser gefunden")
                    }
                },
                border = BorderStroke(1.dp, Palette.Accent),
                modifier = Modifier.fillMaxWidth()
            ) { Text("github.com/FGSeRaser", color = Palette.Accent) }
        }
    }
}

@Composable
private fun Step(number: String, title: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text, color = Palette.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
