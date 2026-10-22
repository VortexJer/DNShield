package dev.dnshield.app.core

/**
 * Decision final para un dominio. Las reglas del usuario (lista de bloqueo + lista de permitidos)
 * forman su propia capa y ganan siempre a los filtros descargados, como en AdGuard.
 */
object Filter {
    enum class Result { BLOCKED_LIST, BLOCKED_USER, ALLOWED_USER, PASSED }

    fun decide(domain: String, user: Blocklist, lists: Blocklist): Result {
        when (user.verdict(domain)) {
            Blocklist.Verdict.ALLOWED -> return Result.ALLOWED_USER
            Blocklist.Verdict.BLOCKED -> return Result.BLOCKED_USER
            Blocklist.Verdict.NONE -> {}
        }
        return if (lists.verdict(domain) == Blocklist.Verdict.BLOCKED) Result.BLOCKED_LIST else Result.PASSED
    }

    /**
     * Capa del usuario. En la lista de bloqueo vale la sintaxis de AdGuard ("||x.com^", "@@||x.com^",
     * "0.0.0.0 x.com") y tambien un dominio suelto, que se entiende como "x.com y sus subdominios".
     * Cada dominio de la lista de permitidos es una excepcion con subdominios.
     */
    fun buildUserLayer(blockRules: Iterable<String>, allowDomains: Iterable<String>): Blocklist {
        val b = BlocklistBuilder()
        for (raw in blockRules) {
            val r = raw.trim()
            val plain = BlocklistBuilder.normalize(r)
            b.addLine(if (plain != null) "||$plain^" else r)
        }
        for (d in allowDomains) BlocklistBuilder.normalize(d)?.let { b.addLine("@@||$it^") }
        return b.build()
    }

    /** true si la linea es una regla que el filtro entiende (para avisar al escribirla). */
    fun isValidUserRule(raw: String): Boolean {
        val r = raw.trim()
        if (BlocklistBuilder.normalize(r) != null) return true
        val b = BlocklistBuilder()
        b.addLine(r)
        return b.accepted == 1
    }
}
