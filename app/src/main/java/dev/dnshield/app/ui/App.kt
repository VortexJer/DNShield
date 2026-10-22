package dev.dnshield.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Filter
import dev.dnshield.app.engine.Companies
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.engine.Format
import dev.dnshield.app.engine.ListStore
import dev.dnshield.app.engine.LogEntry
import dev.dnshield.app.engine.Period
import dev.dnshield.app.engine.PeriodStats
import dev.dnshield.app.engine.ThemeMode
import dev.dnshield.app.engine.UserRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantallas que se apilan dentro de una pestana. */
sealed interface Route {
    data object DnsServer : Route
    data object DnsFiltering : Route
    data object DnsProtection : Route
    data object AdvancedProtection : Route
    data object Filters : Route
    data class FilterGroup(val groupId: Int) : Route
    data object UserRules : Route
    data object Allowlist : Route
    data object Network : Route
    data object Apps : Route
    data class Companies(val blockedOnly: Boolean) : Route
    data object General : Route
    data object Advanced : Route
    data object LowLevel : Route
    data object About : Route
}

class Nav {
    var tab by mutableIntStateOf(0)
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) = stack.add(r)
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun select(t: Int) { tab = t; stack.clear() }
}

private val TABS = listOf(Ico.home to "Inicio", Ico.shieldCheck to "Protección", Ico.pie to "Actividad", Ico.gear to "Ajustes")

@Composable
fun App(onToggle: () -> Unit) {
    val themeMode by ThemeBus.mode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val view = LocalView.current
    LaunchedEffect(dark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val nav = remember { Nav() }
    // Un pulso por segundo mientras la app esta en pantalla; cerrada no cuesta nada.
    val tick by produceState(0) { while (true) { delay(1000); value++ } }

    ProvidePal(makePal(dark)) {
        Column(Modifier.fillMaxSize().background(pal.bg).systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                val top = nav.stack.lastOrNull()
                if (top != null) {
                    RouteScreen(top, nav, tick, onToggle)
                } else when (nav.tab) {
                    0 -> HomeScreen(tick, onToggle)
                    1 -> ProtectionScreen(nav, tick, onToggle)
                    2 -> ActivityScreen(nav, tick)
                    else -> SettingsScreen(nav)
                }
                if (top == null && nav.tab != 0) BackHandler { nav.select(0) }
            }
            BottomBar(nav)
        }
    }
}

@Composable
private fun RouteScreen(r: Route, nav: Nav, tick: Int, onToggle: () -> Unit) {
    val back = { nav.pop() }
    when (r) {
        Route.DnsServer -> DnsServerScreen(back)
        Route.DnsFiltering -> DnsFilteringScreen(nav, tick, back)
        Route.DnsProtection -> DnsProtectionScreen(nav, tick, onToggle, back)
        Route.AdvancedProtection -> AdvancedProtectionScreen(nav, back)
        Route.Filters -> FiltersScreen(nav, back)
        is Route.FilterGroup -> FilterGroupScreen(r.groupId, back)
        Route.UserRules -> UserRulesScreen(back)
        Route.Allowlist -> AllowlistScreen(back)
        Route.Network -> NetworkScreen(back)
        Route.Apps -> AppsScreen(back)
        is Route.Companies -> CompaniesScreen(r.blockedOnly, tick, back)
        Route.General -> GeneralScreen(back)
        Route.Advanced -> AdvancedScreen(nav, back)
        Route.LowLevel -> LowLevelScreen(back)
        Route.About -> AboutScreen(back)
    }
}

@Composable
private fun BottomBar(nav: Nav) {
    Row(Modifier.fillMaxWidth().background(pal.surface).padding(top = 10.dp, bottom = 10.dp)) {
        TABS.forEachIndexed { i, (icon, label) ->
            val on = nav.tab == i
            val c = if (on) pal.accent else pal.faint
            Column(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null) { nav.select(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = c, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, style = Tx.label(c, 11), maxLines = 1)
            }
        }
    }
}

// ================================================================== INICIO

