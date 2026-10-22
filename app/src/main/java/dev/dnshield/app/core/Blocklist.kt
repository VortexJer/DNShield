package dev.dnshield.app.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Lista de bloqueo compacta: cada dominio se guarda como un hash FNV-1a de 64 bits en un
 * LongArray ordenado. 150.000 reglas ocupan ~1,2 MB en vez de los ~20 MB que costaria un
 * HashSet<String>, y la busqueda es binaria (unos 17 saltos).
 *
 *  - exact:  reglas tipo hosts ("0.0.0.0 ads.ejemplo.com"), solo ese dominio.
 *  - suffix: reglas tipo adblock ("||ejemplo.com^"), el dominio y todos sus subdominios.
 *  - allow:  excepciones ("@@||ejemplo.com^"), ganan a cualquier bloqueo.
 */
class Blocklist(
    private val exact: LongArray,
    private val suffix: LongArray,
    private val allow: LongArray,
) {
    val ruleCount: Int get() = exact.size + suffix.size

    /** [domain] en minusculas y sin punto final. */
    fun verdict(domain: String): Verdict {
        var blocked = exact.contains(DomainHash.of(domain))
        var start = 0
        while (true) {
            val h = DomainHash.of(domain, start)
            if (allow.contains(h)) return Verdict.ALLOWED
            if (!blocked && suffix.contains(h)) blocked = true
            val dot = domain.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return if (blocked) Verdict.BLOCKED else Verdict.NONE
    }

    fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream(16 + 8 * (exact.size + suffix.size + allow.size))
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            for (arr in arrayOf(exact, suffix, allow)) {
                out.writeInt(arr.size)
                for (v in arr) out.writeLong(v)
            }
        }
        return bytes.toByteArray()
    }

    enum class Verdict { BLOCKED, ALLOWED, NONE }

    companion object {
        private const val MAGIC = 0x44534231 // "DSB1"

        val EMPTY = Blocklist(LongArray(0), LongArray(0), LongArray(0))

        /** null si el fichero esta corrupto o cortado (p. ej. el movil se apago a mitad de guardarlo). */
        fun deserialize(data: ByteArray): Blocklist? = try {
            val buf = ByteBuffer.wrap(data)
            if (data.size < 16 || buf.int != MAGIC) null else {
                val arrays = Array(3) {
                    val n = buf.int
                    if (n < 0 || buf.remaining() < n * 8L) return null
                    LongArray(n) { buf.long }
                }
                Blocklist(arrays[0], arrays[1], arrays[2])
            }
        } catch (_: java.nio.BufferUnderflowException) {
            null
        }

        private fun LongArray.contains(h: Long) = java.util.Arrays.binarySearch(this, h) >= 0
    }
}

/** Convierte lineas de listas (formato adblock/AdGuard, hosts o dominios sueltos) en un [Blocklist]. */
class BlocklistBuilder {
    private val exact = LongList()
    private val suffix = LongList()
    private val allow = LongList()

    var accepted = 0; private set
    var skipped = 0; private set

    fun addLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '#' || line[0] == '[') return
        when {
            line.startsWith("@@||") -> adblockDomain(line, 4)?.let { allow.add(DomainHash.of(it)); accepted++ } ?: skipped++
            line.startsWith("||") -> adblockDomain(line, 2)?.let { suffix.add(DomainHash.of(it)); accepted++ } ?: skipped++
            else -> addHostsOrPlain(line)
        }
    }

    fun addAll(lines: Sequence<String>) = lines.forEach(::addLine)

    fun build() = Blocklist(exact.sortedUnique(), suffix.sortedUnique(), allow.sortedUnique())

    private fun addHostsOrPlain(line: String) {
        val body = line.substringBefore('#').trim()
        val tokens = body.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val domains = if (tokens[0] in SINK_IPS) tokens.drop(1) else if (tokens.size == 1) tokens else {
            skipped++; return
        }
        for (d in domains) {
            val clean = normalize(d)
            if (clean == null || clean in HOSTS_NOISE) { skipped++; continue }
            exact.add(DomainHash.of(clean)); accepted++
        }
    }

    /** "||ads.ejemplo.com^" o "||x.com^$important" -> dominio. Reglas con otros modificadores, comodines o rutas se descartan. */
    private fun adblockDomain(line: String, from: Int): String? {
        val caret = line.indexOf('^', from)
        val end = if (caret >= 0) caret else line.length
        if (caret >= 0) {
            val tail = line.substring(caret + 1)
            if (tail.isNotEmpty() && tail != "|" && tail != "\$important") return null
        } else if (line.indexOf('$', from) >= 0) return null
        return normalize(line.substring(from, end))
    }

    private class LongList {
        private var data = LongArray(1024)
        private var n = 0
        fun add(v: Long) {
            if (n == data.size) data = data.copyOf(n * 2)
            data[n++] = v
        }
        fun sortedUnique(): LongArray {
            val a = data.copyOf(n)
            a.sort()
            var w = 0
            for (i in a.indices) if (i == 0 || a[i] != a[w - 1]) a[w++] = a[i]
            return a.copyOf(w)
        }
    }

    companion object {
        private val SINK_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::0", "::1", "0", "fe80::1%lo0")
        private val HOSTS_NOISE = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
            "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
            "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
        )

        /** Minusculas, sin punto final; null si no parece un dominio (comodines, rutas, puertos, regex). */
        fun normalize(raw: String): String? {
            val d = raw.trim().trimEnd('.').lowercase()
            if (d.isEmpty() || d.length > 253 || d.startsWith('.') || d.startsWith('-')) return null
            for (c in d) {
                val ok = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
                if (!ok) return null
            }
            if (".." in d) return null
            return d
        }
    }
}

object DomainHash {
    private const val OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val PRIME = 1099511628211L

    fun of(s: String, from: Int = 0): Long {
        var h = OFFSET
        for (i in from until s.length) {
            h = h xor s[i].code.toLong()
            h *= PRIME
        }
        return h
    }
}
