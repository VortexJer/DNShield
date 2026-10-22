package dev.dnshield.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.dnshield.app.core.Blocklist
import dev.dnshield.app.core.BlocklistBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Una lista del catalogo publico de AdGuard (HostlistsRegistry) o una anadida a mano por URL. */
class FilterInfo(
    val key: String,
    val name: String,
    val description: String,
    val groupId: Int,
    val url: String,
    val homepage: String,
    val catalogVersion: String,
)

/** Estado local de una lista: activada, cuantas reglas aporto y cuando se descargo. */
data class FilterState(val enabled: Boolean, val rules: Int = 0, val updated: Long = 0L)

/**
 * Filtros DNS. El catalogo son las 66 listas publicas que usa AdGuard DNS; cada una se descarga a
 * files/lists/<key>.txt y la union de las activas se compila en files/blocklist.bin, que es lo
 * unico que se carga al arrancar (rapido y sin volver a leer texto).
 */
object ListStore {
    const val CUSTOM_GROUP = 0
    val GROUPS = linkedMapOf(
        1 to "Bloqueo de anuncios y rastreo",
        4 to "Seguridad",
        2 to "Otros",
        3 to "Idioma específico",
        CUSTOM_GROUP to "Personalizados",
    )
    val GROUP_HINTS = mapOf(
        1 to "Listas generales contra anuncios y rastreadores",
        4 to "Listas que bloquean las amenazas en línea conocidas",
        2 to "Listas para diferentes propósitos",
        3 to "Listas para sitios web en idiomas específicos",
        CUSTOM_GROUP to "Listas de filtros que se añaden manualmente",
    )

