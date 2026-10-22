package dev.dnshield.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dnshield.app.BuildConfig
import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Packets
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.engine.HostResolver
import dev.dnshield.app.engine.ThemeMode
import dev.dnshield.app.vpn.ShieldVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val INTERVALS = listOf(6 to "Cada 6 horas", 12 to "Cada 12 horas", 24 to "Cada día", 72 to "Cada 3 días", 168 to "Cada semana", 0 to "Nunca")

@Composable
fun SettingsScreen(nav: Nav) {
    Page("Configuración", icon = Ico.gear) {
        item {
            Hairline()
            NavRow("General", "Apariencia, actualización de filtros y uso", icon = Ico.tune) { nav.push(Route.General) }
            NavRow("Configuración avanzada", "Red, bajo nivel, registros y restablecer", icon = Ico.sliders) { nav.push(Route.Advanced) }
            NavRow("Acerca de", "DNShield ${BuildConfig.VERSION_NAME}", icon = Ico.info) { nav.push(Route.About) }
        }
    }
}

@Composable
fun GeneralScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    var theme by remember { mutableStateOf(s.theme) }
    var wifiOnly by remember { mutableStateOf(s.updateWifiOnly) }
    var interval by remember { mutableStateOf(s.updateIntervalHours) }
    var pickInterval by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(Engine.isPaused) }

    Page("General", onBack = onBack) {
        section("01", "Tema")
        for (m in ThemeMode.entries) item(key = "t$m") {
            RadioRow(m.label, selected = theme == m) { theme = m; s.theme = m; ThemeBus.mode.value = m }
        }
        section("02", "Otros")
        item {
            ToggleRow("Actualizar automáticamente solo por Wi-Fi", "Si está habilitada, DNShield solo usará Wi-Fi para las actualizaciones de filtros en segundo plano", wifiOnly) {
                wifiOnly = it; s.updateWifiOnly = it
            }
            NavRow("Intervalo de actualización", value = INTERVALS.firstOrNull { it.first == interval }?.second ?: "${interval} h") { pickInterval = true }
            ToggleRow("Pausar 5 minutos", "Deja pasar todo sin filtrar un rato (también desde la notificación)", paused, enabled = Engine.running.value) {
                if (it) Engine.pause(5) else Engine.resume()
                paused = Engine.isPaused
            }
            NavRow("VPN siempre activa", "Para que Android la mantenga encendida siempre, actívalo para DNShield en los ajustes de VPN") {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
            }
        }
    }

    if (pickInterval) Sheet("Intervalo de actualización", { pickInterval = false }) {
        Column {
            for ((h, label) in INTERVALS) {
                RadioRow(label, selected = interval == h) { interval = h; s.updateIntervalHours = h; pickInterval = false }
            }
        }
    }
}

