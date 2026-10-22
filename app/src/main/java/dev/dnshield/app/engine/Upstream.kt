package dev.dnshield.app.engine

import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Packets
import dev.dnshield.app.core.UpstreamSpec
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/** Un servidor DNS de salida. exchange() recibe y devuelve mensajes DNS sin IP/UDP. */
interface Transport {
    val label: String
    val protocol: String
    fun exchange(query: ByteArray): ByteArray
    fun close() {}
}

/**
 * Resuelve los nombres de los servidores DoH/DoT. Sin "bootstrap" usa el DNS del sistema
 * (la app esta excluida de su propio tunel, asi que no hay bucle); con bootstrap pregunta a esas IPs.
 */
class HostResolver(private val bootstrap: List<String>) {
    fun resolve(host: String): List<InetAddress> {
        if (isLiteral(host)) return listOf(InetAddress.getByName(host))
        if (bootstrap.isEmpty()) return InetAddress.getAllByName(host).toList()
        for (server in bootstrap) {
            runCatching {
                val resp = UdpTransport(server, 53).exchange(DnsMessage.query(host, Packets.TYPE_A, Random.nextInt(0, 65536)))
                val ips = DnsMessage.answers(resp).filter { it.type == Packets.TYPE_A && it.raw != null }
                    .map { InetAddress.getByAddress(host, it.raw) }
                if (ips.isNotEmpty()) return ips
            }
        }
        throw IOException("bootstrap no resolvio $host")
    }

    companion object {
        fun isLiteral(h: String) = ':' in h || h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }
}

class UdpTransport(private val host: String, private val port: Int) : Transport {
    override val label get() = if (port == 53) host else "$host:$port"
    override val protocol = "DNS"

    override fun exchange(query: ByteArray): ByteArray {
        DatagramSocket().use { s ->
            // Corto a proposito: Android abandona hacia los 5 s y aun hay que probar la reserva.
            s.soTimeout = 1500
            val server = InetAddress.getByName(host)
            // connect(): el sistema descarta paquetes de cualquier otra IP o puerto (respuestas falsificadas).
            s.connect(server, port)
            s.send(DatagramPacket(query, query.size, server, port))
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            val id = Packets.u16(query, 0)
            while (true) {
                s.receive(pkt)
                if (pkt.length >= 12 && pkt.address == server && pkt.port == port && Packets.u16(buf, 0) == id &&
                    (Packets.u16(buf, 2) and 0x8000) != 0
                ) return buf.copyOf(pkt.length)
            }
        }
    }
}

/** Conexion TLS reutilizable con su lector con buffer (para DoT y para el DoH ligero). */
private class TlsConn(val socket: SSLSocket) {
    val input = java.io.BufferedInputStream(socket.inputStream, 8192)
    val output: java.io.OutputStream = socket.outputStream
    fun close() = runCatching { socket.close() }
}

private fun openTls(host: String, port: Int, resolver: HostResolver): TlsConn {
    val addr = resolver.resolve(host).first()
    val raw = Socket()
    raw.connect(InetSocketAddress(addr, port), 3000)
    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
    // El propio saludo TLS comprueba que el certificado es de ESTE nombre (igual en Android y en Java de escritorio).
    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    ssl.soTimeout = 4000
    ssl.startHandshake()
    // Segunda capa, independiente de la plataforma.
    if (!okhttp3.internal.tls.OkHostnameVerifier.verify(host, ssl.session)) {
        ssl.close()
        throw IOException("certificado no valido para $host")
    }
    return TlsConn(ssl)
}

/** Pequena reserva de conexiones abiertas: se reutiliza una si esta libre; una caducada se descarta y se reintenta. */
private class TlsPool(private val host: String, private val port: Int, private val resolver: HostResolver) {
    private val idle = ConcurrentLinkedQueue<TlsConn>()

    fun <T> use(block: (TlsConn) -> T): T {
        idle.poll()?.let { c ->
            try {
                return block(c).also { release(c) }
            } catch (_: IOException) {
                c.close()
            }
        }
        val c = openTls(host, port, resolver)
        try {
            return block(c).also { release(c) }
        } catch (e: IOException) {
            c.close()
            throw e
        }
    }

    private fun release(c: TlsConn) {
        if (idle.size < 2) idle.add(c) else c.close()
    }

    fun close() {
        while (true) (idle.poll() ?: return).close()
    }
}

/**
 * DNS-over-HTTPS (RFC 8484, POST). Va por un cliente HTTP/1.1 minimo sobre una conexion TLS
 * reutilizada: medido en el emulador, cuesta unas 3 veces menos CPU por consulta que OkHttp.
 * Si un servidor no lo acepta, se pasa a OkHttp (HTTP/2) para siempre en este servidor.
 */
class DohTransport(private val url: String, private val client: OkHttpClient, resolver: HostResolver? = null) : Transport {
    override val label get() = url
    override val protocol = "DoH"

    private val uri = java.net.URI(url)
    private val host = uri.host
    private val port = if (uri.port > 0) uri.port else 443
    private val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
    private val pool = resolver?.let { TlsPool(host, port, it) }
    @Volatile private var lightweight = pool != null

    override fun exchange(query: ByteArray): ByteArray {
        val id = Packets.u16(query, 0)
        val body = query.copyOf().also { Packets.put16(it, 0, 0) } // RFC 8484: id 0 para que se pueda cachear
        if (lightweight) {
            try {
                return pool!!.use { c -> post(c, body) }.also { Packets.put16(it, 0, id) }
            } catch (e: java.net.ProtocolException) {
                lightweight = false // el servidor no habla HTTP/1.1 como esperamos: OkHttp
            }
        }
        return viaOkHttp(body, id)
    }

