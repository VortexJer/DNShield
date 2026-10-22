package dev.dnshield.app.core

/** Lectura y escritura de mensajes DNS sueltos (sin IP/UDP): consultas propias y resumen de respuestas. */
object DnsMessage {
    private val RCODES = mapOf(1 to "FORMERR", 2 to "SERVFAIL", 3 to "NXDOMAIN", 4 to "NOTIMP", 5 to "REFUSED")
    private val TYPES = mapOf(1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 12 to "PTR", 15 to "MX", 16 to "TXT",
        28 to "AAAA", 33 to "SRV", 64 to "SVCB", 65 to "HTTPS")

    fun typeName(t: Int) = TYPES[t] ?: "TYPE$t"

    /** Consulta estandar con RD activado. */
    fun query(name: String, qtype: Int, id: Int): ByteArray {
        val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
        val q = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        Packets.put16(q, 0, id)
        Packets.put16(q, 2, 0x0100)
        Packets.put16(q, 4, 1)
        var p = 12
        for (l in labels) {
            q[p++] = l.length.toByte()
            for (c in l) q[p++] = c.code.toByte()
        }
        q[p++] = 0
        Packets.put16(q, p, qtype)
        Packets.put16(q, p + 2, 1)
        return q
    }

    class Record(val type: Int, val data: String, val raw: ByteArray?)

    /** Registros de la seccion de respuestas. Tolera mensajes rotos devolviendo lo que haya podido leer. */
    fun answers(msg: ByteArray, len: Int = msg.size): List<Record> {
        val out = ArrayList<Record>()
        try {
            if (len < 12) return out
            val qd = Packets.u16(msg, 4)
            val an = Packets.u16(msg, 6)
            var p = 12
            repeat(qd) { p = skipName(msg, p, len) + 4 }
            repeat(an) {
                p = skipName(msg, p, len)
                if (p + 10 > len) return out
                val type = Packets.u16(msg, p)
                val rdLen = Packets.u16(msg, p + 8)
                val rd = p + 10
                if (rd + rdLen > len) return out
                val data = when (type) {
                    1 -> if (rdLen == 4) (0 until 4).joinToString(".") { (msg[rd + it].toInt() and 0xFF).toString() } else "?"
                    28 -> if (rdLen == 16) ipv6(msg, rd) else "?"
                    5, 2, 12 -> readName(msg, rd, len)
                    else -> ""
                }
                val raw = if (type == 1 || type == 28) msg.copyOfRange(rd, rd + rdLen) else null
                out += Record(type, data, raw)
                p = rd + rdLen
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return out
    }

    /** Texto corto para el registro de actividad: "A 142.250.1.1", "CNAME x.net", "NXDOMAIN"... */
    fun summarize(msg: ByteArray, len: Int = msg.size): String {
        if (len < 12) return "sin respuesta"
        val rcode = Packets.u16(msg, 2) and 0xF
        RCODES[rcode]?.let { return it }
        val recs = answers(msg, len)
        val first = recs.firstOrNull { it.type == 1 || it.type == 28 } ?: recs.firstOrNull() ?: return "sin datos"
        return (typeName(first.type) + " " + first.data).trim()
    }

    fun rcode(msg: ByteArray) = if (msg.size >= 4) Packets.u16(msg, 2) and 0xF else -1

    private fun skipName(b: ByteArray, start: Int, len: Int): Int {
        var p = start
        while (p < len) {
            val l = b[p].toInt() and 0xFF
            if (l == 0) return p + 1
            if (l and 0xC0 == 0xC0) return p + 2
            p += l + 1
        }
        throw IndexOutOfBoundsException()
    }

    private fun readName(b: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        var p = start
        var jumps = 0
        while (p < len) {
            val l = b[p].toInt() and 0xFF
            if (l == 0) break
            if (l and 0xC0 == 0xC0) {
                if (p + 1 >= len || ++jumps > 16) break
                p = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                continue
            }
            if (p + 1 + l > len) break
            if (sb.isNotEmpty()) sb.append('.')
            for (i in p + 1..p + l) sb.append((b[i].toInt() and 0xFF).toChar())
            p += l + 1
        }
        return sb.toString()
    }

    private fun ipv6(b: ByteArray, off: Int): String =
        (0 until 8).joinToString(":") { Integer.toHexString(Packets.u16(b, off + it * 2)) }
}

/**
 * Servidor DNS de salida escrito como en AdGuard: "1.1.1.1", "1.1.1.1:5353",
 * "https://dns.cloudflare.com/dns-query" (DoH), "tls://dns.quad9.net" (DoT) o "udp://9.9.9.9".
 */
sealed class UpstreamSpec {
    abstract val display: String

    data class Plain(val host: String, val port: Int = 53) : UpstreamSpec() {
        override val display get() = if (port == 53) host else "$host:$port"
    }
    data class Doh(val url: String, val host: String) : UpstreamSpec() {
        override val display get() = url
    }
    data class Dot(val host: String, val port: Int = 853) : UpstreamSpec() {
        override val display get() = "tls://$host" + if (port == 853) "" else ":$port"
    }

    val protocol: String
        get() = when (this) { is Plain -> "DNS"; is Doh -> "DoH"; is Dot -> "DoT" }

    companion object {
        fun parse(raw: String): UpstreamSpec? {
            val s = raw.trim()
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            return when {
                s.startsWith("https://", ignoreCase = true) -> {
                    val rest = s.substring(8)
                    val host = rest.substringBefore('/').substringBefore(':')
                    if (!validHost(host)) null else Doh(s, host)
                }
                s.startsWith("tls://", ignoreCase = true) -> hostPort(s.substring(6), 853)?.let { Dot(it.first, it.second) }
                s.startsWith("udp://", ignoreCase = true) -> hostPort(s.substring(6), 53)?.let { Plain(it.first, it.second) }
                s.contains("://") -> null
                else -> hostPort(s, 53)?.let { Plain(it.first, it.second) }
            }
        }

        private fun hostPort(s: String, def: Int): Pair<String, Int>? {
            val v = s.trimEnd('/')
            // IPv6 literal: [2001:db8::1]:53 o 2001:db8::1
            if (v.startsWith("[")) {
                val end = v.indexOf(']')
                if (end < 0) return null
                val host = v.substring(1, end)
                val port = v.substring(end + 1).removePrefix(":").ifEmpty { "$def" }.toIntOrNull() ?: return null
                return if (port in 1..65535 && host.contains(':')) host to port else null
            }
            if (v.count { it == ':' } > 1) return if (v.all { it.isLetterOrDigit() || it == ':' }) v to def else null
            val host = v.substringBefore(':')
            val port = if (':' in v) v.substringAfter(':').toIntOrNull() ?: return null else def
            return if (validHost(host) && port in 1..65535) host to port else null
        }

        private fun validHost(h: String) = h.isNotEmpty() && h.length <= 253 &&
            h.all { it.isLetterOrDigit() || it == '.' || it == '-' } && !h.startsWith('.') && !h.startsWith('-')
    }
}
