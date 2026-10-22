package dev.dnshield.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlocklistTest {
    private fun build(vararg lines: String) = BlocklistBuilder().apply { lines.forEach(::addLine) }.build()

    @Test fun adblockRuleBlocksDomainAndSubdomains() {
        val l = build("||ads.example.com^")
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("ads.example.com"))
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("x.y.ads.example.com"))
        assertEquals(Blocklist.Verdict.NONE, l.verdict("example.com"))
        assertEquals(Blocklist.Verdict.NONE, l.verdict("notads.example.com"))
    }

    @Test fun hostsRuleIsExactOnly() {
        val l = build("0.0.0.0 tracker.net", "127.0.0.1 a.b.c  # comentario")
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("tracker.net"))
        assertEquals(Blocklist.Verdict.NONE, l.verdict("sub.tracker.net"))
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("a.b.c"))
    }

    @Test fun exceptionBeatsBlock() {
        val l = build("||example.com^", "@@||good.example.com^")
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("bad.example.com"))
        assertEquals(Blocklist.Verdict.ALLOWED, l.verdict("good.example.com"))
        assertEquals(Blocklist.Verdict.ALLOWED, l.verdict("cdn.good.example.com"))
    }

    @Test fun commentsModifiersWildcardsAndRegexAreSkipped() {
        val b = BlocklistBuilder()
        listOf(
            "! comentario", "# otro", "[Adblock Plus 2.0]", "",
            "||ok.com^\$important", "||dns.com^\$dnstype=AAAA", "||*.wild.com^", "/ads[0-9]+\\.com/",
            "||path.com/ads^", "||port.com:8080^", "localhost", "0.0.0.0 localhost",
        ).forEach(b::addLine)
        val l = b.build()
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("ok.com"))
        assertEquals(Blocklist.Verdict.NONE, l.verdict("dns.com"))
        assertEquals(Blocklist.Verdict.NONE, l.verdict("localhost"))
        assertEquals(1, b.accepted)
        assertTrue(b.skipped >= 5)
    }

    @Test fun plainDomainLines() {
        val l = build("plain.org", "UPPER.Example.ORG.")
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("plain.org"))
        assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("upper.example.org"))
    }

    @Test fun serializeRoundTrip() {
        val l = build("||a.com^", "0.0.0.0 b.com", "@@||ok.a.com^")
        val back = Blocklist.deserialize(l.serialize())
        assertNotNull(back)
        assertEquals(l.ruleCount, back!!.ruleCount)
        for (d in listOf("a.com", "x.a.com", "b.com", "x.b.com", "ok.a.com", "c.com")) {
            assertEquals(d, l.verdict(d), back.verdict(d))
        }
    }

    @Test fun deserializeRejectsGarbage() {
        assertNull(Blocklist.deserialize(ByteArray(3)))
        assertNull(Blocklist.deserialize(ByteArray(64) { 7 }))
        val good = build("||a.com^").serialize()
        assertNull(Blocklist.deserialize(good.copyOf(good.size - 4)))
    }

    @Test fun duplicatesCollapse() {
        assertEquals(1, build("||a.com^", "||a.com^", "||A.com^").ruleCount)
    }

    @Test fun largeListStaysFastAndCorrect() {
        val b = BlocklistBuilder()
        repeat(200_000) { b.addLine("||host$it.ads.net^") }
        val l = b.build()
        val t0 = System.nanoTime()
        repeat(100_000) { assertEquals(Blocklist.Verdict.BLOCKED, l.verdict("cdn.host${it * 2}.ads.net")) }
        repeat(100_000) { assertEquals(Blocklist.Verdict.NONE, l.verdict("www.site$it.com")) }
        val perLookupUs = (System.nanoTime() - t0) / 200_000.0 / 1000
        assertTrue("lookup too slow: $perLookupUs us", perLookupUs < 20)
    }
}

class UserRulesTest {
    @Test fun userRulesOverrideLists() {
        val list = BlocklistBuilder().apply { addLine("||ads.com^"); addLine("@@||cdn.lists-allow.com^"); addLine("||lists-allow.com^") }.build()
        val user = Filter.buildUserLayer(blockRules = listOf("mine.org", "||cdn.lists-allow.com^"), allowDomains = listOf("good.ads.com"))
        assertEquals(Filter.Result.ALLOWED_USER, Filter.decide("good.ads.com", user, list))
        assertEquals(Filter.Result.ALLOWED_USER, Filter.decide("x.good.ads.com", user, list))
        assertEquals(Filter.Result.BLOCKED_LIST, Filter.decide("other.ads.com", user, list))
        assertEquals(Filter.Result.BLOCKED_USER, Filter.decide("sub.mine.org", user, list))
        assertEquals(Filter.Result.PASSED, Filter.decide("example.com", user, list))
        // la regla del usuario gana a la excepcion de la lista
        assertEquals(Filter.Result.BLOCKED_USER, Filter.decide("cdn.lists-allow.com", user, list))
        assertEquals(Filter.Result.BLOCKED_LIST, Filter.decide("x.lists-allow.com", user, list))
    }