@Composable
private fun HomeScreen(tick: Int, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val running by Engine.running.collectAsState()
    var period by remember { mutableStateOf(periodOf(Engine.settings.statsPeriod)) }
    var picking by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    val stats = remember(tick, period) { Engine.stats.aggregate(period) }
    val paused = remember(tick) { Engine.isPaused }
    val server = remember(tick) { Engine.currentServer().first }
    var companies by remember { mutableStateOf(0) }
    LaunchedEffect(tick / 5, period) {
        // Cifras frescas aqui dentro: el "stats" de fuera es el de cuando se lanzo este efecto.
        companies = withContext(Dispatchers.Default) { Companies.rank(ctx, Engine.stats.aggregate(period).topQueried).size }
    }
    // Cifrado segun el protocolo real de la direccion (un servidor propio no lleva "HTTPS" en el nombre).
    val encrypted = remember(tick) { Engine.serverEncrypted() }
    val serverShort = remember(tick) { Engine.serverShortName() }

    val filters = remember(tick / 5) { ListStore.states(ctx).count { it.value.enabled } }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ico.brand, null, tint = pal.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("DNShield", style = Tx.title(19))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !updating) {
                    updating = true
                    scope.launch { withContext(Dispatchers.IO) { runCatching { ListStore.update(ctx) {} } }; updating = false }
                }, contentAlignment = Alignment.Center) {
                    Icon(Ico.refresh, "Actualizar filtros", tint = if (updating) pal.faint else pal.accent, modifier = Modifier.size(22.dp))
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (encrypted) "DNS cifrado" else "DNS sin cifrar", running && encrypted)
                    Chip("$filters filtros", running && Engine.blocklist.ruleCount > 0)
                    Chip(serverShort, false)
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    when { !running -> "Protección deshabilitada"; paused -> "Protección en pausa"; else -> "Protección habilitada" },
                    style = Tx.title(25), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (running) "${Format.count(stats.requests)} peticiones revisadas ${period.label.lowercase()}" else "Toca el interruptor para bloquear anuncios y rastreadores",
                    style = Tx.body(pal.muted, 15), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(40.dp))
                BigSwitch(running && !paused, onToggle)
                Spacer(Modifier.height(44.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { picking = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(period.label, style = Tx.body(pal.muted, 15))
                    Spacer(Modifier.width(6.dp))
                    Icon(Ico.chevronDown, null, tint = pal.muted, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.height(10.dp))
                LineChart(stats)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Figure(Format.count(stats.requests), "peticiones", pal.text, pal.muted, Modifier.weight(1f))
                    Figure(Format.count(stats.blocked), "bloqueados", pal.block, pal.block, Modifier.weight(1f))
                    Figure(companies.toString(), "empresas", pal.accent, pal.accent, Modifier.weight(1f))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (picking) PeriodPicker(period, { picking = false }) { period = it; Engine.settings.statsPeriod = it.name }
}

/** Pastilla de estado bajo la cabecera: verde con punto si esta activo, gris si es informativa. */
@Composable
private fun Chip(label: String, on: Boolean) {
    Row(
        Modifier.clip(CircleShape).background(if (on) pal.accent.copy(alpha = 0.12f) else pal.surface).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(pal.accent))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = Tx.label(if (on) pal.accent else pal.muted, 13), maxLines = 1)
    }
}

/** El interruptor grande de AdGuard: pildora verde y circulo blanco con su marca. */
@Composable
private fun BigSwitch(on: Boolean, onClick: () -> Unit) {
    val x by animateDpAsState(if (on) 54.dp else (-6).dp, tween(220), label = "bigknob")
    val track by animateColorAsState(if (on) pal.accent else pal.switchOff, tween(220), label = "bigtrack")
    Box(
        Modifier.width(118.dp).height(64.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 3.dp).fillMaxWidth().height(58.dp).clip(CircleShape).background(track))
        Box(
            Modifier.offset(x = x).size(70.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (on) androidx.compose.foundation.Image(Ico.brandCheck, "Protección activa", modifier = Modifier.size(32.dp))
            else Icon(Ico.shield, "Protección apagada", tint = pal.faint, modifier = Modifier.size(28.dp))
        }
    }
}

/** Grafica de lineas como la de AdGuard: peticiones en blanco, bloqueadas en rojo, rejilla vertical. */
@Composable
private fun LineChart(stats: PeriodStats) {
    val textC = pal.accent
    val blockC = pal.block
    val gridC = pal.line
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        for (k in 1..5) {
            val x = size.width * k / 6
            drawLine(gridC, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        val s = stats.series
        if (s.size < 2) return@Canvas
        val max = (s.maxOf { it.first }).coerceAtLeast(1L).toFloat()
        fun path(sel: (Pair<Long, Long>) -> Long): Path {
            val p = Path()
            val step = size.width / (s.size - 1)
            val pts = s.mapIndexed { i, v -> Offset(i * step, size.height - 4.dp.toPx() - (size.height - 12.dp.toPx()) * sel(v) / max) }
            p.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val mx = (a.x + b.x) / 2
                p.cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
            return p
        }
        val req = path { it.first }
        val area = Path().apply {
            addPath(req)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(textC.copy(alpha = 0.28f), textC.copy(alpha = 0f))))
        drawPath(path { it.second }, blockC, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(req, textC, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun Figure(value: String, label: String, color: Color, labelColor: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Tx.figure(color, 21), maxLines = 1)
        Text(label, style = Tx.body(labelColor, 13))
    }
}

@Composable
private fun PeriodPicker(current: Period, onDismiss: () -> Unit, onPick: (Period) -> Unit) {
    Sheet("Período", onDismiss) {
        Column {
            for (p in Period.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p); onDismiss() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Led(p == current, ring = true)
                    Spacer(Modifier.width(14.dp))
                    Text(p.label, style = Tx.body())
                }
            }
        }
    }
}

