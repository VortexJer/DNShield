package dev.dnshield.app.engine

import android.content.Context
import android.content.SharedPreferences
import dev.dnshield.app.core.Packets
import org.json.JSONArray
import org.json.JSONObject

/** Una regla escrita por el usuario que se puede activar/desactivar sin borrarla (las casillas de AdGuard). */
data class UserRule(val text: String, val enabled: Boolean = true)

/** Un servidor DNS anadido a mano ("Anadir servidor DNS del usuario"). */
data class CustomServer(val id: String, val name: String, val address: String)

enum class ThemeMode(val label: String) { SYSTEM("Predeterminado del sistema"), DARK("Oscuro"), LIGHT("Claro") }

/** Ajustes persistentes. Todo pasa por aqui para que la UI, el servicio y el arranque lean lo mismo. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("dnshield", Context.MODE_PRIVATE)

    // --- proteccion
    var enabled by bool("enabled", false)
    var serverId by string("server", DnsServers.DEFAULT_ID)
    /** Protocolo elegido para cada servidor predefinido: "plain" | "doh" | "dot". */
    fun protocolFor(serverId: String): String = p.getString("proto.$serverId", null) ?: DnsServers.byId(serverId)?.defaultProtocol ?: "plain"
    fun setProtocol(serverId: String, proto: String) = p.edit().putString("proto.$serverId", proto).apply()

    var customServers: List<CustomServer>
        get() = jsonList("customServers") { CustomServer(it.getString("id"), it.getString("name"), it.getString("address")) }
        set(v) = putJsonList("customServers", v) { JSONObject().put("id", it.id).put("name", it.name).put("address", it.address) }

    /** Vacio = usar el DNS del sistema como reserva; "none" = sin reserva (como en AdGuard). */
    var fallbackServers by string("fallback", "")
    /** Vacio = resolver los nombres de DoH/DoT con el DNS del sistema. */
    var bootstrapServers by string("bootstrap", "")

    // --- filtrado
    /** Interruptor de "Bloqueo de anuncios": usar o no las listas de filtros (las reglas propias siguen). */
    var listsEnabled by bool("listsEnabled", true)
    var userRulesEnabled by bool("userRulesEnabled", true)
    var userRules: List<UserRule>
        get() = jsonList("userRules") { UserRule(it.getString("t"), it.optBoolean("e", true)) }
        set(v) = putJsonList("userRules", v) { JSONObject().put("t", it.text).put("e", it.enabled) }

    var allowlistEnabled by bool("allowlistEnabled", true)
    var allowlist: List<UserRule>
        get() = jsonList("allowlist") { UserRule(it.getString("t"), it.optBoolean("e", true)) }
        set(v) = putJsonList("allowlist", v) { JSONObject().put("t", it.text).put("e", it.enabled) }

    var excludedApps: Set<String>
        get() = p.getStringSet("excluded", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("excluded", v).apply()

    // --- red
    var filterMobile by bool("filterMobile", true)
    var filterWifi by bool("filterWifi", true)
    var restartOnNetworkChange by bool("restartOnNetwork", false)

    // --- bajo nivel
    var blockMode: Packets.BlockMode
        get() = runCatching { Packets.BlockMode.valueOf(p.getString("blockMode", null) ?: "") }.getOrDefault(Packets.BlockMode.DEFAULT)
        set(v) = p.edit().putString("blockMode", v.name).apply()
    var customBlockIpv4 by string("customIpv4", "0.0.0.0")
    var customBlockIpv6 by string("customIpv6", "::")
    var blockedTtl by int("blockedTtl", Packets.BLOCK_TTL)
    var blockIpv6 by bool("blockIpv6", false)

    // --- general
    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = p.edit().putString("theme", v.name).apply()
    /** Color de acento en ARGB (rojo competicion por defecto). */
    var accent by long("accent", 0xFFE10600)
    var updateWifiOnly by bool("updateWifiOnly", true)
    /** Horas entre actualizaciones automaticas de filtros; 0 = nunca. */
    var updateIntervalHours by int("updateInterval", 24)
    var lastUpdate by long("lastUpdate", 0L)
    var statsPeriod by string("statsPeriod", "TODAY")

    fun resetAll() {
        p.edit().clear().apply()
    }

    // --- plumbing
    private fun bool(key: String, def: Boolean) = Pref({ p.getBoolean(key, def) }, { p.edit().putBoolean(key, it).apply() })
    private fun string(key: String, def: String) = Pref({ p.getString(key, def) ?: def }, { p.edit().putString(key, it).apply() })
    private fun int(key: String, def: Int) = Pref({ p.getInt(key, def) }, { p.edit().putInt(key, it).apply() })
    private fun long(key: String, def: Long) = Pref({ p.getLong(key, def) }, { p.edit().putLong(key, it).apply() })

    private fun <T> jsonList(key: String, read: (JSONObject) -> T): List<T> {
        val raw = p.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { read(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun <T> putJsonList(key: String, items: List<T>, write: (T) -> JSONObject) {
        val arr = JSONArray()
        items.forEach { arr.put(write(it)) }
        p.edit().putString(key, arr.toString()).apply()
    }

    private class Pref<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>, value: T) = set(value)
    }
}