    @Test fun suffixMatchRespectsLabelBoundaries() {
        val user = Filter.buildUserLayer(listOf("ads.com"), emptyList())
        val none = Blocklist.EMPTY
        assertEquals(Filter.Result.BLOCKED_USER, Filter.decide("ads.com", user, none))
        assertEquals(Filter.Result.BLOCKED_USER, Filter.decide("a.ads.com", user, none))
        assertEquals(Filter.Result.PASSED, Filter.decide("badads.com", user, none))
    }

    @Test fun userRuleSyntaxValidation() {
        assertTrue(Filter.isValidUserRule("example.com"))
        assertTrue(Filter.isValidUserRule("||ads.example.com^"))
        assertTrue(Filter.isValidUserRule("@@||ok.example.com^"))
        assertTrue(Filter.isValidUserRule("0.0.0.0 tracker.io"))
        assertFalse(Filter.isValidUserRule("||ads.*.com^"))
        assertFalse(Filter.isValidUserRule("no es una regla"))
        assertFalse(Filter.isValidUserRule("! comentario"))
    }
}

class DnsMessageTest {
    private fun response(query: ByteArray, rcode: Int, vararg answers: Pair<Int, ByteArray>): ByteArray {
        var msg = query.copyOf()
        Packets.put16(msg, 2, 0x8180 or rcode)
        Packets.put16(msg, 6, answers.size)
        for ((type, rdata) in answers) {
            val rr = ByteArray(12 + rdata.size)
            Packets.put16(rr, 0, 0xC00C); Packets.put16(rr, 2, type); Packets.put16(rr, 4, 1)
            Packets.put16(rr, 8, 60); Packets.put16(rr, 10, rdata.size)
            rdata.copyInto(rr, 12)
            msg += rr
        }
        return msg
    }

    @Test fun summarizesAddressAnswers() {
        val q = DnsMessage.query("example.com", 1, 5)
        val r = response(q, 0, 1 to byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        assertEquals("A 93.184.216.34", DnsMessage.summarize(r))
        assertEquals(1, DnsMessage.answers(r).size)
    }

    @Test fun summarizesCnameChainAndPrefersAddress() {
        val q = DnsMessage.query("www.x.com", 1, 6)
        // CNAME apuntando a "cdn" + puntero al nombre de la pregunta (offset 12)
        val cname = byteArrayOf(3, 'c'.code.toByte(), 'd'.code.toByte(), 'n'.code.toByte(), 0xC0.toByte(), 12)
        val r = response(q, 0, 5 to cname, 1 to byteArrayOf(1, 2, 3, 4))
        assertEquals("A 1.2.3.4", DnsMessage.summarize(r))
        assertEquals("cdn.www.x.com", DnsMessage.answers(r)[0].data)
    }

    @Test fun summarizesErrorsAndGarbage() {
        val q = DnsMessage.query("nope.example", 1, 7)
        assertEquals("NXDOMAIN", DnsMessage.summarize(response(q, 3)))
        assertEquals("sin datos", DnsMessage.summarize(response(q, 0)))
        assertEquals("sin respuesta", DnsMessage.summarize(ByteArray(5)))
        val rnd = Random(1)
        repeat(20_000) { DnsMessage.summarize(ByteArray(rnd.nextInt(0, 120)).also(rnd::nextBytes)) }
    }

    @Test fun parsesUpstreams() {
        assertEquals(UpstreamSpec.Plain("1.1.1.1"), UpstreamSpec.parse("1.1.1.1"))
        assertEquals(UpstreamSpec.Plain("9.9.9.9", 5353), UpstreamSpec.parse("udp://9.9.9.9:5353"))
        assertEquals(UpstreamSpec.Plain("2606:4700:4700::1111"), UpstreamSpec.parse("2606:4700:4700::1111"))
        assertEquals(UpstreamSpec.Plain("2620:fe::fe", 53), UpstreamSpec.parse("[2620:fe::fe]:53"))
        val doh = UpstreamSpec.parse("https://dns.adguard-dns.com/dns-query") as UpstreamSpec.Doh
        assertEquals("dns.adguard-dns.com", doh.host)
        assertEquals("DoH", doh.protocol)
        assertEquals(UpstreamSpec.Dot("dns.quad9.net"), UpstreamSpec.parse("tls://dns.quad9.net"))
        assertNull(UpstreamSpec.parse("quic://x.com"))
        assertNull(UpstreamSpec.parse("https://"))
        assertNull(UpstreamSpec.parse("1.1.1.1:99999"))
        assertNull(UpstreamSpec.parse("hola mundo"))
    }
}

class PacketsTest {
    private val client4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns4 = byteArrayOf(10, 111, 222.toByte(), 2)

