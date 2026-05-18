# spk-editor-mobile

The mobile companion for [SPK Editor](https://github.com/Sipaha/spk-editor) —
pair via QR, see your open Solutions and AI sessions, send messages and watch
responses stream in.

This repo is the **Remote Control** counterpart to the protocol established
in the spk-editor main repo. The roadmap (R-1 .. R-6) lives there at
[`docs/plans/2026-05-15-remote-control.md`](https://github.com/Sipaha/spk-editor/blob/main/docs/plans/2026-05-15-remote-control.md).

## Modules

```
spk-editor-mobile/
  core/   # Pure JVM library (Kotlin). Pairing URL parsing, TLS pinning,
          # HMAC handshake, JSON-RPC envelope, reconnect with backoff,
          # outbound queue with disk persistence, ConnectFailure
          # classifier. JDK-only, no Android deps — unit-testable on
          # any machine with JDK 21+.
  cli/    # Pure JVM smoke client over :core. Single-shot RPC against
          # a live editor for debugging. No Android.
  app/    # Android Compose UI. Depends on :core. Multi-server pairing,
          # Settings, Crash logs, EncryptedSharedPreferences-backed
          # state. Requires Android SDK to build.
```

## Build

### Prerequisites

- **JDK 21+** (Temurin 21 recommended). Use older JDKs only for `:core` /
  `:cli`; AGP 8.7 wants ≥ 21 for `:app`.
- **Android SDK** (cmdline-tools alone is enough) for `:app`. Set
  `ANDROID_HOME`.

### `:core` (no Android SDK required)

```sh
./gradlew :core:build :core:test
```

This is the surface CI exercises. ~109 unit tests cover URL parsing,
fingerprint pinning, HMAC challenge, JSON-RPC envelope, MCP tools/call
unwrap, reconnect/backoff, outbound queue TTL, connection-failure
classification.

### `:app` debug APK

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=$HOME/.jdks/temurin-21.0.10   # or any JDK 21
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without `ANDROID_HOME` (or a `local.properties` with `sdk.dir=...`), AGP
halts at configure-time with `SDK location not found`. The `:core` build
is the portable one.

### `:app` release APK (R8 + optional signing)

The release build runs R8 minification + resource shrinking. Signing is
gated on Gradle properties; if they're absent, the build still completes
and emits `app-release-unsigned.apk` so R8 can be verified on any dev
machine without secrets.

1. Generate a keystore once:

   ```sh
   keytool -genkeypair -v -keystore release.keystore -alias spk \
       -keyalg RSA -keysize 2048 -validity 36500
   ```

2. Configure Gradle in `~/.gradle/gradle.properties` (keep these OUT of
   the repo and out of `local.properties`):

   ```
   SPK_RELEASE_STORE_FILE=/abs/path/to/release.keystore
   SPK_RELEASE_STORE_PASSWORD=...
   SPK_RELEASE_KEY_ALIAS=spk
   SPK_RELEASE_KEY_PASSWORD=...
   ```

   Equivalent `SPK_RELEASE_*` env vars work too (useful for CI).

3. Build:

   ```sh
   ./gradlew :app:assembleRelease
   ```

   - With keystore properties → `app/build/outputs/apk/release/app-release.apk`.
   - Without → `app-release-unsigned.apk` (R8 ran end-to-end, just no
     signature). Sideloadable via `adb install` since adb tolerates
     unsigned APKs on debug-mode devices.

Typical release APK size after R8: **~2.2 MB**.

### `:cli` smoke client

```sh
./gradlew :cli:run --args="<pairing-url> [method] [params-json]"
```

- `<pairing-url>` may be omitted if `SPK_EDITOR_PAIRING_URL` is set.
- `[method]` defaults to `remote.editor.capabilities`.
- `[params-json]` is parsed via `kotlinx.serialization.json` and
  forwarded as the JSON-RPC `params` field.

If `method` is `remote.editor.subscribe`, the CLI blocks for up to 30
seconds after the subscribe response, printing every server
notification on the `notifications` SharedFlow. Exit code is `0` on
success, `1` on parse/connection/JSON-RPC-error.

Examples:

```sh
./gradlew :cli:run --args='spk-editor-remote://… remote.solutions.list'
./gradlew :cli:run \
    --args='spk-editor-remote://… remote.editor.subscribe {"kinds":["agent_session_message_appended"]}'
```

## Pairing URL

The editor produces a pairing URL of the form:

```
spk-editor-remote://<host>:<port>?secret=<url-safe-base64>&client=<name>&server_fp=<url-safe-base64>
```

- `secret` — 32 bytes (URL-safe-base64, no padding) — shared HMAC key.
- `server_fp` — 32 bytes (URL-safe-base64, no padding) — SHA-256 of
  the server's leaf TLS certificate. Used for cert pinning during the
  handshake.
- `client` — display name shown in the editor's connections list.

`PairingUrl.parse(...)` validates all of these.

The transport's HMAC challenge frame uses a domain-separation tag
`spk-editor-remote-v1\0`. The client and server must agree on this
tag byte-for-byte (see `core::HmacChallengeAuth.HMAC_DOMAIN_TAG` and
`remote_control::auth::HMAC_DOMAIN_TAG`).

## Pairing flow

1. On the desktop, open **Remote Control** (status bar, right side —
   the indicator dot). Toggle it **on**; the listener binds to
   `0.0.0.0:<port>`.
2. **Add a client** in the panel; the editor mints a per-client secret.
3. Tap **Show QR** to reveal the pairing URL as a QR code.
4. On the phone, tap **Scan pairing QR** and point the camera at the
   QR. (You can also paste the URL manually — useful for debug.)
5. The phone handshakes the editor (TLS pin + HMAC + welcome), then
   pulls the Solutions list.

Pairing URLs are persisted in `EncryptedSharedPreferences`
(Android-Keystore master key, AES-256-GCM values). On every cold
start, `MainActivity` reads the saved URL(s) and reconnects to the
active one without prompting. To change the address (e.g. new public
IP), open **Settings → Edit address / label**; to drop a pairing,
**Settings → Forget this server**.

## Multi-server

The phone can be paired with multiple editor instances at once
(home desktop + work laptop + remote VPS). When ≥ 2 servers are
paired, cold start lands on a **Servers** picker; the active server's
last-connected timestamp drives ordering.

## Connection errors

Every connection failure is classified by the
[`ConnectFailure`](core/src/main/kotlin/ru/sipaha/spkremote/core/ConnectFailure.kt)
sealed class into one of: `Unreachable`, `TlsPinMismatch`,
`AuthRejected`, `HandshakeTimeout`, `ProtocolError`, `ServerClosed`,
or `Unknown`. The on-screen banner shows the user-facing reason —
no opaque "not connected" messages. See the class doc for the
specific symptoms each variant catches.

## Integration test (live editor)

`:core` has an opt-in end-to-end probe gated on the
`SPK_EDITOR_PAIRING_URL` environment variable. Six assertions against
a live editor:

1. `RemoteClient.connect()` succeeds (TLS pin + HMAC handshake).
2. `remote.editor.capabilities` returns a result with `protocol_version`.
3. `remote.solutions.list` returns a (possibly empty) result.
4. `remote.lsp.start` returns JSON-RPC `-32601` — confirms the R-4
   allow-list filter is active.
5. `remote.editor.subscribe` with
   `kinds=["agent_session_message_appended"]` succeeds.
6. After `client.close()`, a follow-up call does not succeed.

```sh
SPK_EDITOR_PAIRING_URL='spk-editor-remote://…' \
    ./gradlew :core:test -DincludeTags=integration
```

The default `:core:test` run skips integration tests.

## Crash logs

Local crash logs are written to the app's private storage at
`filesDir/crash-logs/crash-<timestamp>.log` via an
`UncaughtExceptionHandler` installed in `SpkApplication.onCreate`.
Rotation keeps the 10 newest. Reachable from
**Settings → Diagnostics → Crash logs**: tap a row to view, share via
Android's share-intent, or **Clear all**. No external upload — the
logs stay on-device.

## License

Apache License 2.0. © 2026 Pavel Simonov. See [LICENSE](LICENSE).
