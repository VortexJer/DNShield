package dev.dnshield.app.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Period(val label: String) { TODAY("Hoy"), H24("24 horas"), D7("7 días"), D30("30 días"), ALL("Todo el tiempo") }

class PeriodStats(
    val requests: Long,
    val blocked: Long,
    /** Barras de la grafica (antiguas -> recientes): consultas y bloqueadas por hora o por dia. */
    val series: List<Pair<Long, Long>>,
    val seriesUnit: String,
    val topQueried: List<Pair<String, Int>>,
    val topBlocked: List<Pair<String, Int>>,
)

/**
 * Estadisticas persistentes: contadores por hora (31 dias) y por dominio por dia (con tope, para
 * que un movil con mucho trafico no crezca sin limite). Solo se toca cuando llega una consulta.
 */
class StatsStore(private val ctx: Context) {
    private val zone = ZoneId.systemDefault()
    private val hours = HashMap<Long, LongArray>()
    private val days = HashMap<String, Pair<HashMap<String, Int>, HashMap<String, Int>>>()
    private var allRequests = 0L
    private var allBlocked = 0L
    private var dirty = false
    private val dirtyDays = HashSet<String>()
    private var lastSave = 0L

    private val dir = File(ctx.filesDir, "stats").apply { mkdirs() }
    private val hoursFile = File(dir, "hours.json")

    init {
        runCatching {
            val o = JSONObject(hoursFile.readText())
            allRequests = o.optLong("allR"); allBlocked = o.optLong("allB")
            val h = o.getJSONObject("h")
            for (k in h.keys()) {
                val a = h.getJSONArray(k)
                hours[k.toLong()] = longArrayOf(a.getLong(0), a.getLong(1))
            }
        }
        dir.listFiles { f -> f.name.endsWith(".tsv") }?.forEach { f ->
            val q = HashMap<String, Int>(); val b = HashMap<String, Int>()
            runCatching {
                f.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size == 3) (if (parts[0] == "b") b else q)[parts[1]] = parts[2].toIntOrNull() ?: 0
                }
            }
            days[f.name.removeSuffix(".tsv")] = q to b
        }
        prune()
    }

    @Synchronized
    fun record(domain: String, blocked: Boolean, now: Long) {
        val h = now / 3_600_000L
        val bucket = hours.getOrPut(h) { LongArray(2) }
        bucket[0]++
        allRequests++
        if (blocked) { bucket[1]++; allBlocked++ }
        val day = dayOf(now)
        val (q, b) = days.getOrPut(day) { HashMap<String, Int>() to HashMap() }
        bump(q, domain)
        if (blocked) bump(b, domain)
        dirtyDays += day
        dirty = true
        if (now - lastSave > 60_000L) save()
    }

    private fun bump(m: HashMap<String, Int>, d: String) {
        val c = m[d]
        if (c != null) m[d] = c + 1 else if (m.size < DOMAIN_CAP) m[d] = 1
    }

    @Synchronized
    fun aggregate(p: Period, now: Long = System.currentTimeMillis()): PeriodStats {
        val nowHour = now / 3_600_000L
        val today = LocalDate.now(zone)
        val hourly = p == Period.TODAY || p == Period.H24
        val series = ArrayList<Pair<Long, Long>>()
        var r = 0L
        var bl = 0L
        val dayKeys: List<String>
        if (hourly) {
            val startHour = if (p == Period.H24) nowHour - 23
            else today.atStartOfDay(zone).toInstant().toEpochMilli() / 3_600_000L
            for (h in startHour..nowHour) {
                val v = hours[h]
                series += (v?.get(0) ?: 0L) to (v?.get(1) ?: 0L)
                r += v?.get(0) ?: 0; bl += v?.get(1) ?: 0
            }
            dayKeys = if (p == Period.TODAY) listOf(today.toString()) else listOf(today.minusDays(1).toString(), today.toString())
        } else {
            val n = when (p) { Period.D7 -> 7; Period.D30 -> 30; else -> 31 }
            val first = today.minusDays((n - 1).toLong())
            for (i in 0 until n) {
                val d = first.plusDays(i.toLong())
                val s = d.atStartOfDay(zone).toInstant().toEpochMilli() / 3_600_000L
                var dr = 0L; var db = 0L
                for (h in s until s + 24) hours[h]?.let { dr += it[0]; db += it[1] }
                series += dr to db
                r += dr; bl += db
            }
            dayKeys = (0 until n).map { first.plusDays(it.toLong()).toString() }
            if (p == Period.ALL) { r = allRequests; bl = allBlocked }
        }
        val q = HashMap<String, Int>(); val b = HashMap<String, Int>()
        for (k in dayKeys) days[k]?.let { (dq, db) ->
            dq.forEach { (d, c) -> q[d] = (q[d] ?: 0) + c }
            db.forEach { (d, c) -> b[d] = (b[d] ?: 0) + c }
        }
        return PeriodStats(r, bl, series, if (hourly) "hora" else "día", top(q), top(b))
    }

    private fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(TOP_KEEP).map { it.key to it.value }

    /** Mapa dominio->consultas del periodo, para contar empresas. */
    @Synchronized
    fun domainsOf(p: Period): Map<String, Int> = aggregate(p).topQueried.toMap()

    @Synchronized
    fun save() {
        if (!dirty) return
        lastSave = System.currentTimeMillis()
        dirty = false
        val h = JSONObject()
        for ((k, v) in hours) h.put(k.toString(), org.json.JSONArray().put(v[0]).put(v[1]))
        writeAtomic(hoursFile, JSONObject().put("allR", allRequests).put("allB", allBlocked).put("h", h).toString())
        // Solo se reescriben los dias que han cambiado (hoy, y ayer si acaba de cambiar el dia).
        for (day in dirtyDays) days[day]?.let { (q, b) ->
            val sb = StringBuilder()
            q.forEach { (d, c) -> sb.append("q\t").append(d).append('\t').append(c).append('\n') }
            b.forEach { (d, c) -> sb.append("b\t").append(d).append('\t').append(c).append('\n') }
            writeAtomic(File(dir, "$day.tsv"), sb.toString())
        }
        dirtyDays.clear()
        prune()
    }

    @Synchronized
    fun reset() {
        hours.clear(); days.clear(); dirtyDays.clear(); allRequests = 0; allBlocked = 0
        dir.listFiles()?.forEach { it.delete() }
        dirty = false
    }

    private fun prune() {
        val oldestHour = System.currentTimeMillis() / 3_600_000L - KEEP_DAYS * 24
        hours.keys.removeAll { it < oldestHour }
        val oldestDay = LocalDate.now(zone).minusDays(KEEP_DAYS.toLong()).toString()
        days.keys.filter { it < oldestDay }.forEach { days.remove(it); File(dir, "$it.tsv").delete() }
    }

    private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        private const val DOMAIN_CAP = 3000
        private const val TOP_KEEP = 200
        private const val KEEP_DAYS = 31
    }
}

