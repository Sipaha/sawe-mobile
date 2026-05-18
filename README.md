# spk-editor-mobile

The mobile companion for [SPK Editor](https://github.com/Sipaha/spk-editor) —
pair via QR, see your open solutions and AI sessions, send messages and watch
responses stream in.

This repo is the **Remote Control** counterpart for the protocol established
in the spk-editor main repo. The roadmap docs that drove this client live there
(same machine):

- `docs/plans/2026-05-15-remote-control.md` — overall Remote Control roadmap
  (R-1 .. R-6).
- `docs/plans/2026-05-16-remote-control-R5a-android-bootstrap.md` — the inline
  plan that produced the initial scaffold.

## Modules

```
spk-editor-mobile/
  core/   # Pure JVM library (Kotlin). Pairing URL parsing, TLS pinning,
          # HMAC handshake, JSON-RPC envelope, reconnect with backoff,
          # outbound queue. JDK-only, no Android dependencies — easy to
          # unit-test on CI without an SDK.
  cli/    # Pure JVM smoke client built on :core. No Android. Issues a
          # single JSON-RPC call against a live editor for debugging.
  app/    # Android Compose UI. Depends on :core. Requires Android SDK
          # to build.
```

## Build

### Prerequisites

- JDK 21+ (Temurin 21 recommended). Older JDKs (17) work for `:core` and
  `:cli` but AGP 8.7 needs ≥ 21 for `:app`.
- Android SDK (cmdline-tools is enough) for `:app`. Set `ANDROID_HOME`.

### `:core` (no Android SDK required)

```sh
./gradlew :core:build :core:test
```

This is the surface that gets exercised in CI. Tests cover URL parsing,
fingerprint pinning, the HMAC challenge round, JSON-RPC envelope serialisation,
reconnect/backoff, and the outbound queue's TTL behaviour.

### `:app` debug APK

```sh
export ANDROID_HOME=$HOME/Android/Sdk   # or wherever your SDK lives
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without `ANDROID_HOME` (or a `local.properties` with `sdk.dir=...`), the
Android plugin halts at configure time with `SDK location not found`. That's
expected — the `:core` build is the portable one.

### `:app` release APK (R8 / signed)

The release build runs R8 minification + resource shrinking. The keystore is
configured via Gradle properties (or env vars); if none are present, the
build still completes and emits `app-release-unsigned.apk` so R8 can be
verified on any dev machine without secrets.

1. Generate a keystore (one-time):

   ```sh
   keytool -genkeypair -v -keystore release.keystore -alias spk \
       -keyalg RSA -keysize 2048 -validity 36500
   ```

2. Configure Gradle (in `~/.gradle/gradle.properties` — keep these out of
   the repo and out of `local.properties`):

   ```
   SPK_RELEASE_STORE_FILE=/abs/path/to/release.keystore
   SPK_RELEASE_STORE_PASSWORD=...
   SPK_RELEASE_KEY_ALIAS=spk
   SPK_RELEASE_KEY_PASSWORD=...
   ```

   Equivalent `SPK_RELEASE_*` environment variables also work (useful for CI).

3. Build:

   ```sh
   ./gradlew :app:assembleRelease
   ```

   - With keystore properties present: `app/build/outputs/apk/release/app-release.apk`.
   - Without: `app/build/outputs/apk/release/app-release-unsigned.apk` — R8
     still runs end-to-end, just no signature.

### `:cli` smoke client

```sh
./gradlew :cli:run --args="<pairing-url> [method] [params-json]"
```

- `<pairing-url>` may be omitted if `SPK_EDITOR_PAIRING_URL` is set.
- `[method]` defaults to `remote.editor.capabilities`.
- `[params-json]` is parsed via `kotlinx.serialization.json` and forwarded as
  the JSON-RPC `params` field.

If `method` is `remote.editor.subscribe`, the CLI blocks for up to 30 seconds
after the subscribe response, printing every server notification on the
`notifications` SharedFlow. Exit code is `0` on success, `1` on
parse/connection/JSON-RPC-error.

Example:

```sh
./gradlew :cli:run --args='spk-remote://... remote.solutions.list'
./gradlew :cli:run \
    --args='spk-remote://... remote.editor.subscribe {"kinds":["buffer_opened"]}'
```

## Pairing URL

The editor produces a pairing URL of the form:

```
spk-remote://<host>:<port>?secret=<base64>&client=<name>&fp=<sha256-hex>
```

- `secret` — 32 bytes (base64-encoded) of shared key for HMAC handshake.
- `fp` — 32 bytes (lowercase hex) SHA-256 fingerprint of the server's leaf cert.
- `client` — display name shown in the editor's connections list.

`PairingUrl.parse(...)` validates all of these.

## Pairing flow

1. Open Remote Control on the desktop (status bar → Remote Control).
2. Add a client, tap "Show QR".
3. On the phone, tap **Scan pairing QR** and point at the QR.
4. The app handshakes the editor (TLS pin + HMAC), pulls solutions, and lands
   you on the Solutions list.

Pairing URLs are persisted in **EncryptedSharedPreferences** (Android-Keystore
master key, AES256-GCM values). On every cold start, `MainActivity.onCreate`
reads the saved URL and reconnects without prompting. To wipe the pairing,
open **Settings** (gear icon on the Solutions list) → **Forget paired server**
or **Re-pair**.

## Integration test (live editor)

`:core` has an opt-in end-to-end probe gated on the `SPK_EDITOR_PAIRING_URL`
environment variable. It exercises six assertions against a live editor:

1. `RemoteClient.connect()` succeeds (TLS pin + HMAC handshake).
2. `remote.editor.capabilities` returns a result containing `protocol_version`.
3. `remote.solutions.list` returns a (possibly empty) result.
4. `remote.lsp.start` is rejected with JSON-RPC `-32601` — proves the R-4
   allow-list filter is active.
5. `remote.editor.subscribe` with `kinds=["agent_session_message_appended"]`
   succeeds.
6. After `client.close()`, a follow-up call does not succeed.

```sh
SPK_EDITOR_PAIRING_URL='spk-remote://...' ./gradlew :core:test \
    -DincludeTags=integration
```

The default `:core:test` run skips integration tests.

## License

GPL-3.0-or-later. © 2026 Pavel Simonov. See [LICENSE](LICENSE).