    /** Consulta DNS real dentro de IPv4/UDP, como la escribe Android en el tunel. */
    private fun query4(name: String, qtype: Int, id: Int = 0x1234, srcPort: Int = 40000): ByteArray {
        val dns = dnsQuery(name, qtype, id)
        val pkt = ByteArray(20 + 8 + dns.size)
        pkt[0] = 0x45
        Packets.put16(pkt, 2, pkt.size)
        pkt[8] = 64; pkt[9] = 17
        client4.copyInto(pkt, 12); dns4.copyInto(pkt, 16)
        Packets.put16(pkt, 10, Packets.checksum(pkt, 0, 20, 0))
        Packets.put16(pkt, 20, srcPort); Packets.put16(pkt, 22, 53); Packets.put16(pkt, 24, 8 + dns.size)
        dns.copyInto(pkt, 28)
        return pkt
    }

    private fun query6(name: String, qtype: Int): ByteArray {
        val dns = dnsQuery(name, qtype, 0x4321)
        val pkt = ByteArray(40 + 8 + dns.size)
        pkt[0] = 0x60
        Packets.put16(pkt, 4, 8 + dns.size)
        pkt[6] = 17; pkt[7] = 64
        val src = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        src.copyInto(pkt, 8); dst.copyInto(pkt, 24)
        Packets.put16(pkt, 40, 50000); Packets.put16(pkt, 42, 53); Packets.put16(pkt, 44, 8 + dns.size)
        dns.copyInto(pkt, 48)
        return pkt
    }

    private fun dnsQuery(name: String, qtype: Int, id: Int): ByteArray {
        val labels = name.split('.')
        val q = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        Packets.put16(q, 0, id)
        Packets.put16(q, 2, 0x0100) // RD
        Packets.put16(q, 4, 1)
        var p = 12
        for (l in labels) { q[p++] = l.length.toByte(); l.toByteArray().copyInto(q, p); p += l.length }
        q[p++] = 0
        Packets.put16(q, p, qtype); Packets.put16(q, p + 2, 1)
        return q
    }

    @Test fun parsesIpv4Query() {
        val q = Packets.parse(query4("Ads.Example.COM", Packets.TYPE_A).let { it.copyOf(it.size + 50) }, query4("Ads.Example.COM", 1).size)
        assertNotNull(q)
        assertEquals("ads.example.com", q!!.name)
        assertEquals(Packets.TYPE_A, q.qtype)
        assertEquals(0x1234, q.dnsId)
    }

    @Test fun rejectsNonDns() {
        val p = query4("a.com", 1)
        Packets.put16(p, 22, 80) // puerto destino 80
        assertNull(Packets.parse(p, p.size))
        val tcp = query4("a.com", 1).also { it[9] = 6 }
        assertNull(Packets.parse(tcp, tcp.size))
        val response = query4("a.com", 1).also { Packets.put16(it, 30, 0x8180) }
        assertNull(Packets.parse(response, response.size))
    }

    @Test fun blockedAResponseIsValid() {
        val raw = query4("ads.example.com", Packets.TYPE_A)
        val q = Packets.parse(raw, raw.size)!!
        val r = Packets.blockedResponse(q)
        // IPv4: direcciones y puertos invertidos, checksum de cabecera correcto
        assertArrayEquals(dns4, r.copyOfRange(12, 16))
        assertArrayEquals(client4, r.copyOfRange(16, 20))
        assertEquals(0, Packets.checksum(r, 0, 20, 0))
        assertEquals(53, Packets.u16(r, 20)); assertEquals(40000, Packets.u16(r, 22))
        assertEquals(r.size - 20, Packets.u16(r, 24))
        assertUdpChecksumValid(r, 20, dns4, client4)
        val d = 28
        assertEquals(0x1234, Packets.u16(r, d))
        val flags = Packets.u16(r, d + 2)
        assertTrue(flags and 0x8000 != 0) // QR
        assertTrue(flags and 0x0100 != 0) // RD copiado
        assertEquals(0, flags and 0xF) // NOERROR
        assertEquals(1, Packets.u16(r, d + 4)); assertEquals(1, Packets.u16(r, d + 6))
        // respuesta: puntero, tipo A, IN, TTL, 4 bytes a cero
        val ans = r.size - 16
        assertEquals(0xC00C, Packets.u16(r, ans))
        assertEquals(Packets.TYPE_A, Packets.u16(r, ans + 2))
        assertEquals(Packets.BLOCK_TTL, Packets.u16(r, ans + 8))
        assertEquals(4, Packets.u16(r, ans + 10))
        assertArrayEquals(ByteArray(4), r.copyOfRange(ans + 12, ans + 16))
    }

