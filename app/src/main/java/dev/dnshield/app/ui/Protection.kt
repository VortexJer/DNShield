package dev.dnshield.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Filter
import dev.dnshield.app.core.Packets
import dev.dnshield.app.core.UpstreamSpec
import dev.dnshield.app.engine.CustomServer
import dev.dnshield.app.engine.DnsServers
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.engine.FilterState
import dev.dnshield.app.engine.Format
import dev.dnshield.app.engine.HostResolver
import dev.dnshield.app.engine.ListStore
import dev.dnshield.app.engine.UpstreamSet
import dev.dnshield.app.engine.DohTransport
import dev.dnshield.app.engine.UserRule
import dev.dnshield.app.vpn.ShieldVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// ================================================================== PROTECCION (raiz)

@Composable
fun ProtectionScreen(nav: Nav, tick: Int, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val running by Engine.running.collectAsState()
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf(Engine.settings.listsEnabled) }
    var security by remember { mutableStateOf(SECURITY_KEYS.all { ListStore.isEnabled(ctx, it) }) }
    Page("Protección", icon = Ico.shieldCheck) {
        item {
            Hairline()
            ToggleNavRow("Protección DNS", "Mejora tu privacidad bloqueando el seguimiento en las apps y cifrando tus solicitudes de DNS",
                running, Ico.shield, onToggle = { onToggle() }) { nav.push(Route.DnsProtection) }
            ToggleNavRow("Bloqueo de anuncios", "Bloquea anuncios y rastreadores con filtros, reglas de usuario y lista de permitido",
                lists, Ico.filter, onToggle = { on ->
                    lists = on; Engine.settings.listsEnabled = on; Engine.reloadConfig()
                }) { nav.push(Route.DnsFiltering) }
            ToggleNavRow("Protección avanzada", "Bloquea malware y phishing, y da más control: IPv6, apps excluidas y pausa",
                security, Ico.sliders, onToggle = { on ->
                    security = on
                    scope.launch { withContext(Dispatchers.IO) { SECURITY_KEYS.forEach { ListStore.setEnabled(ctx, it, on) } } }
                }) { nav.push(Route.AdvancedProtection) }
            NavRow("AdGuard VPN", "Funciona aparte del bloqueador: Android solo permite una VPN a la vez", icon = Ico.vpn) {
                openAdguardVpn(ctx)
            }
        }
    }
}

@Composable
fun DnsProtectionScreen(nav: Nav, tick: Int, onToggle: () -> Unit, onBack: () -> Unit) {
    val running by Engine.running.collectAsState()
    val server = remember(tick) { Engine.currentServer().first }
    Page("Protección DNS", "Mejora tu privacidad bloqueando el seguimiento en las apps y cifrando tus solicitudes de DNS",
        onBack = onBack, icon = Ico.shield) {
        item {
            Hairline()
            ToggleRow("Habilitado", null, running, icon = Ico.shield) { onToggle() }
            InfoRow("Implementación DNS", "DNShield: \"pseudo\" VPN local. Admite DNS normal, DoH y DoT y permite ver la actividad", icon = Ico.shieldCheck)
            NavRow("Servidor DNS", server, icon = Ico.server) { nav.push(Route.DnsServer) }
            NavRow("Configuración de red", "Configuración de red avanzada", icon = Ico.wifi) { nav.push(Route.Network) }
            NavRow("Filtrado DNS", "Filtros y excepciones", icon = Ico.filter) { nav.push(Route.DnsFiltering) }
        }
    }
}

@Composable
fun DnsFilteringScreen(nav: Nav, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    val enabledFilters = remember(tick) { ListStore.states(ctx).count { it.value.enabled } }
    val rules = remember(tick) { Engine.blocklist.ruleCount }
    Page("Bloqueo de anuncios", "Bloquea anuncios y rastreadores con filtros y tus propias reglas", onBack = onBack, icon = Ico.filter) {
        item {
            Hairline()
            NavRow("Filtros", "$enabledFilters filtros habilitados · ${Format.count(rules.toLong())} reglas", icon = Ico.filter) { nav.push(Route.Filters) }
            NavRow("Reglas de usuario", "${s.userRules.count { it.enabled }} reglas habilitadas${if (!s.userRulesEnabled) " · deshabilitadas" else ""}", icon = Ico.block) {
                nav.push(Route.UserRules)
            }
            NavRow("Lista de permitido", "DNShield no bloqueará los dominios de esta lista${if (!s.allowlistEnabled) " · deshabilitada" else ""}", icon = Ico.hand) {
                nav.push(Route.Allowlist)
            }
        }
    }
}