private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun exportLogs(ctx: android.content.Context) {
    val s = Engine.settings
    val text = buildString {
        appendLine("DNShield ${BuildConfig.VERSION_NAME} · ${stamp.format(Date())}")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
        appendLine("Protección: ${if (Engine.running.value) "activa" else "apagada"} · Servidor: ${Engine.currentServer().first}")
        appendLine("Reglas: ${Engine.blocklist.ruleCount} · Propias: ${s.userRules.size} · Permitidos: ${s.allowlist.size}")
        appendLine("Modo de bloqueo: ${s.blockMode} · Bloquear IPv6: ${s.blockIpv6} · Apps excluidas: ${s.excludedApps.size}")
        appendLine()
        for (e in Engine.recent()) {
            appendLine("${stamp.format(Date(e.time))}\t${e.result}\t${DnsMessage.typeName(e.qtype)}\t${e.domain}\t${e.answer}\t${e.elapsedMs}ms\t${e.via}")
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Registro de DNShield")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Exportar registros").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun AdvancedScreen(nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    var confirmStats by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf(s.restartOnNetworkChange) }
    Page("Configuración avanzada", onBack = onBack, icon = Ico.sliders) {
        section("01", "Conexión")
        item {
            ToggleRow(
                "Reiniciar la protección cuando la red cambie",
                "Habilítalo si tienes problemas de conexión al pasar de Wi-Fi a datos con DNShield activo",
                restart,
            ) { restart = it; s.restartOnNetworkChange = it }
        }
        section("02", "Expertos")
        item { NavRow("Opciones de bajo nivel", "¿Eres desarrollador?") { nav.push(Route.LowLevel) } }
        section("03", "Datos")
        item {
            NavRow("Exportar registros", "Comparte la actividad reciente y la configuración en texto") { exportLogs(ctx) }
            NavRow("Restablecer estadísticas", "Esta opción borrará todos los datos estadísticos") { confirmStats = true }
            NavRow("Restablecer configuración", "Vuelve a los ajustes de fábrica (no borra las listas descargadas)") { confirmReset = true }
        }
    }
    if (confirmStats) Confirm("Restablecer estadísticas", "Se borrarán todos los contadores, la gráfica y la actividad reciente.", "Borrar",
        onConfirm = { Engine.resetStats() }, onDismiss = { confirmStats = false })
    if (confirmReset) Confirm("Restablecer configuración", "Se perderán tus reglas, lista de permitido, servidores propios y ajustes.", "Restablecer",
        onConfirm = {
            val wasRunning = Engine.running.value
            s.resetAll()
            s.enabled = wasRunning
            Engine.reloadConfig()
            ThemeBus.mode.value = s.theme
            ShieldVpnService.restartIfRunning(ctx)
        }, onDismiss = { confirmReset = false })
}

@Composable
fun LowLevelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Engine.settings
    var mode by remember { mutableStateOf(s.blockMode) }
    var v4 by remember { mutableStateOf(s.customBlockIpv4) }
    var v6 by remember { mutableStateOf(s.customBlockIpv6) }
    var ttl by remember { mutableStateOf(s.blockedTtl.toString()) }
    var ipv6 by remember { mutableStateOf(s.blockIpv6) }
    var bootstrap by remember { mutableStateOf(s.bootstrapServers) }
    var fallback by remember { mutableStateOf(s.fallbackServers) }

    fun apply() {
        s.blockMode = mode
        if (HostResolver.isLiteral(v4.trim())) s.customBlockIpv4 = v4.trim()
        if (HostResolver.isLiteral(v6.trim())) s.customBlockIpv6 = v6.trim()
        ttl.toIntOrNull()?.let { s.blockedTtl = it.coerceIn(0, 86400) }
        s.blockIpv6 = ipv6
        s.bootstrapServers = bootstrap.trim()
        s.fallbackServers = fallback.trim()
        Engine.reloadConfig()
    }

    Page(
        "Opciones de bajo nivel",
        "¡Advertencia! Cambiar estas opciones puede romper la conexión a Internet o reducir la protección.",
        onBack = { apply(); onBack() },
    ) {
        section("01", "Modo túnel")
        item { Note("Túnel dividido: solo el DNS pasa por DNShield; el resto del tráfico va directo. Es lo que menos batería gasta.") }
        section("02", "Modo de bloqueo")
        val modes = listOf(
            Packets.BlockMode.DEFAULT to ("Predeterminado" to "0.0.0.0 / :: para A y AAAA; NXDOMAIN para el resto"),
            Packets.BlockMode.NXDOMAIN to ("NXDOMAIN" to "Responde que el dominio no existe"),
            Packets.BlockMode.REFUSED to ("REFUSED" to "Responde que el servidor rechaza la consulta"),
            Packets.BlockMode.CUSTOM_IP to ("IP personalizada" to "Responde con las direcciones de abajo"),
        )
        for ((m, labels) in modes) item(key = "m$m") {
            RadioRow(labels.first, labels.second, mode == m) { mode = m; apply() }
        }
        if (mode == Packets.BlockMode.CUSTOM_IP) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(v4, { v4 = it }, "IPv4, p. ej. 0.0.0.0")
                Field(v6, { v6 = it }, "IPv6, p. ej. ::")
            }
        }
        section("03", "Respuestas")
        item {
            ToggleRow("Bloquear IPv6", "Responde vacío a todas las peticiones AAAA", ipv6) { ipv6 = it; apply() }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("RESPUESTA TTL BLOQUEADA (SEGUNDOS)", style = Tx.label())
                Spacer(Modifier.height(8.dp))
                Field(ttl, { ttl = it.filter(Char::isDigit).take(5) }, "300", keyboard = KeyboardType.Number)
                Spacer(Modifier.height(4.dp))
                Text("Cuánto tiempo guarda Android la respuesta de bloqueo. Más alto = menos consultas = menos batería.", style = Tx.small())
            }
        }
        section("04", "Servidores")
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("SERVIDORES BOOTSTRAP", style = Tx.label())
                Spacer(Modifier.height(8.dp))
                Field(bootstrap, { bootstrap = it }, "Vacío = DNS del sistema", singleLine = false, minHeight = 70.dp)
                Spacer(Modifier.height(4.dp))
                Text("DNS normales (IP) para resolver los nombres de los servidores DoH/DoT. Uno por línea.", style = Tx.small())
                Spacer(Modifier.height(16.dp))
                Text("SERVIDORES ALTERNATIVOS", style = Tx.label())
                Spacer(Modifier.height(8.dp))
                Field(fallback, { fallback = it }, "Vacío = DNS del sistema · none = sin reserva", singleLine = false, minHeight = 70.dp)
                Spacer(Modifier.height(4.dp))
                Text("Se usan cuando el servidor elegido no responde. Admite 1.1.1.1, https://… y tls://…", style = Tx.small())
                Spacer(Modifier.height(12.dp))
                Btn("Guardar", primary = true) { apply() }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Page("Acerca de", "DNShield ${BuildConfig.VERSION_NAME}", onBack = onBack) {
        section("01", "Qué es")
        item {
            Note(
                "Bloqueador de anuncios y rastreadores para todo el móvil, a nivel de DNS. Funciona como una VPN local: " +
                    "nada sale del teléfono hacia servidores nuestros y solo se filtran los nombres de dominio.",
            )
        }
        section("02", "Fuentes")
        item {
            Note(
                "Filtros: catálogo público HostlistsRegistry de AdGuard (AdGuard DNS filter, HaGeZi, OISD, 1Hosts, StevenBlack, " +
                    "URLHaus, Phishing Army y otros, cada uno con su licencia).\n" +
                    "Empresas: base de datos pública companiesdb de AdGuard.\n" +
                    "Servidores DNS: servicios públicos de cada proveedor.",
            )
        }
        section("03", "Límites honestos")
        item {
            Note(
                "Un filtro DNS no puede quitar los anuncios que vienen del mismo dominio que el contenido (por ejemplo, los de la app de YouTube). " +
                    "Si en Ajustes de Android > DNS privado hay un servidor puesto, o Chrome usa su propio DNS seguro, esas consultas se saltan el filtro.",
            )
        }
    }
}

/** Cambios de tema desde cualquier pantalla (General o restablecer) hacia la raiz de la app. */
object ThemeBus {
    val mode = kotlinx.coroutines.flow.MutableStateFlow(Engine.settings.theme)
}
