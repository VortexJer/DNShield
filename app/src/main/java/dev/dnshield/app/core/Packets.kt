package dev.dnshield.app.core

/**
 * Una consulta DNS sacada de un paquete IP crudo leido del tunel. [packet] es una copia propia,
 * asi que la consulta puede reenviarse desde otro hilo mientras el lector reutiliza su buffer.
 */
class DnsQuery(
    val packet: ByteArray,
    val ipVersion: Int,
    private val ipHeaderLen: Int,
    val name: String,
    val qtype: Int,
    private val questionEnd: Int,
) {
    val dnsOffset get() = ipHeaderLen + 8
    val dnsLength get() = packet.size - dnsOffset
    val dnsId get() = Packets.u16(packet, dnsOffset)

    fun dnsPayload(): ByteArray = packet.copyOfRange(dnsOffset, packet.size)

    internal fun srcAddr(): ByteArray = if (ipVersion == 4) packet.copyOfRange(12, 16) else packet.copyOfRange(8, 24)
    internal fun dstAddr(): ByteArray = if (ipVersion == 4) packet.copyOfRange(16, 20) else packet.copyOfRange(24, 40)
    internal fun srcPort() = Packets.u16(packet, ipHeaderLen)
    internal fun dstPort() = Packets.u16(packet, ipHeaderLen + 2)
    internal fun questionBytes(): ByteArray = packet.copyOfRange(dnsOffset + 12, questionEnd)
    internal fun dnsFlags() = Packets.u16(packet, dnsOffset + 2)
}

object Packets {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val RCODE_SERVFAIL = 2
    private const val RCODE_NXDOMAIN = 3
    private const val RCODE_REFUSED = 5
    const val BLOCK_TTL = 300