    /** AdGuard DNS filter (incluido en la app) + malware (URLHaus) + phishing (PhishTank/OpenPhish). */
    private val DEFAULT_ON = setOf("1", "11", "30")
    private const val BUNDLED_KEY = "1"
    private const val CATALOG_URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filters.json"
    private const val MAX_BYTES = 40L * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "lists").apply { mkdirs() }
    fun fileFor(ctx: Context, key: String) = File(dir(ctx), "${key.replace(':', '_')}.txt")
    private fun compiledFile(ctx: Context) = File(ctx.filesDir, "blocklist.bin")
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "filters-state.json")
    private fun customFile(ctx: Context) = File(ctx.filesDir, "filters-custom.json")
    private fun catalogFile(ctx: Context) = File(ctx.filesDir, "catalog.json")

    // ------------------------------------------------------------ catalogo y estado

    @Volatile private var catalogCache: List<FilterInfo>? = null

    fun catalog(ctx: Context): List<FilterInfo> {
        catalogCache?.let { return it }
        val text = catalogFile(ctx).takeIf { it.exists() }?.readText()
            ?: ctx.assets.open("filters.json").bufferedReader().use { it.readText() }
        val parsed = runCatching { parseCatalog(text) }.getOrElse {
            parseCatalog(ctx.assets.open("filters.json").bufferedReader().use { it.readText() })
        }
        catalogCache = parsed
        return parsed
    }

    private fun parseCatalog(text: String): List<FilterInfo> {
        val arr = JSONObject(text).getJSONArray("filters")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("deprecated", false) }
            .sortedBy { it.optInt("displayNumber", 999) }
            .map {
                FilterInfo(
                    key = it.getInt("filterId").toString(),
                    name = it.getString("name"),
                    description = it.optString("description"),
                    groupId = it.optInt("groupId", 2),
                    url = it.getString("downloadUrl"),
                    homepage = it.optString("homepage"),
                    catalogVersion = it.optString("version"),
                )
            }
    }

    fun customFilters(ctx: Context): List<FilterInfo> {
        val f = customFile(ctx).takeIf { it.exists() } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                FilterInfo(it.getString("key"), it.getString("name"), it.optString("url"), CUSTOM_GROUP,
                    it.getString("url"), "", "")
            }
        }.getOrDefault(emptyList())
    }

    fun all(ctx: Context): List<FilterInfo> = catalog(ctx) + customFilters(ctx)

    @Synchronized
    fun states(ctx: Context): Map<String, FilterState> {
        val f = stateFile(ctx)
        if (!f.exists()) return DEFAULT_ON.associateWith { FilterState(true) }
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { k ->
                val s = o.getJSONObject(k)
                FilterState(s.optBoolean("e"), s.optInt("r"), s.optLong("u"))
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    private fun saveStates(ctx: Context, states: Map<String, FilterState>) {
        val o = JSONObject()
        for ((k, s) in states) o.put(k, JSONObject().put("e", s.enabled).put("r", s.rules).put("u", s.updated))
        val tmp = File(ctx.filesDir, "filters-state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile(ctx))
    }

    fun isEnabled(ctx: Context, key: String) = states(ctx)[key]?.enabled == true

    /** Activa o desactiva una lista. Si hay que descargarla, lo hace (llamar fuera del hilo principal). */
    fun setEnabled(ctx: Context, key: String, on: Boolean): String? {
        val s = states(ctx).toMutableMap()
        s[key] = (s[key] ?: FilterState(false)).copy(enabled = on)
        saveStates(ctx, s)
        var error: String? = null
        if (on && !fileFor(ctx, key).exists() && key != BUNDLED_KEY) {
            val info = all(ctx).firstOrNull { it.key == key }
            error = if (info == null) "lista desconocida" else runCatching { download(info.url, fileFor(ctx, key)); null }
                .getOrElse { "no se pudo descargar: ${it.message}" }
            if (error == null) markUpdated(ctx, listOf(key))
        }
        Engine.swapBlocklist(compile(ctx))
        return error
    }

    fun groupSummary(ctx: Context, groupId: Int): Pair<Int, Int> {
        val st = states(ctx)
        val inGroup = all(ctx).filter { it.groupId == groupId }
        return inGroup.count { st[it.key]?.enabled == true } to inGroup.size
    }

    /** Anade una lista por URL. Devuelve null si fue bien o el motivo del fallo. */
    fun addCustom(ctx: Context, name: String, url: String): String? {
        // Solo https: por http alguien en la misma red podria cambiar la lista por el camino.
        if (!url.startsWith("https://")) return "la URL debe empezar por https:// (por seguridad, sin cifrar no se admite)"
        val key = "custom:" + Integer.toHexString(url.hashCode())
        val dest = fileFor(ctx, key)
        runCatching { download(url, dest) }.onFailure { return "no se pudo descargar: ${it.message}" }
        val probe = BlocklistBuilder()
        dest.bufferedReader().useLines { probe.addAll(it) }
        if (probe.accepted == 0) {
            dest.delete()
            return "no contiene reglas DNS reconocibles"
        }
        val arr = JSONArray()
        customFilters(ctx).filter { it.key != key }.forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name).put("url", it.url))
        }
        arr.put(JSONObject().put("key", key).put("name", name.ifBlank { url.substringAfter("://").substringBefore('/') }).put("url", url))
        customFile(ctx).writeText(arr.toString())
        val s = states(ctx).toMutableMap()
        s[key] = FilterState(true, 0, System.currentTimeMillis())
        saveStates(ctx, s)
        Engine.swapBlocklist(compile(ctx))
        return null
    }

    fun removeCustom(ctx: Context, key: String) {
        val arr = JSONArray()
        customFilters(ctx).filter { it.key != key }.forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name).put("url", it.url))
        }
        customFile(ctx).writeText(arr.toString())
        fileFor(ctx, key).delete()
        saveStates(ctx, states(ctx) - key)
        Engine.swapBlocklist(compile(ctx))
    }

    // ------------------------------------------------------------ compilacion

    /** Carga la lista compilada; si no existe (primer arranque) la crea desde lo que haya en disco. */
    fun load(ctx: Context): Blocklist {
        compiledFile(ctx).takeIf { it.exists() }?.let { f ->
            Blocklist.deserialize(f.readBytes())?.let { return it }
        }
        return compile(ctx)
    }

    @Synchronized
    fun compile(ctx: Context): Blocklist {
        ensureBundled(ctx)
        val st = states(ctx).toMutableMap()
        val builder = BlocklistBuilder()
        for (info in all(ctx)) {
            val s = st[info.key] ?: continue
            if (!s.enabled) continue
            val f = fileFor(ctx, info.key).takeIf { it.exists() } ?: continue
            val before = builder.accepted
            f.bufferedReader().useLines { builder.addAll(it) }
            st[info.key] = s.copy(rules = builder.accepted - before)
        }
        val list = builder.build()
        val tmp = File(ctx.filesDir, "blocklist.bin.tmp")
        tmp.writeBytes(list.serialize())
        tmp.renameTo(compiledFile(ctx))
        saveStates(ctx, st)
        return list
    }

    private fun ensureBundled(ctx: Context) {
        val f = fileFor(ctx, BUNDLED_KEY)
        if (f.exists()) return
        ctx.assets.open("lists/adguard.txt").use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    // ------------------------------------------------------------ actualizacion

    fun isDue(ctx: Context): Boolean {
        val s = Settings(ctx)
        if (s.updateIntervalHours <= 0) return false
        if (System.currentTimeMillis() - s.lastUpdate < s.updateIntervalHours * 3_600_000L) return false
        return !s.updateWifiOnly || onUnmeteredNetwork(ctx)
    }

    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // Con nuestra VPN activa la red por defecto es la VPN; miramos todas las redes fisicas.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    /** Descarga el catalogo y las listas activas, y recompila. Una lista que falla conserva su copia anterior. */
    @Synchronized
    fun update(ctx: Context, progress: (String) -> Unit): String {
        progress("Actualizando catálogo")
        runCatching {
            val tmp = File(ctx.filesDir, "catalog.json.part")
            download(CATALOG_URL, tmp)
            parseCatalog(tmp.readText()) // valida antes de sustituir
            tmp.renameTo(catalogFile(ctx))
            catalogCache = null
        }
        val st = states(ctx)
        val active = all(ctx).filter { st[it.key]?.enabled == true }
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((i, info) in active.withIndex()) {
            progress("Descargando ${info.name} (${i + 1}/${active.size})")
            if (runCatching { download(info.url, fileFor(ctx, info.key)) }.isSuccess) ok += info.key else failed += info.name
        }
        markUpdated(ctx, ok)
        progress("Compilando reglas")
        val list = compile(ctx)
        Engine.swapBlocklist(list)
        if (ok.isNotEmpty()) Settings(ctx).lastUpdate = System.currentTimeMillis()
        return buildString {
            append("${Format.count(list.ruleCount.toLong())} reglas activas")
            if (failed.isNotEmpty()) append(" · sin conexión para: ${failed.joinToString()}")
        }
    }

    private fun markUpdated(ctx: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        val s = states(ctx).toMutableMap()
        for (k in keys) s[k] = (s[k] ?: FilterState(true)).copy(updated = now)
        saveStates(ctx, s)
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "DNShield-Android/1.0")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val tmp = File(dest.parentFile, dest.name + ".part")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("lista demasiado grande")
                        out.write(buf, 0, n)
                    }
                }
            }
            // Una descarga vacia o una pagina de error no debe sustituir una lista buena.
            if (total < 64) { tmp.delete(); error("respuesta vacía") }
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        } finally {
            conn.disconnect()
        }
    }
}
