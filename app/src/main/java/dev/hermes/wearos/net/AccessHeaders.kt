package dev.hermes.wearos.net

import dev.hermes.wearos.domain.DirectGateway
import dev.hermes.wearos.domain.Gateway
import dev.hermes.wearos.domain.Origin
import java.io.IOException
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Per-origin credential policy for generic HTTPS gateways (SPEC §11.7).
 *
 * One network interceptor, installed on every OkHttp client that talks to a gateway. It runs on
 * EVERY hop (network interceptor), which is what makes redirects safe:
 *  - access-layer headers of a direct gateway are attached only when the hop's origin is one of
 *    that gateway's own https origins, and any copy that rode in from an earlier hop is removed first;
 *  - a redirect that leaves the approved origin is refused instead of followed, so a login page of
 *    the access layer (or a hostile `Location`) never receives the headers;
 *  - `Authorization` (the per-profile Hermes key) is dropped on any hop to an origin that is not a
 *    configured gateway origin.
 * TLS stays the platform default: nothing here touches trust managers or hostname verification.
 */
object AccessHeaders {
    private data class Policy(
        val headersByOrigin: Map<Origin, Map<String, String>>,
        val directOrigins: Set<Origin>,
        val knownOrigins: Set<Origin>,
        val names: Set<String>,
    )

    @Volatile private var policy = Policy(emptyMap(), emptySet(), emptySet(), emptySet())

    fun apply(gateways: List<Gateway>) {
        val byOrigin = HashMap<Origin, Map<String, String>>()
        val known = HashSet<Origin>()
        val names = HashSet<String>()
        for (gw in gateways) {
            (gw.apiEndpoints + gw.watchkitEndpoints).mapNotNull { DirectGateway.originOf(it) }.forEach { known += it }
            if (!gw.direct) continue
            gw.accessHeaders.keys.forEach { names += it.lowercase(Locale.ROOT) }
            val own = DirectGateway.origins(gw)
            own.forEach { byOrigin[it] = gw.accessHeaders }
        }
        val direct = gateways.filter { it.direct }.flatMap { DirectGateway.origins(it) }.toSet()
        policy = Policy(byOrigin, direct, known, names)
        TailnetRoute.directOrigins = direct
    }

    fun isDirect(origin: Origin?): Boolean = origin != null && origin in policy.directOrigins

    val INTERCEPTOR = Interceptor { chain -> intercept(chain) }

    private fun intercept(chain: Interceptor.Chain): Response {
        val p = policy
        val req = chain.request()
        val origin = DirectGateway.originOf(req.url.toString())
        val builder = req.newBuilder()
        p.names.forEach { builder.removeHeader(it) }
        val headers = origin?.let { p.headersByOrigin[it] }
        headers?.forEach { (k, v) -> builder.header(k, v) }
        if (origin == null || origin !in p.knownOrigins) builder.removeHeader("Authorization")
        val res = chain.proceed(builder.build())
        if (headers != null && headers.isNotEmpty() && res.isRedirect) {
            val target = res.header("Location")?.let { req.url.resolve(it) }?.let { DirectGateway.originOf(it.toString()) }
            if (target != origin) {
                res.close()
                throw IOException("refused redirect to another origin — access headers are only sent to the gateway's own origin")
            }
        }
        return res
    }
}