private fun periodOf(s: String) = runCatching { Period.valueOf(s) }.getOrDefault(Period.TODAY)
private fun ratio(s: PeriodStats) = if (s.requests == 0L) "0 %" else "%.0f %%".format(s.blocked * 100.0 / s.requests)

// ================================================================== ACTIVIDAD

@Composable
private fun ActivityScreen(nav: Nav, tick: Int) {
    val ctx = LocalContext.current
    var period by remember { mutableStateOf(periodOf(Engine.settings.statsPeriod)) }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    val stats = remember(tick / 2, period) { Engine.stats.aggregate(period) }
    var companies by remember { mutableStateOf<Triple<Int, String?, String?>?>(null) }
    LaunchedEffect(tick / 5, period) {
        companies = withContext(Dispatchers.Default) {
            val fresh = Engine.stats.aggregate(period)
            val active = Companies.rank(ctx, fresh.topQueried)
            val blocked = Companies.rank(ctx, fresh.topBlocked)
            Triple(active.size, active.firstOrNull()?.first?.name, blocked.firstOrNull()?.first?.name)
        }
    }
    val log = remember(tick) { Engine.recent() }
    val shown = log.filter {
        (query.isBlank() || it.domain.contains(query.trim().lowercase())) &&
            when (filter) { 1 -> it.blocked; 2 -> !it.blocked; else -> true }
    }.take(300)

    Page(title = "Actividad", icon = Ico.pie) {
        item {
            Row(
                Modifier.fillMaxWidth().offset(y = (-12).dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { picking = true }.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(period.label, style = Tx.body(pal.muted, 14))
                    Spacer(Modifier.width(6.dp))
                    Icon(Ico.chevronDown, null, tint = pal.muted, modifier = Modifier.size(12.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Row {
                    Quad(Format.count(stats.requests), "peticiones", pal.text, pal.muted, Modifier.weight(1f), right = true, bottom = true)
                    Quad(Format.count(stats.blocked), "bloqueados", pal.block, pal.block, Modifier.weight(1f), right = false, bottom = true)
                }
                Row {
                    Quad(ratio(stats), "del tráfico fuera", pal.accent, pal.accent, Modifier.weight(1f), right = true, bottom = false)
                    Quad(companies?.first?.toString() ?: "—", "empresas", pal.text, pal.muted, Modifier.weight(1f), right = false, bottom = false)
                }
            }
            Spacer(Modifier.height(14.dp))
            Hairline()
            NavRow("Más activo", value = companies?.second ?: "—") { nav.push(Route.Companies(false)) }
            NavRow("Más bloqueados", value = companies?.third ?: "—") { nav.push(Route.Companies(true)) }
        }
        item {
            Column(
                Modifier.padding(top = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(pal.surface).padding(16.dp),
            ) {
                Text("Actividad reciente", style = Tx.title(17))
                Spacer(Modifier.height(12.dp))
                Field(query, { query = it }, "Buscar", leading = Ico.search)
                Spacer(Modifier.height(10.dp))
                Segmented(listOf("Todo", "Bloqueado", "Permitido"), filter) { filter = it }
            }
        }
        if (shown.isEmpty()) item {
            Box(Modifier.fillMaxWidth().background(pal.surface)) {
                Note(if (Engine.running.value) "Aún no hay peticiones que coincidan." else "Activa la protección para ver aquí cada dominio que piden tus apps.")
            }
        }
        items(shown, key = { System.identityHashCode(it) }) { e -> LogRow(e) { selected = e } }
    }
    if (picking) PeriodPicker(period, { picking = false }) { period = it; Engine.settings.statsPeriod = it.name }
    selected?.let { EntryDetail(it) { selected = null } }
}

@Composable
private fun Quad(value: String, label: String, color: Color, labelColor: Color, modifier: Modifier, right: Boolean, bottom: Boolean) {
    val line = pal.line
    Box(modifier.height(86.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(value, style = Tx.figure(color, 28), maxLines = 1)
            Text(label, style = Tx.body(labelColor, 14))
        }
        if (right) Box(Modifier.align(Alignment.CenterEnd).width(1.dp).height(86.dp).background(line))
        if (bottom) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(line))
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogRow(e: LogEntry, onClick: () -> Unit) {
    val type = DnsMessage.typeName(e.qtype)
    val detail = when {
        e.result == Filter.Result.ALLOWED_USER -> "$type · permitido por ti"
        e.blocked -> "$type · bloqueado"
        e.answer.startsWith("$type ") -> e.answer
        e.answer.isEmpty() -> type
        else -> "$type · ${e.answer}"
    }
    Column(Modifier.fillMaxWidth().background(pal.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.domain, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Tx.body(if (e.blocked) pal.block else pal.text, 15))
                Text(detail, style = Tx.small(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(timeFmt.format(Date(e.time)), style = Tx.small())
        }
        Hairline(Modifier.padding(start = 16.dp))
    }
}

/** Ficha de una peticion con acciones de un toque, como en AdGuard. */
@Composable
fun EntryDetail(e: LogEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    val company = remember(e.domain) { Companies.lookup(ctx, e.domain) }
    val inBlock = s.userRules.any { it.text == e.domain || it.text == "||${e.domain}^" }
    val inAllow = s.allowlist.any { it.text == e.domain }
    Sheet(e.domain, onDismiss, buttons = {
        if (inBlock || inAllow) {
            Btn("Quitar regla", Modifier.weight(1f)) {
                s.userRules = s.userRules.filterNot { it.text == e.domain || it.text == "||${e.domain}^" }
                s.allowlist = s.allowlist.filterNot { it.text == e.domain }
                Engine.reloadConfig(); onDismiss()
            }
        } else if (e.blocked) {
            Btn("Desbloquear", Modifier.weight(1f), primary = true) {
                s.allowlist = s.allowlist + UserRule(e.domain); Engine.reloadConfig(); onDismiss()
            }
        } else {
            Btn("Bloquear", Modifier.weight(1f), primary = true) {
                s.userRules = s.userRules + UserRule("||${e.domain}^"); Engine.reloadConfig(); onDismiss()
            }
        }
        Btn("Copiar", Modifier.weight(1f)) {
            ctx.copyText("dominio", e.domain)
            onDismiss()
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Detail("Estado", when (e.result) {
                Filter.Result.BLOCKED_LIST -> "Bloqueado por un filtro"
                Filter.Result.BLOCKED_USER -> "Bloqueado por tu lista de bloqueo"
                Filter.Result.ALLOWED_USER -> "Permitido por tu lista de permitido"
                Filter.Result.PASSED -> "Procesado"
            })
            Detail("Tipo", DnsMessage.typeName(e.qtype))
            Detail("Respuesta", e.answer.ifEmpty { "—" })
            Detail("Hora", timeFmt.format(Date(e.time)))
            if (e.elapsedMs >= 0) Detail("Tiempo", "${e.elapsedMs} ms" + if (e.via.isNotEmpty()) " · ${e.via}" else "")
            company?.let { Detail("Empresa", "${it.name} · ${Companies.categoryLabel(it.category)}") }
            Text(
                "Las reglas incluyen subdominios. Android guarda las respuestas unos minutos: el cambio puede tardar en notarse en una app ya abierta.",
                style = Tx.small(),
            )
        }
    }
}

@Composable
fun Detail(k: String, v: String) {
    Row {
        Text(k, style = Tx.small(), modifier = Modifier.width(88.dp))
        Text(v, style = Tx.body(pal.text, 14))
    }
}

@Composable
private fun CompaniesScreen(blockedOnly: Boolean, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val period = periodOf(Engine.settings.statsPeriod)
    var rows by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var domains by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(tick / 5) {
        withContext(Dispatchers.Default) {
            val st = Engine.stats.aggregate(period)
            val src = if (blockedOnly) st.topBlocked else st.topQueried
            rows = Companies.rank(ctx, src).map { Triple(it.first.name, Companies.categoryLabel(it.first.category), it.second) }
            domains = src.take(50)
        }
    }
    Page(
        title = if (blockedOnly) "Más bloqueados" else "Más activos",
        subtitle = "${period.label} · empresas según la base de datos pública de AdGuard",
        onBack = onBack,
    ) {
        section("01", "Empresas")
        if (rows.isEmpty()) item { Note("Sin datos todavía.") }
        items(rows.take(50), key = { "c" + it.first }) { (name, cat, n) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = Tx.body(size = 15))
                    Text(cat, style = Tx.small())
                }
                Text(Format.count(n.toLong()), style = Tx.body(if (blockedOnly) pal.block else pal.text, 15))
            }
            Hairline(Modifier.padding(start = 18.dp))
        }
        section("02", "Dominios")
        items(domains, key = { "d" + it.first }) { (d, n) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp)) {
                Text(d, style = Tx.body(size = 14), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Format.count(n.toLong()), style = Tx.body(pal.muted, 14))
            }
            Hairline(Modifier.padding(start = 18.dp))
        }
    }
}

fun Context.copyText(label: String, text: String) =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
