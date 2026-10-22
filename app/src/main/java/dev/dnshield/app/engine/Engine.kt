package dev.dnshield.app.engine

import android.content.Context
import dev.dnshield.app.core.Blocklist
import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Filter
import dev.dnshield.app.core.Packets
import dev.dnshield.app.core.UpstreamSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.ArrayDeque

/** Una linea de "Actividad reciente". La respuesta se rellena cuando contesta el servidor. */
class LogEntry(val time: Long, val domain: String, val qtype: Int, val result: Filter.Result) {
    @Volatile var answer: String = ""
    @Volatile var elapsedMs: Int = -1
    @Volatile var via: String = ""
    val blocked get() = result == Filter.Result.BLOCKED_LIST || result == Filter.Result.BLOCKED_USER
}

/**
 * Estado compartido del proceso: listas, reglas del usuario, servidor de salida, contadores y
 * registro. El servicio VPN escribe; la UI, la tile y la notificacion leen. Nada aqui hace trabajo
 * periodico: solo se mueve cuando llega una consulta DNS, para no gastar bateria en reposo.
 */
object Engine {
    private lateinit var app: Context
    lateinit var settings: Settings; private set
    lateinit var stats: StatsStore; private set

    @Volatile var blocklist: Blocklist = Blocklist.EMPTY; private set
    @Volatile private var userLayer: Blocklist = Blocklist.EMPTY
    @Volatile private var blockOptions = Packets.BlockOptions()
    @Volatile var blockIpv6 = false; private set
    @Volatile private var listsEnabled = true
    @Volatile private var upstream: UpstreamSet? = null
    /** DNS de la red fisica (operador/router), lo da el servicio al cambiar de red. */
    @Volatile var systemDns: List<InetAddress> = emptyList()
    /** true cuando la red actual esta excluida en "Configuracion de red" (filtrar Wi-Fi / datos moviles). */
    @Volatile var networkExcluded = false
    @Volatile var pausedUntil = 0L; private set

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private const val LOG_SIZE = 1000
    private val log = ArrayDeque<LogEntry>(LOG_SIZE)

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        settings = Settings(app)
        stats = StatsStore(app)
        reloadConfig()
    }

    /** Lee la lista compilada del disco (o la compila la primera vez). Llamar fuera del hilo principal. */
    fun loadBlocklist() {
        if (blocklist === Blocklist.EMPTY) blocklist = ListStore.load(app)
    }

    fun swapBlocklist(list: Blocklist) { blocklist = list }

    /** Relee reglas, modo de bloqueo y servidor. Barato: llamarlo tras cualquier cambio de ajustes. */
    fun reloadConfig() {
        val s = settings
        userLayer = Filter.buildUserLayer(
            blockRules = if (s.userRulesEnabled) s.userRules.filter { it.enabled }.map { it.text } else emptyList(),
            allowDomains = if (s.allowlistEnabled) s.allowlist.filter { it.enabled }.map { it.text } else emptyList(),
        )
        blockOptions = Packets.BlockOptions(
            mode = s.blockMode,
            ttl = s.blockedTtl.coerceIn(0, 86400),
            customV4 = ipBytes(s.customBlockIpv4, 4),
            customV6 = ipBytes(s.customBlockIpv6, 16),
        )
        blockIpv6 = s.blockIpv6
        listsEnabled = s.listsEnabled
        rebuildUpstream()
    }

    fun rebuildUpstream() {
        val s = settings
        val resolver = HostResolver(split(s.bootstrapServers))
        var client: OkHttpClient? = null
        val clientOf = { client ?: DohTransport.client(resolver).also { client = it } }
        val (name, addresses) = currentServer()
        val primary = addresses.mapNotNull { UpstreamSpec.parse(it) }.map { UpstreamSet.transportFor(it, resolver, clientOf) }
        val fallbackRaw = s.fallbackServers.trim()
        val fixedFallback = if (fallbackRaw.isEmpty() || fallbackRaw == "none") emptyList()
        else split(fallbackRaw).mapNotNull { UpstreamSpec.parse(it) }.map { UpstreamSet.transportFor(it, resolver, clientOf) }
        val useSystemFallback = fallbackRaw.isEmpty()
        val fallback = {
            if (useSystemFallback) systemDns.map { UdpTransport(it.hostAddress ?: "", 53) } else fixedFallback
        }
        val old = upstream
        upstream = UpstreamSet(primary, fallback, name)
        old?.close()
    }

    /** Nombre visible y direcciones del servidor elegido. "Sistema" usa los DNS de la red fisica. */
    fun currentServer(): Pair<String, List<String>> {
        val s = settings
        val id = s.serverId
        s.customServers.firstOrNull { it.id == id }?.let { return it.name to listOf(it.address) }
        val preset = DnsServers.byId(id) ?: DnsServers.byId(DnsServers.DEFAULT_ID)!!
        if (preset.id == DnsServers.SYSTEM_ID) return preset.name to systemDns.mapNotNull { it.hostAddress }
        val proto = s.protocolFor(preset.id)
        return "${preset.name} · ${DnsServers.protocolLabel(proto)}" to preset.address(proto)
    }

    /** true si el servidor elegido va por DoH o DoT. */
    fun serverEncrypted(): Boolean =
        currentServer().second.mapNotNull { UpstreamSpec.parse(it) }.any { it !is UpstreamSpec.Plain }

    /** Nombre corto para la pastilla de Inicio: "Cloudflare", "dns.google"... */
    fun serverShortName(): String {
        val name = currentServer().first.substringBefore(" ·")
        if ("://" in name) return name.substringAfter("://").substringBefore('/').substringBefore(':')
        return name.removeSuffix(" DNS").take(22)
    }

    fun setRunning(v: Boolean) {
        _running.value = v
        if (!v && ::app.isInitialized) stats.save()
    }

    fun pause(minutes: Int) { pausedUntil = System.currentTimeMillis() + minutes * 60_000L }
    fun resume() { pausedUntil = 0L }
    val isPaused get() = pausedUntil > System.currentTimeMillis()

    class Decision(val entry: LogEntry, val response: ByteArray?)

    /**
     * Ruta caliente. Devuelve la respuesta ya hecha si el dominio se bloquea (o si se bloquea IPv6);
     * response = null significa "reenviar al servidor".
     */
    fun decide(q: dev.dnshield.app.core.DnsQuery): Decision {
        val now = System.currentTimeMillis()
        val bypass = now < pausedUntil || networkExcluded
        val result = if (bypass) Filter.Result.PASSED else Filter.decide(q.name, userLayer, if (listsEnabled) blocklist else Blocklist.EMPTY)
        val entry = LogEntry(now, q.name, q.qtype, result)
        var response: ByteArray? = null
        if (entry.blocked) {
            response = Packets.blockedResponse(q, blockOptions)
            entry.answer = when (blockOptions.mode) {
                Packets.BlockMode.NXDOMAIN -> "NXDOMAIN (bloqueado)"
                Packets.BlockMode.REFUSED -> "REFUSED (bloqueado)"
                else -> "bloqueado"
            }
            entry.elapsedMs = 0
        } else if (blockIpv6 && !bypass && q.qtype == Packets.TYPE_AAAA) {
            response = Packets.noDataResponse(q)
            entry.answer = "AAAA bloqueado (IPv6)"
            entry.elapsedMs = 0
        }
        stats.record(q.name, entry.blocked, now)
        synchronized(log) {
            if (log.size == LOG_SIZE) log.removeFirst()
            log.addLast(entry)
        }
        return Decision(entry, response)
    }

    /** Reenvia al servidor elegido (con reservas). Bloquea el hilo: llamar desde el pool del servicio. */
    fun forward(query: ByteArray, entry: LogEntry): ByteArray? {
        val t0 = System.nanoTime()
        val ans = upstream?.exchange(query)
        entry.elapsedMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
        if (ans == null) {
            entry.answer = "sin respuesta del servidor"
            return null
        }
        entry.answer = DnsMessage.summarize(ans.bytes)
        entry.via = ans.via.protocol
        return ans.bytes
    }

    // toList() primero: ArrayDeque.reversed() de Java 21 solo existe en Android 15+.
    fun recent(): List<LogEntry> = synchronized(log) { log.toList().asReversed() }

    fun clearLog() = synchronized(log) { log.clear() }

    fun resetStats() {
        stats.reset()
        clearLog()
    }

    private fun split(s: String) = s.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    // Solo IPs literales: un nombre aqui provocaria una consulta DNS en el hilo principal.
    private fun ipBytes(s: String, len: Int): ByteArray = s.trim().takeIf { HostResolver.isLiteral(it) }
        ?.let { runCatching { InetAddress.getByName(it).address.takeIf { a -> a.size == len } }.getOrNull() } ?: ByteArray(len)
}