/** Dominio -> empresa, de la base de datos publica de AdGuard (companiesdb). */
object Companies {
    class Company(val name: String, val category: String)

    @Volatile private var map: Map<String, Company>? = null

    private fun load(ctx: Context): Map<String, Company> {
        map?.let { return it }
        val m = HashMap<String, Company>(6000)
        val names = HashMap<String, Company>()
        ctx.assets.open("companies.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 3) continue
                m[p[0]] = names.getOrPut(p[1]) { Company(p[1], p[2]) }
            }
        }
        map = m
        return m
    }

    fun lookup(ctx: Context, domain: String): Company? {
        val m = load(ctx)
        var start = 0
        while (true) {
            m[if (start == 0) domain else domain.substring(start)]?.let { return it }
            val dot = domain.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    /** Empresas ordenadas por numero de consultas a sus dominios. */
    fun rank(ctx: Context, domains: List<Pair<String, Int>>): List<Pair<Company, Int>> {
        val acc = HashMap<Company, Int>()
        for ((d, c) in domains) lookup(ctx, d)?.let { acc[it] = (acc[it] ?: 0) + c }
        return acc.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun categoryLabel(c: String) = when (c) {
        "advertising" -> "Publicidad"
        "site_analytics" -> "Analítica"
        "social_media" -> "Redes sociales"
        "customer_interaction" -> "Interacción"
        "audio_video_player" -> "Vídeo y audio"
        "cdn" -> "CDN"
        "essential" -> "Esencial"
        "hosting" -> "Alojamiento"
        "pornvertising" -> "Publicidad adulta"
        "comments" -> "Comentarios"
        "extensions" -> "Extensiones"
        "email" -> "Correo"
        "consent" -> "Consentimiento"
        "telemetry" -> "Telemetría"
        "mobile_analytics" -> "Analítica móvil"
        else -> "Otros"
    }
}

object Format {
    fun count(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace('.', ',')
        n >= 10_000 -> "%.1fK".format(n / 1_000.0).replace('.', ',')
        else -> "%,d".format(n).replace(',', '.')
    }

    fun ago(t: Long): String {
        if (t <= 0) return "nunca"
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 5 -> "ahora"
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            s < 86400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86400} d"
        }
    }
}
