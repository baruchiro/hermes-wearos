package dev.hermes.wearos.net

import dev.hermes.wearos.domain.DirectGateway
import dev.hermes.wearos.domain.Gateway
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape parser for the sessions fast-path (owner ask #2) + the /agents list (ask #1),
 * pinned against the REAL 0.21.7 payload (GET /api/sessions on the live gateway, 2026-10-24).
 */
class GatewaySessionsTest {

    private val sample = """
        {"object":"list","data":[
          {"id":"wear-watch-mvma9on0","source":"api_server","model":"m",
           "title":"what's my current gps location of this watch",
           "started_at":1792839369.855,"ended_at":null,"message_count":1,"tool_call_count":0,
           "last_active":1792839369.657,"preview":"what's my current gps location"},
          {"id":"0f2a9c1e-4b7d","title":"desktop chat","last_active":1792839999.5,"message_count":7},
          {"id":"legacy-no-fields"}
        ]}
    """.trimIndent()

    @Test fun `parses id title last_active and message_count`() {
        val list = GatewaySessions.parse(sample)
        assertEquals(3, list.size)
        val watch = list.first { it.id == "wear-watch-mvma9on0" }
        assertEquals("what's my current gps location of this watch", watch.title)
        assertEquals(1, watch.messages)
        assertEquals(1792839369657L, watch.lastActiveMs) // float unix seconds → whole millis
    }

    @Test fun `sorted newest first`() {
        val list = GatewaySessions.parse(sample)
        assertEquals("0f2a9c1e-4b7d", list.first().id) // last_active 1792839999 > others
        assertEquals(listOf("0f2a9c1e-4b7d", "wear-watch-mvma9on0", "legacy-no-fields"), list.map { it.id })
    }

    @Test fun `missing fields degrade to empty title zero time zero messages`() {
        val legacy = GatewaySessions.parse(sample).last()
        assertEquals("", legacy.title)
        assertEquals(0L, legacy.lastActiveMs)
        assertEquals(0, legacy.messages)
    }

    @Test fun `empty data list parses to empty not crash`() {
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.parse("""{"object":"list","data":[]}"""))
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.parse("""{"object":"list"}"""))
    }

    @Test fun `junk input throws for the caller's cache-vs-error branch`() {
        assertThrows(Exception::class.java) { GatewaySessions.parse("<html>502</html>") }
    }

    @Test fun `non-object entries inside data are skipped`() {
        val list = GatewaySessions.parse("""{"data":[42,{"id":"ok"},null]}""")
        assertEquals(listOf("ok"), list.map { it.id })
    }

    @Test fun `agents list parses dedupes and drops blanks`() {
        val agents = GatewaySessions.parseAgents(
            """{"agents":["default","wear","wear","", 42, "research"]}""",
        )
        // Primitives are read leniently (a directory literally named "42" is legal): dedupe + drop blanks only.
        assertEquals(listOf("default", "wear", "42", "research"), agents)
    }

    @Test fun `agents round-trips the AgentsRepo cache format`() {
        val raw = """{"agents":["default","comfyui","wear"]}"""
        assertTrue(GatewaySessions.parseAgents(raw).contains("comfyui"))
    }

    // ── cross-profile sessions (walkthrough): tagging, merge, filter, cache ─────

    @Test fun `parse reads the watchkit profile field when present`() {
        val list = GatewaySessions.parse(
            """{"data":[{"id":"a","title":"t","last_active":100.0,"message_count":2,"profile":"default"}]}""",
        )
        assertEquals("default", list.single().profile)
        // Gateway rows (no profile key) parse untagged; callers tag via parseProfiled.
        assertEquals("", GatewaySessions.parse(sample).first().profile)
    }

    @Test fun `parseProfiled tags untagged rows but never overwrites a real profile`() {
        val tagged = GatewaySessions.parseProfiled(sample, "wear")
        assertTrue(tagged.all { it.profile == "wear" })
        val mixed = GatewaySessions.parseProfiled(
            """{"data":[{"id":"a","profile":"research"},{"id":"b"}]}""", "wear",
        )
        assertEquals("research", mixed.first { it.id == "a" }.profile)
        assertEquals("wear", mixed.first { it.id == "b" }.profile)
    }

    private fun info(id: String, ms: Long, profile: String = "") =
        GatewaySessions.Info(id, "t", ms, 1, profile)

    // ── ARCHIVED flag (owner batch 5: "archive instead of delete") ────────────
    @Test fun `archived flag parses bool and string and defaults false for old caches`() {
        val rows = GatewaySessions.parse(
            """{"data":[{"id":"a","archived":true},{"id":"b","archived":"true"},""" +
                """{"id":"c","archived":false},{"id":"d"}]}""",
        )
        assertTrue(rows.first { it.id == "a" }.archived)
        assertTrue(rows.first { it.id == "b" }.archived)   // watchkit tags its archived list
        assertFalse(rows.first { it.id == "c" }.archived)
        assertFalse(rows.first { it.id == "d" }.archived)  // pre-archive payload decodes clean
    }

    @Test fun `archived survives the offline-cache encode round-trip`() {
        val rows = listOf(
            GatewaySessions.Info("keep", "t", 1000L, 2, "wear", archived = false),
            GatewaySessions.Info("hidden", "t", 900L, 3, "wear", archived = true),
        )
        val back = GatewaySessions.parse(GatewaySessions.encode(rows))
        assertEquals(listOf(true, false), back.sortedBy { it.id }.map { it.archived })
    }

    @Test fun `archived chip sentinel never collides with a profile name in filtering`() {
        val rows = listOf(info("a", 100L, "wear"))
        // The sentinel selects a SEPARATE list in the screen; if it ever reached filterProfile it
        // must simply match nothing rather than accidentally matching everything or a real agent.
        assertTrue(GatewaySessions.filterProfile(rows, GatewaySessions.FILTER_ARCHIVED).isEmpty())
    }

    @Test fun `merge dedupes by id preferring tagged and fresher rows newest first`() {
        val merged = GatewaySessions.merge(
            listOf(
                info("dup", 500L),                                    // untagged (gateway list)
                info("dup", 400L, "default"),                          // same row, tagged, older stamp
                info("older", 100L, "wear"),
                info("newest", 900L, "default"),
            ),
        )
        assertEquals(listOf("newest", "dup", "older"), merged.map { it.id })
        assertEquals("default", merged.first { it.id == "dup" }.profile) // tagged wins over untagged
    }

    @Test fun `merge keeps the freshest stamp of duplicate ids`() {
        val merged = GatewaySessions.merge(
            listOf(info("x", 100L, "default"), info("x", 800L, "default")),
        )
        assertEquals(800L, merged.single().lastActiveMs)
    }

    @Test fun `merge caps to 30 rows — the owner's get-out-of-hand guard`() {
        val many = (1..80).map { info("s$it", it.toLong()) }
        val merged = GatewaySessions.merge(many)
        assertEquals(GatewaySessions.MAX_ROWS, merged.size)
        assertEquals("s80", merged.first().id) // freshest kept
        assertEquals("s51", merged.last().id)  // oldest 50 dropped
        assertEquals(3, GatewaySessions.merge(many, cap = 3).size)
        assertEquals(GatewaySessions.merge(many, cap = 999).size, GatewaySessions.MAX_ROWS) // cap clamps
    }

    @Test fun `filterProfile powers the all and per-profile chips`() {
        val rows = listOf(info("a", 300L, "wear"), info("b", 200L, "default"), info("c", 100L, "wear"))
        assertEquals(3, GatewaySessions.filterProfile(rows, GatewaySessions.FILTER_ALL).size)
        assertEquals(listOf("a", "c"), GatewaySessions.filterProfile(rows, "wear").map { it.id })
        assertEquals(listOf("b"), GatewaySessions.filterProfile(rows, "default").map { it.id })
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.filterProfile(rows, "ghost"))
    }

    @Test fun `profilesIn lists profiles in newest-seen order without duplicates`() {
        val rows = listOf(info("a", 300L, "wear"), info("b", 200L, "default"), info("c", 100L, "wear"), info("d", 50L))
        assertEquals(listOf("wear", "default"), GatewaySessions.profilesIn(rows))
    }

    @Test fun `encode caches the merged list and parses back identically`() {
        val rows = listOf(
            GatewaySessions.Info("wear-watch-x", "title with \"quotes\" and \\ slashes\nnew\ttab", 1792839369657L, 7, "wear"),
            GatewaySessions.Info("0f2a", "电脑 chat ✓ ünïcode", 1792839999500L, 3, "default"),
            GatewaySessions.Info("legacy", "", 0L, 0, ""),
        )
        val back = GatewaySessions.parse(GatewaySessions.encode(rows))
        assertEquals(rows.sortedByDescending { it.lastActiveMs }, back) // ms → sec → ms keeps whole ms
    }

    // ── FRACTIONAL GATEWAY TIMESTAMPS (found live 2026-10-26: last_active 1793034407.2032979) ──
    @Test fun `search rows carry fractional-second timestamps and must not drop the profile`() {
        // A Long field threw here ("Failed to parse literal … as a long"), which made watchkit
        // search report zero hits and the watch silently fell back to local-only.
        val r = SearchResponse.parse(
            """{"query":"aurora","data":[{"id":"run_e23","title":"Aurora forecast","last_active":1793034407.2032979,"message_count":12,"profile":"wear-demo","hits":2,"snippet":"the [aurora] guide","title_match":true}]}""",
        )
        assertEquals(1, r.data.size)
        assertEquals(1793034407203L, r.data[0].lastActiveMs())
        assertEquals("wear-demo", r.data[0].profile)
    }

    @Test fun `integer timestamps still parse in the search row shape`() {
        val r = SearchResponse.parse("""{"data":[{"id":"a","last_active":1793034407}]}""")
        assertEquals(1793034407000L, r.data[0].lastActiveMs())
    }
}