private val SECURITY_KEYS = setOf("11", "30")

@Composable
fun AdvancedProtectionScreen(nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = Engine.settings
    var security by remember { mutableStateOf(SECURITY_KEYS.all { ListStore.isEnabled(ctx, it) }) }
    var busy by remember { mutableStateOf(false) }
    var ipv6 by remember { mutableStateOf(s.blockIpv6) }
    var paused by remember { mutableStateOf(Engine.isPaused) }
    Page("Protección avanzada", "Funciones extra para proteger más el móvil", onBack = onBack, icon = Ico.sliders) {
        item {
            Hairline()
            ToggleRow("Filtros de seguridad", if (busy) "Descargando…" else "Bloquea malware (URLHaus) y phishing (PhishTank y OpenPhish)",
                security, enabled = !busy, icon = Ico.shieldCheck) { on ->
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) { SECURITY_KEYS.forEach { ListStore.setEnabled(ctx, it, on) } }
                    security = on; busy = false
                }
            }
            ToggleRow("Bloquear IPv6", "Responde vacío a las peticiones AAAA. Útil si tu red da problemas con IPv6", ipv6, icon = Ico.globe) {
                ipv6 = it; s.blockIpv6 = it; Engine.reloadConfig()
            }
            NavRow("Apps excluidas", "${s.excludedApps.size} apps no pasan por el filtro", icon = Ico.apps) { nav.push(Route.Apps) }
            ToggleRow("Pausar 5 minutos", "Deja pasar todo sin filtrar un rato", paused, enabled = Engine.running.value, icon = Ico.pause) {
                if (it) Engine.pause(5) else Engine.resume()
                paused = Engine.isPaused
            }
        }
    }
}

