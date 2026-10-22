package dev.dnshield.app.engine

/** Servidor DNS predefinido con sus tres formas de hablarle: DNS normal, DoH y DoT. */
class DnsServer(
    val id: String,
    val name: String,
    val description: String,
    val plain: List<String>,
    val doh: String?,
    val dot: String?,
    val defaultProtocol: String = "doh",
) {
    fun address(proto: String): List<String> = when (proto) {
        "doh" -> listOfNotNull(doh).ifEmpty { plain }
        "dot" -> listOfNotNull(dot).ifEmpty { plain }
        else -> plain
    }

    val protocols: List<String> get() = listOfNotNull("plain".takeIf { plain.isNotEmpty() }, "doh".takeIf { doh != null }, "dot".takeIf { dot != null })
}

object DnsServers {
    const val SYSTEM_ID = "system"
    const val DEFAULT_ID = "cloudflare"

    val PRESETS = listOf(
        DnsServer(SYSTEM_ID, "Predeterminado del sistema",
            "Los servidores que da la red (tu operador o el router). Sin cifrar.", emptyList(), null, null, "plain"),
        DnsServer("adguard", "AdGuard DNS", "Servicio AdGuard DNS que bloquea anuncios y rastreadores",
            listOf("94.140.14.14", "94.140.15.15"), "https://dns.adguard-dns.com/dns-query", "tls://dns.adguard-dns.com"),
        DnsServer("adguard_family", "AdGuard DNS Protección familiar",
            "AdGuard DNS que además bloquea contenido para adultos y fuerza la búsqueda segura",
            listOf("94.140.14.15", "94.140.15.16"), "https://family.adguard-dns.com/dns-query", "tls://family.adguard-dns.com"),
        DnsServer("adguard_unfiltered", "AdGuard DNS Sin filtrado", "Conexión segura y fiable, pero no filtra nada",
            listOf("94.140.14.140", "94.140.14.141"), "https://unfiltered.adguard-dns.com/dns-query", "tls://unfiltered.adguard-dns.com"),
        DnsServer("google", "Google DNS", "Servicio DNS ofrecido por Google",
            listOf("8.8.8.8", "8.8.4.4"), "https://dns.google/dns-query", "tls://dns.google"),
        DnsServer("cloudflare", "Cloudflare DNS", "Servicio DNS público ofrecido por Cloudflare",
            listOf("1.1.1.1", "1.0.0.1"), "https://cloudflare-dns.com/dns-query", "tls://one.one.one.one"),
        DnsServer("cloudflare_malware", "Cloudflare para familias – Malware", "Bloquea malware, ofrecido por Cloudflare",
            listOf("1.1.1.2", "1.0.0.2"), "https://security.cloudflare-dns.com/dns-query", "tls://security.cloudflare-dns.com"),
        DnsServer("cloudflare_family", "Cloudflare para familias – Malware y adultos",
            "Bloquea tanto malware como contenido para adultos",
            listOf("1.1.1.3", "1.0.0.3"), "https://family.cloudflare-dns.com/dns-query", "tls://family.cloudflare-dns.com"),
        DnsServer("opendns", "OpenDNS", "Bloquea malware y phishing, ofrecido por Cisco",
            listOf("208.67.222.222", "208.67.220.220"), "https://doh.opendns.com/dns-query", null),
        DnsServer("opendns_family", "OpenDNS con escudo familiar", "Bloquea contenido para adultos, ofrecido por Cisco",
            listOf("208.67.222.123", "208.67.220.123"), "https://doh.familyshield.opendns.com/dns-query", null),
        DnsServer("quad9", "Quad9", "Bloquea dominios maliciosos",
            listOf("9.9.9.9", "149.112.112.112"), "https://dns.quad9.net/dns-query", "tls://dns.quad9.net"),
        DnsServer("quad9_unsecured", "Quad9 sin filtrado", "Quad9 sin bloqueo de dominios maliciosos",
            listOf("9.9.9.10", "149.112.112.10"), "https://dns10.quad9.net/dns-query", "tls://dns10.quad9.net"),
        DnsServer("quad9_ecs", "Quad9 con ECS", "Bloquea malware y envía tu zona aproximada para CDN más cercanas",
            listOf("9.9.9.11", "149.112.112.11"), "https://dns11.quad9.net/dns-query", "tls://dns11.quad9.net"),
        DnsServer("cleanbrowsing_security", "CleanBrowsing Seguridad", "Bloquea phishing, malware y dominios maliciosos",
            listOf("185.228.168.9", "185.228.169.9"), "https://doh.cleanbrowsing.org/doh/security-filter/",
            "tls://security-filter-dns.cleanbrowsing.org"),
        DnsServer("cleanbrowsing_adult", "CleanBrowsing Adultos", "Bloquea contenido para adultos y fuerza la búsqueda segura",
            listOf("185.228.168.10", "185.228.169.11"), "https://doh.cleanbrowsing.org/doh/adult-filter/",
            "tls://adult-filter-dns.cleanbrowsing.org"),
        DnsServer("cleanbrowsing_family", "CleanBrowsing Familia", "Bloquea adultos, proxies y VPN, y fuerza la búsqueda segura",
            listOf("185.228.168.168", "185.228.169.168"), "https://doh.cleanbrowsing.org/doh/family-filter/",
            "tls://family-filter-dns.cleanbrowsing.org"),
        DnsServer("controld_unfiltered", "Control D Sin filtrado", "Resolución rápida sin bloquear nada",
            listOf("76.76.2.0", "76.76.10.0"), "https://freedns.controld.com/p0", "tls://p0.freedns.controld.com"),
        DnsServer("controld_malware", "Control D Malware", "Bloquea malware y phishing",
            listOf("76.76.2.1", "76.76.10.1"), "https://freedns.controld.com/p1", "tls://p1.freedns.controld.com"),
        DnsServer("controld_ads", "Control D Anuncios y rastreo", "Bloquea anuncios, rastreadores y malware",
            listOf("76.76.2.2", "76.76.10.2"), "https://freedns.controld.com/p2", "tls://p2.freedns.controld.com"),
        DnsServer("controld_social", "Control D Redes sociales", "Lo anterior y además las redes sociales",
            listOf("76.76.2.3", "76.76.10.3"), "https://freedns.controld.com/p3", "tls://p3.freedns.controld.com"),
        DnsServer("mullvad", "Mullvad DNS", "Sin registros y sin filtrado. Solo cifrado",
            emptyList(), "https://dns.mullvad.net/dns-query", "tls://dns.mullvad.net"),
        DnsServer("mullvad_adblock", "Mullvad Anuncios", "Mullvad bloqueando anuncios y rastreadores",
            emptyList(), "https://adblock.dns.mullvad.net/dns-query", "tls://adblock.dns.mullvad.net"),
        DnsServer("mullvad_base", "Mullvad Base", "Anuncios, rastreadores y malware",
            emptyList(), "https://base.dns.mullvad.net/dns-query", "tls://base.dns.mullvad.net"),
        DnsServer("mullvad_family", "Mullvad Familia", "Base más contenido para adultos y apuestas",
            emptyList(), "https://family.dns.mullvad.net/dns-query", "tls://family.dns.mullvad.net"),
        DnsServer("dnssb", "DNS.SB", "Sin registros, con DNSSEC",
            listOf("185.222.222.222", "45.11.45.11"), "https://doh.dns.sb/dns-query", "tls://dot.sb"),
        DnsServer("comodo", "Comodo Secure DNS", "Bloquea sitios de phishing y malware", listOf("8.26.56.26", "8.20.247.20"),
            null, null, "plain"),
        DnsServer("alidns", "AliDNS", "Servicio DNS público de Alibaba",
            listOf("223.5.5.5", "223.6.6.6"), "https://dns.alidns.com/dns-query", "tls://dns.alidns.com"),
    )

    fun byId(id: String) = PRESETS.firstOrNull { it.id == id }

    fun protocolLabel(p: String) = when (p) { "doh" -> "DNS-over-HTTPS"; "dot" -> "DNS-over-TLS"; else -> "DNS normal" }
}