/**
 * GENERIC HTTPS GATEWAY WIRE POLICY (SPEC §11.7) against real TLS MockWebServers: access headers
 * only to the approved origin, never across a redirect to another origin, Authorization dropped
 * for unknown origins, SSE streaming and POST bodies intact through the interceptor, and the
 * platform trust chain untouched (an untrusted certificate must fail, not be waved through).
 */
class DirectGatewayWireTest {
    private val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()

    private fun server(): okhttp3.mockwebserver.MockWebServer =
        okhttp3.mockwebserver.MockWebServer().also {
            it.useHttps(serverTls.sslSocketFactory(), false)
            it.start()
            servers += it
        }

    private fun client() = okhttp3.OkHttpClient.Builder()
        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .addNetworkInterceptor(AccessHeaders.INTERCEPTOR)
        .build()

    private fun gateway(vararg s: okhttp3.mockwebserver.MockWebServer, headers: Map<String, String>) = Gateway(
        "d", "remote", s.map { "https://localhost:${it.port}" }, emptyList(), "", emptyMap(),
        direct = true, accessHeaders = headers,
    )

    private fun get(c: okhttp3.OkHttpClient, url: String, auth: String? = "Bearer PROFILEKEY"): okhttp3.Response =
        c.newCall(okhttp3.Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }.build()).execute()

    @After fun tearDown() {
        servers.forEach { it.shutdown() }
        AccessHeaders.apply(emptyList())
    }

    @Test fun `access headers and the profile bearer ride together to the approved origin`() {
        val s = server()
        s.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok"))
        AccessHeaders.apply(listOf(gateway(s, headers = mapOf("CF-Access-Client-Id" to "ID", "CF-Access-Client-Secret" to "SECRET"))))
        get(client(), "https://localhost:${s.port}/p/wear/v1/health").use { assertEquals("ok", it.body!!.string()) }
        val req = s.takeRequest()
        assertEquals("ID", req.getHeader("CF-Access-Client-Id"))
        assertEquals("SECRET", req.getHeader("CF-Access-Client-Secret"))
        assertEquals("Bearer PROFILEKEY", req.getHeader("Authorization"))
    }

    @Test fun `a redirect to another origin is refused and that origin sees nothing`() {
        val gw = server()
        val other = server()
        gw.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.port}/steal"))
        other.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x"))
        AccessHeaders.apply(listOf(gateway(gw, headers = mapOf("CF-Access-Client-Secret" to "SECRET"))))
        assertThrows(java.io.IOException::class.java) { get(client(), "https://localhost:${gw.port}/v1/x").close() }
        assertEquals(0, other.requestCount)
    }

    @Test fun `a same-origin redirect keeps the headers on both hops`() {
        val s = server()
        s.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(307).setHeader("Location", "/v2/x"))
        s.enqueue(okhttp3.mockwebserver.MockResponse().setBody("done"))
        AccessHeaders.apply(listOf(gateway(s, headers = mapOf("X-Access" to "A"))))
        get(client(), "https://localhost:${s.port}/v1/x").use { assertEquals("done", it.body!!.string()) }
        repeat(2) { assertEquals("A", s.takeRequest().getHeader("X-Access")) }
    }

    @Test fun `credentials never reach an origin that is not a configured gateway`() {
        val gw = server()
        val stranger = server()
        stranger.enqueue(okhttp3.mockwebserver.MockResponse().setBody("media"))
        AccessHeaders.apply(listOf(gateway(gw, headers = mapOf("X-Access" to "A"))))
        get(client(), "https://localhost:${stranger.port}/pic.png").use { assertEquals("media", it.body!!.string()) }
        val req = stranger.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Access"))
    }

    @Test fun `a caller-supplied header with an access name is replaced, not trusted`() {
        val gw = server()
        val stranger = server()
        listOf(gw, stranger).forEach { it.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok")) }
        AccessHeaders.apply(listOf(gateway(gw, headers = mapOf("X-Access" to "REAL"))))
        val c = client()
        val forged = { url: String -> c.newCall(okhttp3.Request.Builder().url(url).header("X-Access", "FORGED").build()).execute().close() }
        forged("https://localhost:${gw.port}/")
        forged("https://localhost:${stranger.port}/")
        assertEquals("REAL", gw.takeRequest().getHeader("X-Access"))
        assertNull(stranger.takeRequest().getHeader("X-Access"))
    }

    @Test fun `two direct gateways keep their own headers`() {
        val a = server()
        val b = server()
        listOf(a, b).forEach { it.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok")) }
        AccessHeaders.apply(listOf(
            gateway(a, headers = mapOf("X-Access" to "FOR-A")).copy(id = "a"),
            gateway(b, headers = mapOf("X-Access" to "FOR-B")).copy(id = "b"),
        ))
        val c = client()
        get(c, "https://localhost:${a.port}/").close()
        get(c, "https://localhost:${b.port}/").close()
        assertEquals("FOR-A", a.takeRequest().getHeader("X-Access"))
        assertEquals("FOR-B", b.takeRequest().getHeader("X-Access"))
    }

    @Test fun `SSE streams line by line and POST bodies pass through intact`() {
        val s = server()
        s.enqueue(
            okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"d\":\"he\"}\n\ndata: {\"d\":\"llo\"}\n\ndata: [DONE]\n\n"),
        )
        AccessHeaders.apply(listOf(gateway(s, headers = mapOf("X-Access" to "A"))))
        val body = okhttp3.RequestBody.create("application/json".toMediaType(), """{"input":"hi"}""")
        val req = okhttp3.Request.Builder().url("https://localhost:${s.port}/v1/responses").post(body)
            .header("Idempotency-Key", "k1").header("Accept", "text/event-stream").build()
        val lines = client().newCall(req).execute().use { it.body!!.source().readUtf8().lines().filter { l -> l.startsWith("data:") } }
        assertEquals(3, lines.size)
        val got = s.takeRequest()
        assertEquals("""{"input":"hi"}""", got.body.readUtf8())
        assertEquals("k1", got.getHeader("Idempotency-Key"))
        assertEquals("A", got.getHeader("X-Access"))
    }

    @Test fun `an untrusted certificate still fails the handshake`() {
        val s = server()
        AccessHeaders.apply(listOf(gateway(s, headers = mapOf("X-Access" to "A"))))
        val strict = okhttp3.OkHttpClient.Builder().addNetworkInterceptor(AccessHeaders.INTERCEPTOR).build()
        assertThrows(javax.net.ssl.SSLException::class.java) { get(strict, "https://localhost:${s.port}/").close() }
        assertEquals(0, s.requestCount)
    }

    @Test fun `the selector bypasses the tunnel for direct origins only`() {
        val s = server()
        AccessHeaders.apply(listOf(gateway(s, headers = emptyMap())))
        val direct = TailnetRoute.SELECTOR.select(java.net.URI("https://localhost:${s.port}/x"))
        assertEquals(listOf(java.net.Proxy.NO_PROXY), direct)
        assertTrue(AccessHeaders.isDirect(DirectGateway.originOf("https://localhost:${s.port}")))
        assertFalse(AccessHeaders.isDirect(DirectGateway.originOf("https://elsewhere.example.com")))
    }
}