private fun openAdguardVpn(ctx: Context) {
    val pkg = "com.adguard.vpn"
    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
    val intents = listOfNotNull(
        launch,
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
    )
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

// ================================================================== SERVIDOR DNS

@Composable
fun DnsServerScreen(onBack: () -> Unit) {
    val s = Engine.settings
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(s.serverId) }
    var version by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val customs = remember(version) { s.customServers }

    fun select(id: String) {
        current = id
        s.serverId = id
        Engine.rebuildUpstream()
        testResult = null
    }

    Page(
        "Servidor DNS",
        "Puedes mejorar tu privacidad cifrando el tráfico DNS. Elige uno de los proveedores públicos o añade el tuyo.",
        onBack = onBack,
        action = {
            HeaderAction("Probar") {
                testResult = "Probando…"
                scope.launch { testResult = withContext(Dispatchers.IO) { testCurrent() } }
            }
        },
    ) {
        testResult?.let { r -> item { Note("Prueba: $r") } }
        section("01", "Proveedores")
        items(DnsServers.PRESETS, key = { it.id }) { p ->
            val selected = current == p.id
            RadioRow(p.name, p.description, selected) { select(p.id) }
            if (selected && p.protocols.size > 1) {
                var proto by remember(p.id, version) { mutableStateOf(s.protocolFor(p.id)) }
                Column(Modifier.padding(start = 46.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                    val labels = p.protocols.map { when (it) { "doh" -> "DoH"; "dot" -> "DoT"; else -> "Normal" } }
                    Segmented(labels, p.protocols.indexOf(proto).coerceAtLeast(0)) { i ->
                        proto = p.protocols[i]
                        s.setProtocol(p.id, proto)
                        Engine.rebuildUpstream()
                        version++
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.address(proto).joinToString("  "), style = Tx.mono(pal.faint, 10), maxLines = 2)
                }
                Hairline(Modifier.padding(start = 20.dp))
            }
        }
        section("02", "Servidores del usuario")
        items(customs, key = { it.id }) { c ->
            RadioRow(c.name, c.address, current == c.id, trailing = {
                HeaderAction("Quitar") {
                    s.customServers = s.customServers.filterNot { it.id == c.id }
                    if (current == c.id) select(DnsServers.DEFAULT_ID)
                    version++
                }
            }) { select(c.id) }
        }
        item { NavRow("Añadir servidor DNS del usuario", "1.2.3.4 · https://…/dns-query · tls://…") { adding = true } }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var addr by remember { mutableStateOf("") }
        val spec = UpstreamSpec.parse(addr)
        Sheet("Añadir servidor DNS", { adding = false }, buttons = {
            Btn("Cancelar", Modifier.weight(1f)) { adding = false }
            Btn("Añadir", Modifier.weight(1f), enabled = spec != null, primary = true) {
                val id = "custom:" + Integer.toHexString(addr.trim().hashCode())
                s.customServers = s.customServers.filterNot { it.id == id } + CustomServer(id, name.ifBlank { spec!!.display }, addr.trim())
                version++
                select(id)
                adding = false
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "Nombre (opcional)")
                Field(addr, { addr = it }, "Dirección")
                Text(
                    if (addr.isBlank()) "Admite DNS normal (1.1.1.1), DoH (https://…) y DoT (tls://…)."
                    else if (spec == null) "No es una dirección válida." else "Protocolo: ${spec.protocol}",
                    style = Tx.small(),
                )
            }
        }
    }
}

/** Resuelve example.com con el servidor elegido y mide el tiempo, sin pasar por el tunel. */
private fun testCurrent(): String {
    val (name, addrs) = Engine.currentServer()
    val spec = addrs.firstNotNullOfOrNull { UpstreamSpec.parse(it) } ?: return "sin dirección para $name"
    val resolver = HostResolver(Engine.settings.bootstrapServers.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() })
    val t = UpstreamSet.transportFor(spec, resolver) { DohTransport.client(resolver) }
    return try {
        val t0 = System.nanoTime()
        val resp = t.exchange(DnsMessage.query("example.com", Packets.TYPE_A, Random.nextInt(65536)))
        val ms = (System.nanoTime() - t0) / 1_000_000
        "${spec.protocol} correcto en $ms ms · ${DnsMessage.summarize(resp)}"
    } catch (e: Exception) {
        "falló (${e.message ?: e.javaClass.simpleName})"
    } finally {
        t.close()
    }
}

// ================================================================== FILTROS