    private fun post(c: TlsConn, body: ByteArray): ByteArray {
        val head = "POST $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: DNShield/1.0\r\n" +
            "Accept: $MEDIA\r\nContent-Type: $MEDIA\r\nContent-Length: ${body.size}\r\n\r\n"
        c.output.write(head.toByteArray(Charsets.US_ASCII) + body)
        c.output.flush()
        val status = readLine(c.input) ?: throw IOException("conexion cerrada")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.net.ProtocolException("estado: $status")
        var length = -1
        var chunked = false
        var close = false
        while (true) {
            val line = readLine(c.input) ?: throw IOException("cabeceras cortadas")
            if (line.isEmpty()) break
            val k = line.substringBefore(':').trim().lowercase()
            val v = line.substringAfter(':', "").trim()
            when (k) {
                "content-length" -> length = v.toIntOrNull() ?: -1
                "transfer-encoding" -> chunked = v.contains("chunked", true)
                "connection" -> close = v.equals("close", true)
            }
        }
        val data = when {
            chunked -> readChunked(c.input)
            length >= 0 -> readN(c.input, length)
            else -> throw java.net.ProtocolException("sin longitud")
        }
        // Si el servidor anuncia cierre, se cierra ya: la reserva la descartara y abrira otra la proxima vez.
        if (close) c.close()
        if (code != 200) throw java.net.ProtocolException("HTTP $code")
        if (data.size < 12) throw IOException("respuesta corta")
        return data
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw java.net.ProtocolException("linea demasiado larga")
            sb.append(b.toChar())
        }
    }

    private fun readN(input: java.io.InputStream, n: Int): ByteArray {
        if (n < 0 || n > 65535) throw java.net.ProtocolException("tamano no valido")
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IOException("cuerpo cortado")
            off += r
        }
        return out
    }

    private fun readChunked(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16)
                ?.takeIf { it in 0..65535 } ?: throw java.net.ProtocolException("chunk")
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) { /* trailers */ }
                return out.toByteArray()
            }
            out.write(readN(input, size))
            readLine(input)
            if (out.size() > 65535) throw java.net.ProtocolException("respuesta demasiado grande")
        }
    }

    override fun close() { pool?.close() }

    private fun viaOkHttp(body: ByteArray, id: Int): ByteArray {
        val req = Request.Builder().url(url)
            .header("Accept", MEDIA)
            .post(body.toRequestBody(MEDIA.toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val bytes = r.body?.bytes() ?: throw IOException("sin cuerpo")
            if (bytes.size < 12) throw IOException("respuesta corta")
            Packets.put16(bytes, 0, id)
            return bytes
        }
    }

    companion object {
        private const val MEDIA = "application/dns-message"

        fun client(resolver: HostResolver): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
            .dns(object : Dns {
                override fun lookup(hostname: String) = resolver.resolve(hostname)
            })
            .build()
    }
}

/** DNS-over-TLS (RFC 7858): mensaje con prefijo de longitud sobre una conexion TLS reutilizada. */
class DotTransport(private val host: String, private val port: Int, resolver: HostResolver) : Transport {
    override val label get() = "tls://$host"
    override val protocol = "DoT"
    private val pool = TlsPool(host, port, resolver)

    override fun exchange(query: ByteArray): ByteArray = pool.use { c ->
        c.output.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()) + query)
        c.output.flush()
        val input = DataInputStream(c.input)
        val resp = ByteArray(input.readUnsignedShort())
        input.readFully(resp)
        resp
    }

    override fun close() = pool.close()
}

/**
 * Servidor elegido + reservas. Prueba los del servidor en orden y, si ninguno responde,
 * las reservas (o el DNS del sistema si no se configuro ninguna).
 */
class UpstreamSet(val primary: List<Transport>, private val fallback: () -> List<Transport>, val name: String) {
    class Answer(val bytes: ByteArray, val via: Transport)

    /** Ultimo fallo de cada servidor: uno que acaba de fallar se salta un rato (menos espera, menos bateria). */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun exchange(query: ByteArray): Answer? {
        val now = System.currentTimeMillis()
        val all = primary + fallback()
        val healthy = all.filter { now - (failedAt[it.label] ?: 0L) > PENALTY_MS }
        // Si todos estan penalizados, se prueban igualmente (puede que la red haya vuelto).
        for (t in healthy.ifEmpty { all }) {
            try {
                val r = t.exchange(query)
                failedAt.remove(t.label)
                return Answer(r, t)
            } catch (_: Exception) {
                failedAt[t.label] = System.currentTimeMillis()
            }
        }
        return null
    }

    fun resetHealth() = failedAt.clear()

    fun close() = primary.forEach { it.close() }

    companion object {
        private const val PENALTY_MS = 60_000L

        fun transportFor(spec: UpstreamSpec, resolver: HostResolver, client: () -> OkHttpClient): Transport = when (spec) {
            is UpstreamSpec.Plain -> UdpTransport(spec.host, spec.port)
            is UpstreamSpec.Doh -> DohTransport(spec.url, client(), resolver)
            is UpstreamSpec.Dot -> DotTransport(spec.host, spec.port, resolver)
        }
    }
}
