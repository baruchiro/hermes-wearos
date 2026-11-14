package dev.hermes.wearos.domain

import java.net.URI
import java.util.Locale

/** scheme + host + port: the unit a secret header is allowed to travel to. */
data class Origin(val scheme: String, val host: String, val port: Int) {
    val isHttps: Boolean get() = scheme == "https"
}

/**
 * Generic HTTPS gateway rules (no tailnet, no pairing lane): what a direct endpoint must look like
 * and which access-layer headers may be configured. Pure and JVM-tested (GatewayModelTest).
 *
 * The Hermes bearer (`Authorization`, one per profile) and the access layer (Cloudflare Access,
 * an nginx auth shim, …) are two different credentials. They never share a header: configuring
 * `Authorization` as an access header is refused outright instead of silently overriding either side.
 */
object DirectGateway {
    const val MAX_HEADERS = 8
    const val MAX_VALUE_LENGTH = 2048

    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    private val RESERVED = setOf(
        "authorization", "proxy-authorization", "host", "content-length", "content-type",
        "content-encoding", "transfer-encoding", "connection", "upgrade", "te", "trailer",
        "expect", "accept", "idempotency-key", "last-event-id", "range", "user-agent",
    )

    fun originOf(url: String): Origin? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = u.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port >= 0) u.port else when (scheme) { "https" -> 443; "http" -> 80; else -> return null }
        return Origin(scheme, host, port)
    }

    /** Bare `host` / `host:port` input means https here (the tailnet default of http:8642 does not apply). */
    fun normalizeEndpoint(raw: String): String {
        val u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        val withScheme = if (u.contains("://")) u else "https://$u"
        val cut = withScheme.indexOf("/p/", withScheme.indexOf("://") + 3)
        return if (cut < 0) withScheme else withScheme.substring(0, cut).trimEnd('/')
    }

    fun validateEndpoint(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "invalid URL"
        if (u.scheme?.lowercase(Locale.ROOT) != "https") return "direct gateways require https://"
        if (u.host.isNullOrEmpty()) return "URL has no host"
        if (u.rawUserInfo != null) return "credentials in the URL are not allowed"
        if (u.rawQuery != null || u.rawFragment != null) return "URL must not carry a query or fragment"
        return null
    }

    fun validateHeaders(headers: Map<String, String>): String? {
        if (headers.size > MAX_HEADERS) return "at most $MAX_HEADERS access headers"
        val seen = HashSet<String>()
        for ((rawName, value) in headers) {
            val name = rawName.trim()
            val lower = name.lowercase(Locale.ROOT)
            if (!TOKEN.matches(name)) return "invalid header name '$name'"
            if (lower == "authorization" || lower == "proxy-authorization")
                return "'$name' is reserved for the per-profile Hermes key — use a different access header name"
            if (lower in RESERVED || lower.startsWith("sec-websocket-")) return "header '$name' is reserved"
            if (!seen.add(lower)) return "duplicate header '$name'"
            if (value.isEmpty()) return "header '$name' has an empty value"
            if (value.length > MAX_VALUE_LENGTH) return "header '$name' value too long"
            if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7f }) return "header '$name' value has control characters"
        }
        return null
    }

    /** First problem with [gw], or null. Tailnet gateways may not carry access headers. */
    fun validate(gw: Gateway): String? {
        if (!gw.direct) return if (gw.accessHeaders.isEmpty()) null else "access headers need a direct (https) gateway"
        (gw.apiEndpoints + gw.watchkitEndpoints).forEach { ep -> validateEndpoint(ep)?.let { return "$ep: $it" } }
        return validateHeaders(gw.accessHeaders)
    }

    fun origins(gw: Gateway): Set<Origin> =
        if (!gw.direct) emptySet()
        else (gw.apiEndpoints + gw.watchkitEndpoints).mapNotNull { originOf(it) }.filter { it.isHttps }.toSet()

    /** True when every configured gateway is direct: nothing needs the embedded tunnel. */
    fun tunnelNotNeeded(gateways: List<Gateway>): Boolean = gateways.isNotEmpty() && gateways.all { it.direct }
}