@Composable
fun FiltersScreen(nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val rules = remember(version) { Engine.blocklist.ruleCount }
    val last = remember(version) { Engine.settings.lastUpdate }

    Page(
        "Filtros DNS", "${Format.count(rules.toLong())} reglas habilitadas",
        onBack = onBack,
        action = {
            HeaderAction(if (busy != null) "…" else "Actualizar", enabled = busy == null) {
                busy = "Conectando"
                scope.launch {
                    result = withContext(Dispatchers.IO) { ListStore.update(ctx) { busy = it } }
                    busy = null; version++
                }
            }
        },
    ) {
        item {
            Note(busy ?: result ?: "Actualizados ${Format.ago(last)}. Se actualizan solos según Ajustes.")
            Hairline()
        }
        section("01", "Categorías")
        items(ListStore.GROUPS.entries.toList(), key = { it.key }) { (gid, name) ->
            val (on, total) = remember(version) { ListStore.groupSummary(ctx, gid) }
            NavRow(name, if (total == 0) "Ninguno" else "Filtros habilitados: $on/$total") { nav.push(Route.FilterGroup(gid)) }
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES"))

@Composable
fun FilterGroupScreen(groupId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val filters = remember(version) { ListStore.all(ctx).filter { it.groupId == groupId } }
    val states = remember(version) { ListStore.states(ctx) }
    val shown = filters.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }

    fun toggle(key: String, on: Boolean) {
        working = key
        scope.launch {
            message = withContext(Dispatchers.IO) { ListStore.setEnabled(ctx, key, on) }
            working = null; version++
        }
    }

    Page(ListStore.GROUPS[groupId] ?: "Filtros", ListStore.GROUP_HINTS[groupId], onBack = onBack) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { Field(query, { query = it }, "Buscar filtro") }
            message?.let { Note(it) }
            Hairline()
        }
        if (groupId == ListStore.CUSTOM_GROUP) item { NavRow("Añadir filtro", "Cualquier lista de dominios por URL (formato AdGuard, hosts o dominios)") { adding = true } }
        items(shown, key = { it.key }) { f ->
            val st = states[f.key] ?: FilterState(false)
            val busyHere = working == f.key
            Row(
                Modifier.fillMaxWidth().clickable(enabled = working == null) { toggle(f.key, !st.enabled) }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = Tx.body(size = 14))
                    if (f.description.isNotBlank() && f.groupId != ListStore.CUSTOM_GROUP) {
                        Text(f.description, style = Tx.small(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            if (f.catalogVersion.isNotBlank()) append("Versión ${f.catalogVersion}  ")
                            if (st.enabled && st.rules > 0) append("· ${Format.count(st.rules.toLong())} reglas  ")
                            if (st.updated > 0) append("· ${dateFmt.format(Date(st.updated))}")
                            if (busyHere) append(" · descargando…")
                        }.trim().trimStart('·').trim(),
                        style = Tx.mono(pal.faint, 10),
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (f.groupId == ListStore.CUSTOM_GROUP) {
                    HeaderAction("Quitar", enabled = working == null) {
                        scope.launch { withContext(Dispatchers.IO) { ListStore.removeCustom(ctx, f.key) }; version++ }
                    }
                }
                Toggle(st.enabled, working == null)
            }
            Hairline(Modifier.padding(start = 20.dp))
        }
        if (shown.isEmpty() && groupId != ListStore.CUSTOM_GROUP) item { Note("Ningún filtro coincide.") }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        Sheet("Añadir filtro", { adding = false }, buttons = {
            Btn("Cancelar", Modifier.weight(1f)) { adding = false }
            Btn(if (saving) "Descargando" else "Añadir", Modifier.weight(1f), enabled = url.isNotBlank() && !saving, primary = true) {
                saving = true
                scope.launch {
                    err = withContext(Dispatchers.IO) { ListStore.addCustom(ctx, name.trim(), url.trim()) }
                    saving = false
                    if (err == null) { adding = false; version++ }
                }
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "Nombre (opcional)")
                Field(url, { url = it }, "https://…/lista.txt")
                err?.let { Text(it, style = Tx.small(pal.text)) }
            }
        }
    }
}

// ================================================================== REGLAS DEL USUARIO

@Composable
fun UserRulesScreen(onBack: () -> Unit) {
    val s = Engine.settings
    RuleListScreen(
        title = "Lista de bloqueo",
        subtitle = "Una regla por línea. Admite la sintaxis de AdGuard DNS: ||dominio^, @@||dominio^ (excepción), " +
            "0.0.0.0 dominio, o un dominio suelto (bloquea también sus subdominios).",
        placeholder = "||ads.ejemplo.com^",
        enabledGetter = { s.userRulesEnabled }, enabledSetter = { s.userRulesEnabled = it },
        rulesGetter = { s.userRules }, rulesSetter = { s.userRules = it },
        validate = { Filter.isValidUserRule(it) },
        onBack = onBack,
    )
}

@Composable
fun AllowlistScreen(onBack: () -> Unit) {
    val s = Engine.settings
    RuleListScreen(
        title = "Lista de permitido",
        subtitle = "Dominios que DNShield no filtrará nunca. Tiene prioridad sobre los filtros. Incluye subdominios.",
        placeholder = "ejemplo.com",
        enabledGetter = { s.allowlistEnabled }, enabledSetter = { s.allowlistEnabled = it },
        rulesGetter = { s.allowlist }, rulesSetter = { s.allowlist = it },
        validate = { dev.dnshield.app.core.BlocklistBuilder.normalize(it) != null },
        normalize = { dev.dnshield.app.core.BlocklistBuilder.normalize(it.removePrefix("https://").removePrefix("http://").substringBefore('/')) ?: it },
        onBack = onBack,
    )
}

@Composable
private fun RuleListScreen(
    title: String,
    subtitle: String,
    placeholder: String,
    enabledGetter: () -> Boolean,
    enabledSetter: (Boolean) -> Unit,
    rulesGetter: () -> List<UserRule>,
    rulesSetter: (List<UserRule>) -> Unit,
    validate: (String) -> Boolean,
    normalize: (String) -> String = { it.trim() },
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(enabledGetter()) }
    var rules by remember { mutableStateOf(rulesGetter()) }
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var bulk by remember { mutableStateOf(false) }

    fun save(new: List<UserRule>) { rules = new; rulesSetter(new); Engine.reloadConfig() }

    val lines = input.lines().map { normalize(it.trim()) }.filter { it.isNotEmpty() }
    val valid = lines.isNotEmpty() && lines.all(validate)
    val shown = rules.filter { query.isBlank() || it.text.contains(query, true) }

    Page(title, subtitle, onBack = onBack, action = { HeaderAction(if (bulk) "Una" else "Varias") { bulk = !bulk } }) {
        item { ToggleRow("Habilitado", null, enabled) { enabled = it; enabledSetter(it); Engine.reloadConfig() } }
        section("01", "Añadir nueva regla")
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Field(input, { input = it }, placeholder, singleLine = !bulk, minHeight = if (bulk) 110.dp else 0.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        lines.isEmpty() -> if (bulk) "Pega varias reglas, una por línea." else "Escribe una regla."
                        valid -> "${lines.size} regla${if (lines.size == 1) "" else "s"} válida${if (lines.size == 1) "" else "s"}"
                        else -> "Hay reglas no válidas: " + lines.filterNot(validate).take(2).joinToString()
                    },
                    style = Tx.small(),
                )
                Spacer(Modifier.height(8.dp))
                Btn("Añadir", enabled = valid, primary = true) {
                    val existing = rules.map { it.text }.toSet()
                    save(rules + lines.distinct().filter { it !in existing }.map { UserRule(it) })
                    input = ""
                }
            }
        }
        section("02", "Reglas · ${rules.size}")
        if (rules.size > 8) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { Field(query, { query = it }, "Buscar") }
        }
        if (rules.isEmpty()) item { Note("Ninguna todavía.") }
        items(shown, key = { "r" + it.text }) { r ->
            CheckRow(r.text, checked = r.enabled, onToggle = {
                save(rules.map { if (it.text == r.text) it.copy(enabled = !it.enabled) else it })
            }, trailing = {
                HeaderAction("Quitar") { save(rules.filterNot { it.text == r.text }) }
            })
        }
    }
}