    /** Devuelve la consulta si el paquete es IPv4/IPv6 + UDP al puerto 53 con una pregunta DNS valida; si no, null. */
    fun parse(buf: ByteArray, len: Int): DnsQuery? {
        if (len < 28 || len > buf.size) return null
        val version = (buf[0].toInt() ushr 4) and 0xF
        val ipHeaderLen: Int
        when (version) {
            4 -> {
                ipHeaderLen = (buf[0].toInt() and 0xF) * 4
                if (ipHeaderLen < 20 || buf[9].toInt() != 17) return null
                // Fragmentos: no los manejamos (las consultas DNS caben de sobra en un paquete).
                if ((u16(buf, 6) and 0x3FFF) != 0) return null
            }
            6 -> {
                ipHeaderLen = 40
                if (len < 48 || buf[6].toInt() != 17) return null
            }
            else -> return null
        }
        if (len < ipHeaderLen + 8 + 12) return null
        if (u16(buf, ipHeaderLen + 2) != 53) return null
        val udpLen = u16(buf, ipHeaderLen + 4)
        if (udpLen < 8 + 12 || ipHeaderLen + udpLen > len) return null
        val end = ipHeaderLen + udpLen
        val dns = ipHeaderLen + 8
        val flags = u16(buf, dns + 2)
        if (flags and 0x8000 != 0) return null // es una respuesta, no una consulta
        if (u16(buf, dns + 4) < 1) return null

        val name = StringBuilder()
        var p = dns + 12
        while (true) {
            if (p >= end) return null
            val l = buf[p].toInt() and 0xFF
            p++
            if (l == 0) break
            if (l and 0xC0 != 0 || p + l > end) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in p until p + l) {
                var c = (buf[i].toInt() and 0xFF).toChar()
                // Solo ASCII imprimible: tabuladores o saltos de linea corromperian registros y estadisticas.
                if (c < '!' || c > '~') return null
                if (c in 'A'..'Z') c += 32
                name.append(c)
            }
            if (name.length > 253) return null
            p += l
        }
        if (p + 4 > end) return null
        val qtype = u16(buf, p)
        return DnsQuery(buf.copyOf(end), version, ipHeaderLen, name.toString(), qtype, p + 4)
    }

    /** Como responde el filtro a un dominio bloqueado (los modos de "Opciones de bajo nivel" de AdGuard). */
    enum class BlockMode { DEFAULT, NXDOMAIN, REFUSED, CUSTOM_IP }

    class BlockOptions(
        val mode: BlockMode = BlockMode.DEFAULT,
        val ttl: Int = BLOCK_TTL,
        /** Para CUSTOM_IP: 4 bytes (A) y 16 bytes (AAAA). */
        val customV4: ByteArray = ByteArray(4),
        val customV6: ByteArray = ByteArray(16),
    )

    /**
     * Respuesta de bloqueo. DEFAULT: A -> 0.0.0.0, AAAA -> ::, el resto NXDOMAIN; con TTL para que
     * Android la cachee y no vuelva a preguntar enseguida (menos despertares, menos bateria).
     */
    fun blockedResponse(q: DnsQuery, opt: BlockOptions = BlockOptions()): ByteArray {
        val addr: ByteArray? = when (opt.mode) {
            BlockMode.DEFAULT -> when (q.qtype) { TYPE_A -> ByteArray(4); TYPE_AAAA -> ByteArray(16); else -> null }
            BlockMode.CUSTOM_IP -> when (q.qtype) { TYPE_A -> opt.customV4; TYPE_AAAA -> opt.customV6; else -> null }
            else -> null
        }
        val rcode = when {
            addr != null -> 0
            opt.mode == BlockMode.REFUSED -> RCODE_REFUSED
            else -> RCODE_NXDOMAIN
        }
        return answer(q, rcode, addr, opt.ttl)
    }

    /** NOERROR sin respuestas: "este nombre existe pero no tiene ese tipo". Se usa para bloquear AAAA (IPv6). */
    fun noDataResponse(q: DnsQuery): ByteArray = answer(q, 0, null, BLOCK_TTL)

    private fun answer(q: DnsQuery, rcode: Int, addr: ByteArray?, ttl: Int): ByteArray {
        val question = q.questionBytes()
        val answerLen = if (addr != null) 12 + addr.size else 0
        val dns = ByteArray(12 + question.size + answerLen)
        put16(dns, 0, q.dnsId)
        put16(dns, 2, 0x8000 or (q.dnsFlags() and 0x7900) or 0x0080 or rcode)
        put16(dns, 4, 1)
        put16(dns, 6, if (addr != null) 1 else 0)
        question.copyInto(dns, 12)
        if (addr != null) {
            var p = 12 + question.size
            put16(dns, p, 0xC00C); p += 2 // puntero al nombre de la pregunta
            put16(dns, p, q.qtype); p += 2
            put16(dns, p, 1); p += 2 // clase IN
            put16(dns, p, ttl ushr 16); put16(dns, p + 2, ttl and 0xFFFF); p += 4
            put16(dns, p, addr.size); p += 2
            addr.copyInto(dns, p)
        }
        return wrap(q, dns, dns.size)
    }

    /** SERVFAIL rapido cuando el DNS de salida no contesta, para que la app no espere a su propio timeout. */
    fun servfail(q: DnsQuery): ByteArray {
        val question = q.questionBytes()
        val dns = ByteArray(12 + question.size)
        put16(dns, 0, q.dnsId)
        put16(dns, 2, 0x8000 or (q.dnsFlags() and 0x7900) or 0x0080 or RCODE_SERVFAIL)
        put16(dns, 4, 1)
        question.copyInto(dns, 12)
        return wrap(q, dns, dns.size)
    }

    /** Envuelve una respuesta DNS en IP+UDP de vuelta hacia quien pregunto (direcciones y puertos invertidos). */
    fun wrap(q: DnsQuery, dns: ByteArray, dnsLen: Int): ByteArray {
        val src = q.dstAddr()
        val dst = q.srcAddr()
        val udpLen = 8 + dnsLen
        val ipLen = if (q.ipVersion == 4) 20 else 40
        val out = ByteArray(ipLen + udpLen)
        if (q.ipVersion == 4) {
            out[0] = 0x45
            put16(out, 2, out.size)
            put16(out, 6, 0x4000) // no fragmentar
            out[8] = 64
            out[9] = 17
            src.copyInto(out, 12)
            dst.copyInto(out, 16)
            put16(out, 10, checksum(out, 0, 20, 0))
        } else {
            out[0] = 0x60
            put16(out, 4, udpLen)
            out[6] = 17
            out[7] = 64
            src.copyInto(out, 8)
            dst.copyInto(out, 24)
        }
        put16(out, ipLen, q.dstPort())
        put16(out, ipLen + 2, q.srcPort())
        put16(out, ipLen + 4, udpLen)
        dns.copyInto(out, ipLen + 8, 0, dnsLen)
        put16(out, ipLen + 6, udpChecksum(out, ipLen, src, dst, udpLen))
        return out
    }

    internal fun udpChecksum(pkt: ByteArray, udpOff: Int, src: ByteArray, dst: ByteArray, udpLen: Int): Int {
        var sum = 0L
        sum += sumWords(src, 0, src.size)
        sum += sumWords(dst, 0, dst.size)
        sum += 17 + udpLen.toLong()
        val c = checksum(pkt, udpOff, udpLen, sum)
        return if (c == 0) 0xFFFF else c
    }

    internal fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial + sumWords(b, off, len)
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun sumWords(b: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i); i += 2 }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        return sum
    }

    fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }
}
