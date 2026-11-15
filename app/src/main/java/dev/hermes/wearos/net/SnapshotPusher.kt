package dev.hermes.wearos.net

import android.content.Context
import android.util.Log
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.health.BodySnapshot
import dev.hermes.wearos.health.BodyTools
import dev.hermes.wearos.location.LocationTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * W8 (SPEC §5): watch → watchkit snapshot plumbing. Builds a [BodySnapshot] honoring the owner's
 * per-tool toggles and POSTs it to `POST /health/snapshot` (bearer) on the configured watchkit
 * base — same bearer/OkHttp client as mermaid renders (see [Watchkit]). Cadence: 15 min while the
 * app process lives + a refresh right before a chat ask (so "what's my heart rate" reads fresh).
 * Offline is a drop, not a queue (v1): the next interval heals it.
 */
class SnapshotPusher(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val app: Context,
) {
    companion object {
        private const val TAG = "HermesWear"
        const val INTERVAL_MS = 15 * 60_000L
        private const val ASK_MIN_INTERVAL_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    data class PushState(val lastTs: Long = 0L, val result: String = "never", val inFlight: Boolean = false)
    private val _state = MutableStateFlow(PushState())
    val state: StateFlow<PushState> = _state.asStateFlow()
    private val busy = AtomicBoolean(false)

    /** Start the 15-min loop (SPEC W8 "digest cadence" placeholder). No-op when all tools off. */
    fun start() {
        scope.launch {
            while (true) {
                try { if (toggles().any()) push("interval") } catch (e: Exception) { record("err ${e.message}") }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Chat asked: make the agent's MCP read fresh — re-probe then push (rate-limited, best-effort). */
    fun refreshOnAsk() {
        scope.launch {
            try {
                val t = toggles()
                if (!t.any()) return@launch
                if (_state.value.lastTs > 0 && System.currentTimeMillis() - _state.value.lastTs < ASK_MIN_INTERVAL_MS)
                    return@launch // a recent interval/ask push is fresh enough
                BodyTools.refresh(scope)
                push("chat-ask")
            } catch (e: Exception) { Log.d(TAG, "snapshot refresh-on-ask skipped: ${e.message}") }
        }
    }

    /** Settings "Push now" / dev `body_push`: force one push regardless of cadence. */
    fun pushNow(reason: String = "manual") {
        scope.launch {
            try { BodyTools.probe(); push(reason, force = true) }
            catch (e: Exception) { record("err ${e.message}") }
        }
    }

    private suspend fun toggles(): BodySnapshot.Toggles = BodySnapshot.Toggles(
        heartRate = settings.bodyToolEnabled(BodySnapshot.TOOL_HR),
        steps = settings.bodyToolEnabled(BodySnapshot.TOOL_STEPS),
        sleep = settings.bodyToolEnabled(BodySnapshot.TOOL_SLEEP),
        spo2 = settings.bodyToolEnabled(BodySnapshot.TOOL_SPO2),
        location = settings.bodyToolEnabled(BodySnapshot.TOOL_LOC),
    )

    /**
     * Owner ask B: when the Location tool is ON, refresh the fix that goes into `values.location`.
     * chat-ask/manual probes a fresh fix (~10 s cap, same refresh-on-ask promise HR has); the
     * battery-conscious 15-min interval only reads the OS last-known. Fail-soft: null → omitted.
     */
    private suspend fun locationValues(t: BodySnapshot.Toggles, reason: String): BodySnapshot.Values {
        if (!t.location) return BodyTools.values()
        val fix = try {
            if (reason == "interval") LocationTool.lastKnown(app)
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocationTool.probe(app) }
        } catch (e: Exception) { Log.d(TAG, "location refresh skipped: ${e.message}"); LocationTool.cachedFix() }
        return BodyTools.values().copy(
            locationLat = fix?.lat,
            locationLng = fix?.lng,
            locationAccuracyM = fix?.accuracyM,
            locationTs = fix?.tsMs ?: 0L,
        )
    }

    /** @return the one-line result (also mirrored into [state]). */
    suspend fun push(reason: String, force: Boolean = false): String {
        if (!busy.compareAndSet(false, true)) return "skipped · push in flight"
        try {
            _state.value = _state.value.copy(inFlight = true)
            val t = toggles()
            if (!t.any() && !force) return record("skipped · all body tools off")
            val cfg = Watchkit.config.value
            if (cfg.baseUrl.isEmpty()) return record("skipped · no watchkit for this gateway")
            if (cfg.token.isEmpty()) return record("skipped · watchkit token unset (Settings/adb)")
            val now = System.currentTimeMillis()
            val body = BodySnapshot.buildJson(now, chatActive = false, t, locationValues(t, reason))
            val req = Request.Builder()
                .url("${cfg.baseUrl}/health/snapshot")
                .post(body.toRequestBody(JSON))
                .header("Authorization", "Bearer ${cfg.token}")
                .build()
            return try {
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    HermesApi.DEFAULT.newCall(req).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        if (!r.isSuccessful) "err ${r.code}: ${text.take(80)}"
                        else "ok · ${body.length}B → watchkit"
                    }
                }
                record(result, ts = now, note = reason)
            } catch (e: Exception) {
                record("err ${e.message ?: "unreachable"} (dropped, not queued)", note = reason)
            }
        } finally {
            _state.value = _state.value.copy(inFlight = false)
            busy.set(false)
        }
    }

    private fun record(result: String, ts: Long = System.currentTimeMillis(), note: String = ""): String {
        _state.value = PushState(lastTs = ts, result = result)
        Log.i(TAG, "snapshot push ($note): $result")
        return result
    }
}