// ================================================================== RED Y APPS

@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    var mobile by remember { mutableStateOf(s.filterMobile) }
    var wifi by remember { mutableStateOf(s.filterWifi) }
    // La red se reevalua al rehacer el tunel: asi un cambio Wi-Fi/datos se aplica al momento.
    val apply = { ShieldVpnService.restartIfRunning(ctx) }
    Page("Configuración de red", "Elige en qué redes filtra DNShield. En las demás, el DNS pasa sin filtrar (y se sigue contando).", onBack = onBack) {
        section("01", "Redes")
        item {
            ToggleRow("Filtrar datos móviles", "Protección DNS mientras estás en datos móviles", mobile) { mobile = it; s.filterMobile = it; apply() }
            ToggleRow("Filtrar Wi-Fi", "Protección DNS mientras estás en Wi-Fi", wifi) { wifi = it; s.filterWifi = it; apply() }
        }
    }
}

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var excluded by remember { mutableStateOf(s.excludedApps) }
    var query by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }
    val back = {
        if (dirty) ShieldVpnService.restartIfRunning(ctx)
        onBack()
    }
    Page("Apps excluidas", "Las apps marcadas no pasan por el filtro. Úsalo si alguna app deja de funcionar.", onBack = back) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { Field(query, { query = it }, "Buscar app") }
            Hairline()
        }
        val list = apps
        if (list == null) item { Note("Cargando apps…") }
        else items(list.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }, key = { it.first }) { (pkg, label) ->
            CheckRow(label, pkg, checked = pkg in excluded, onToggle = {
                excluded = if (pkg in excluded) excluded - pkg else excluded + pkg
                s.excludedApps = excluded
                dirty = true
            })
        }
    }
}
