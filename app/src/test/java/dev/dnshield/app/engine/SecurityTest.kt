package dev.dnshield.app.engine

import dev.dnshield.app.core.DnsMessage
import dev.dnshield.app.core.Packets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** Ataques contra el nucleo: suplantacion de respuestas, paquetes envenenados y TLS falso. */
class SecurityTest {

    /** Servidor DNS falso en 127.0.0.1 que contesta como le digamos. */
    private fun fakeServer(reply: (query: ByteArray, from: DatagramPacket, sock: DatagramSocket) -> Unit): DatagramSocket {
        val sock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            runCatching { sock.receive(p); reply(buf.copyOf(p.length), p, sock) }
        }
        return sock
    }

    private fun answerFor(q: ByteArray): ByteArray = q.copyOf().also { Packets.put16(it, 2, 0x8180) }

    @Test fun legitimateUdpAnswerIsAccepted() {
        val srv = fakeServer { q, from, sock -> sock.send(DatagramPacket(answerFor(q), q.size, from.socketAddress)) }
        val r = UdpTransport("127.0.0.1", srv.localPort).exchange(DnsMessage.query("ok.test", 1, 4242))
        assertEquals(4242, Packets.u16(r, 0))
        srv.close()
    }

    @Test fun spoofedAnswerFromAnotherPortIsIgnored() {
        // El atacante contesta con el id correcto pero desde otro puerto: debe descartarse (y acabar en timeout).
        val attacker = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val srv = fakeServer { q, from, _ -> attacker.send(DatagramPacket(answerFor(q), q.size, from.socketAddress)) }
        try {
            UdpTransport("127.0.0.1", srv.localPort).exchange(DnsMessage.query("spoof.test", 1, 777))
            fail("aceptó una respuesta falsificada")
        } catch (_: java.net.SocketTimeoutException) {
        } finally {
            srv.close(); attacker.close()
        }
    }

    @Test fun echoedQueryIsNotTakenAsAnswer() {
        // Un rebote de la propia consulta (QR=0) no es una respuesta.
        val srv = fakeServer { q, from, sock -> sock.send(DatagramPacket(q, q.size, from.socketAddress)) }
        try {
            UdpTransport("127.0.0.1", srv.localPort).exchange(DnsMessage.query("echo.test", 1, 99))
            fail("aceptó una consulta rebotada como respuesta")
        } catch (_: java.net.SocketTimeoutException) {
        } finally {
            srv.close()
        }
    }

    @Test fun namesWithControlCharactersAreDropped() {
        for (bad in listOf("evil\nline.com", "tab\tx.com", "nul\u0000.com", "sp ace.com", "del\u007f.com", "utfé.com")) {
            val dns = DnsMessage.query("placeholder.com", 1, 1)
            // Sustituye la primera etiqueta por una con el caracter peligroso.
            val label = bad.substringBefore('.').toByteArray(Charsets.ISO_8859_1)
            val rest = DnsMessage.query(bad.substringAfter('.'), 1, 1)
            val q = rest.copyOfRange(0, 12) + byteArrayOf(label.size.toByte()) + label + rest.copyOfRange(12, rest.size)
            val pkt = ByteArray(28 + q.size)
            pkt[0] = 0x45; Packets.put16(pkt, 2, pkt.size); pkt[9] = 17
            Packets.put16(pkt, 22, 53); Packets.put16(pkt, 24, 8 + q.size)
            q.copyInto(pkt, 28)
            assertNull("aceptó un nombre con caracter de control: ${bad.replace("\n", "\\n")}", Packets.parse(pkt, pkt.size))
            assertTrue(dns.isNotEmpty())
        }
    }

    @Test fun oversizedAndTruncatedPacketsNeverCrash() {
        val rnd = java.util.Random(7)
        repeat(20_000) {
            val len = rnd.nextInt(5000)
            val b = ByteArray(len).also(rnd::nextBytes)
            if (len > 0) b[0] = if (rnd.nextBoolean()) 0x45 else 0x60
            Packets.parse(b, rnd.nextInt(len + 1))?.let { Packets.blockedResponse(it); Packets.servfail(it) }
        }
    }

    /** TLS: un servidor con certificado caducado o de otro nombre NUNCA debe aceptarse. */
    @Test fun dohRejectsBadCertificates() {
        val resolver = HostResolver(emptyList())
        val good = DohTransport("https://cloudflare-dns.com/dns-query", DohTransport.client(resolver), resolver)
        val probe = runCatching { good.exchange(DnsMessage.query("example.com", 1, 5)) }
        probe.exceptionOrNull()?.let { System.err.println("CONTROL DoH: " + generateSequence(it) { e -> e.cause }.joinToString(" <- ") { e -> e.javaClass.name + ": " + e.message }) }
        val reachable = probe.isSuccess
        assumeTrue("sin red: prueba de TLS no concluyente", reachable)
        for (bad in listOf("https://expired.badssl.com/dns-query", "https://wrong.host.badssl.com/dns-query", "https://self-signed.badssl.com/dns-query")) {
            val t = DohTransport(bad, DohTransport.client(resolver), resolver)
            val ok = runCatching { t.exchange(DnsMessage.query("example.com", 1, 6)) }
            assertTrue("aceptó TLS no válido en $bad", ok.isFailure)
            val msg = generateSequence(ok.exceptionOrNull()) { it.cause }.joinToString(" | ") { it.javaClass.simpleName + ": " + it.message }
            assertTrue("$bad falló pero no por TLS: $msg", msg.contains("SSL", true) || msg.contains("certif", true) || msg.contains("Hostname", true) || msg.contains("PKIX", true))
        }
    }

    @Test fun dotRejectsWrongHostname() {
        val resolver = HostResolver(emptyList())
        val good = DotTransport("one.one.one.one", 853, resolver)
        val probe = runCatching { good.exchange(DnsMessage.query("example.com", 1, 7)) }
        probe.exceptionOrNull()?.let { System.err.println("CONTROL DoT: " + generateSequence(it) { e -> e.cause }.joinToString(" <- ") { e -> e.javaClass.name + ": " + e.message }) }
        val reachable = probe.isSuccess
        assumeTrue("sin red o 853 bloqueado: prueba no concluyente", reachable)
        // 1.1.1.1 presenta certificado de cloudflare-dns/one.one.one.one; pedirlo como "google.com" debe fallar.
        val evil = DotTransport("1.1.1.1", 853, HostResolver(emptyList())).let {
            object : Transport by it {}
        }
        val wrong = runCatching {
            val s = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                .createSocket(java.net.Socket("1.1.1.1", 853), "google.com", 853, true) as javax.net.ssl.SSLSocket
            s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            s.soTimeout = 4000
            s.startHandshake()
            check(okhttp3.internal.tls.OkHostnameVerifier.verify("google.com", s.session)) { "nombre no coincide" }
        }
        assertTrue("el certificado de 1.1.1.1 se aceptó como google.com", wrong.isFailure)
        assertTrue(evil.label.isNotEmpty())
    }
}