    @Test fun blockedOtherTypeIsNxdomain() {
        val raw = query4("ads.example.com", 65) // HTTPS RR
        val r = Packets.blockedResponse(Packets.parse(raw, raw.size)!!)
        assertEquals(3, Packets.u16(r, 30) and 0xF)
        assertEquals(0, Packets.u16(r, 34)) // sin respuestas
    }

    @Test fun blockModes() {
        val raw = query4("ads.example.com", Packets.TYPE_A)
        val q = Packets.parse(raw, raw.size)!!
        val nx = Packets.blockedResponse(q, Packets.BlockOptions(Packets.BlockMode.NXDOMAIN))
        assertEquals(3, Packets.u16(nx, 30) and 0xF)
        val refused = Packets.blockedResponse(q, Packets.BlockOptions(Packets.BlockMode.REFUSED))
        assertEquals(5, Packets.u16(refused, 30) and 0xF)
        val custom = Packets.blockedResponse(q, Packets.BlockOptions(Packets.BlockMode.CUSTOM_IP, ttl = 2,
            customV4 = byteArrayOf(10, 0, 0, 1)))
        assertEquals("A 10.0.0.1", DnsMessage.summarize(custom.copyOfRange(28, custom.size)))
        assertEquals(2, Packets.u16(custom, custom.size - 8))
        val nodata = Packets.noDataResponse(q)
        assertEquals(0, Packets.u16(nodata, 30) and 0xF)
        assertEquals(0, Packets.u16(nodata, 34))
    }

    @Test fun ipv6AaaaBlockedWithValidChecksum() {
        val raw = query6("track.io", Packets.TYPE_AAAA)
        val q = Packets.parse(raw, raw.size)!!
        assertEquals(6, q.ipVersion)
        assertEquals("track.io", q.name)
        val r = Packets.blockedResponse(q)
        assertEquals(0x60, r[0].toInt() and 0xF0)
        assertEquals(r.size - 40, Packets.u16(r, 4))
        assertUdpChecksumValid(r, 40, raw.copyOfRange(24, 40), raw.copyOfRange(8, 24))
        assertEquals(16, Packets.u16(r, r.size - 18))
    }

    @Test fun wrapCarriesUpstreamAnswerVerbatim() {
        val raw = query4("example.com", 1, id = 77, srcPort = 12345)
        val q = Packets.parse(raw, raw.size)!!
        val upstream = q.dnsPayload().also { Packets.put16(it, 2, 0x8180) } + byteArrayOf(1, 2, 3, 4)
        val r = Packets.wrap(q, upstream, upstream.size)
        assertArrayEquals(upstream, r.copyOfRange(28, r.size))
        assertEquals(12345, Packets.u16(r, 22))
        assertEquals(0, Packets.checksum(r, 0, 20, 0))
    }

    @Test fun servfailKeepsIdAndQuestion() {
        val raw = query4("down.net", 1, id = 999)
        val r = Packets.servfail(Packets.parse(raw, raw.size)!!)
        assertEquals(999, Packets.u16(r, 28))
        assertEquals(2, Packets.u16(r, 30) and 0xF)
        assertEquals(1, Packets.u16(r, 32))
    }

    @Test fun fuzzNeverThrows() {
        val rnd = Random(42)
        val base = query4("fuzz.example.com", 1)
        repeat(50_000) {
            val p = if (rnd.nextBoolean()) ByteArray(rnd.nextInt(0, 200)).also(rnd::nextBytes)
            else base.copyOf().also { b -> repeat(rnd.nextInt(1, 6)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() } }
            val q = Packets.parse(p, rnd.nextInt(0, p.size + 1))
            if (q != null) {
                Packets.blockedResponse(q)
                Packets.servfail(q)
            }
        }
    }

    private fun assertUdpChecksumValid(pkt: ByteArray, udpOff: Int, src: ByteArray, dst: ByteArray) {
        val len = pkt.size - udpOff
        var sum = 0L
        for (a in listOf(src, dst)) for (i in a.indices step 2) sum += Packets.u16(a, i)
        sum += 17 + len
        assertEquals("UDP checksum", 0, Packets.checksum(pkt, udpOff, len, sum))
    }
}
