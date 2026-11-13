# Generic HTTPS gateway mode (no tailnet)

Reach a Hermes gateway over ordinary HTTPS — a reverse proxy, Cloudflare Access, any VPN-less
remote — without Tailscale enrollment, pairing, or whois identity. The tailnet mode is unchanged and
stays the default; a gateway opts in with the **Remote HTTPS (no tailnet)** switch in the gateway
editor (Settings ▸ Gateways).

## What the mode is, and is not

| | Tailnet gateway (default) | Generic HTTPS gateway |
|---|---|---|
| Transport | embedded tailnet node (SOCKS shim) | ordinary network, `Proxy.NO_PROXY` for the gateway's own origins |
| Pairing / private lane / whois | available | **not used** — keys are entered by hand |
| Profile keys | learned over the pairing lane, or typed | typed per profile only |
| Key fallback | profile key → gateway token → mirror token | **profile key only**; never another profile's or a gateway-wide key |
| URL scheme | `http` or `https` | `https` only, no credentials / query / fragment in the URL |
| Access-layer headers | not allowed | up to 8, sent only to this gateway's origins |

The embedded node is not started when every configured gateway is direct (`AppSettings.tunnelRequired()`).
Mixed setups work: tailnet gateways keep the tunnel, direct gateways bypass it.

## Setup

1. Put the gateway behind HTTPS with a certificate that validates normally. TLS verification and
   hostname checks are the platform defaults; nothing in this mode disables or pins them.
2. Settings ▸ Gateways ▸ add/edit. Enter the base URL (`https://hermes.example.com`, without
   `/p/<profile>`), turn on **Remote HTTPS (no tailnet)**.
3. Optional access headers (name + value), for example Cloudflare Access service-token headers
   `CF-Access-Client-Id` and `CF-Access-Client-Secret`. Values are sealed in the Android Keystore like
   tokens.
4. Set each profile's `API_SERVER_KEY` under that profile's agent key. A profile with no key is
   refused (401 on the first request) — by design there is no fallback.
5. Optional: a watchkit URL and **Watchkit token** if you expose the watchkit helper (media scaling,
   mermaid, STT fallback). Without one, those features degrade; chat, sessions and approvals do not
   need it.

`Authorization` (and `Proxy-Authorization`) cannot be an access header: it is reserved for the
per-profile Hermes key. Save is refused with an explicit message rather than silently choosing one.
Other protocol-level names (`Host`, `Content-Type`, `Idempotency-Key`, `Last-Event-ID`, `Sec-WebSocket-*`,
…) are refused too.

## How the credentials are kept apart

`net/AccessHeaders` is an OkHttp **network interceptor** on every gateway client (REST, SSE, probes,
STT WebSocket, TTS fetch, media), so it runs on every redirect hop:

- access headers are attached only when the hop's origin (scheme+host+effective port) is one of that
  gateway's own https origins; a copy of an access-header name arriving from a caller is stripped first;
- a redirect that leaves the approved origin is refused (`IOException`); the other origin receives
  nothing;
- `Authorization` is removed on any hop to an origin that is not a configured gateway origin;
- media3 playback now uses the same OkHttp client (`media3-datasource-okhttp`), replacing a
  process-wide `ProxySelector` override and `DefaultHttpDataSource` that bypassed this policy.

Secrets are not logged, put in URLs, or stored in plain DataStore: access-header values and the
watchkit token are sealed with `KeystoreSecrets` next to the existing tokens.

## What was separated (findings)

- **Transport.** `TailnetRoute.SELECTOR` returns `NO_PROXY` for direct origins and `failClosed` no
  longer blocks them. `tailnetEnabled()` forcing the tunnel in release builds is bypassed through
  `tunnelRequired()` when no gateway needs it.
- **Pairing / private lane.** `PrivateLane` and `PairingClient` only run once a device is paired and
  `PrivateLane.configured` is true. In generic mode nothing configures them, so they stay idle. The
  plugin's `private_api.py` verifies the socket peer through Tailscale whois; that boundary is
  untouched, and the private lane must **not** be exposed through the public proxy. Keys are entered
  manually instead.
- **Key fallback.** `Gateway.keyFor` / `GatewayModel.tokenFor` previously fell back to the gateway
  token and the mirror token; for direct gateways both fallbacks are off.
- **Watchkit bearer.** Direct gateways carry their own watchkit token instead of the global one.

## Server side

No gateway change is required for chat, SSE, sessions, profile selection, run stop/steer/approval:
they are plain `/p/<profile>/v1/...` and `/api/...` calls with the profile bearer. The reverse proxy
must:

- terminate TLS with a publicly valid certificate;
- not buffer `text/event-stream` (SSE; e.g. nginx `proxy_buffering off`) and allow long idle reads;
- pass `Authorization`, `Idempotency-Key` and `Last-Event-ID` through, and the WebSocket upgrade if the
  STT WebSocket is used;
- if it is an access layer such as Cloudflare Access, accept the configured service-token headers.

Voice lanes that identify the device by tailnet node identity (`private_api.py` voice routes) are not
available through the generic path (the private lane is never configured there). Gateway voice
output (TTS) and the STT WebSocket go through the same OkHttp policy but were not exercised against a
live remote gateway in this change.

## Sessions and WhatsApp continuity

The gateway is the only session authority (SPEC §12.13); the watch adopts a gateway session id and
submits runs with the same `session_id`. Whether a **WhatsApp** conversation can be continued from the
watch in the same session, and the reverse, depends on the gateway admitting a non-`api_server`
session through the run API (the `bind_api_steps` 409 gate described in §12.13): it is not verified
by this change, and separate watch conversations are the supported fallback. No conversation
database is touched by this mode.

## Update flow for the fork

```
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch release && git merge main && git push origin release
```

Feature branches start from `main`; `release` is the branch you build and sideload. See the
`working-in-a-fork` rules.

## Build and sideload

```
../devkit/run-gradle.sh :app:testDebugUnitTest :app:assembleDebug
adb connect <watch-ip:port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Hardware verification on a Galaxy Watch 6 Classic is not covered by CI or the JVM tests.
