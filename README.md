# spk-editor-android-client

Android remote-control client for [SPK Editor](https://github.com/Sipaha/spk-editor).
Pairs with an editor instance over WebSocket + TLS, authenticates via HMAC, and
proxies tool calls into the editor's embedded MCP server.

This is the **Remote Control** counterpart for the protocol established in the
spk-editor repo. The corresponding plan docs live there (same machine):

- `/home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor/docs/plans/2026-05-15-remote-control.md`
  — overall Remote Control roadmap (R-1 .. R-5).
- `/home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor/docs/plans/2026-05-16-remote-control-R5a-android-bootstrap.md`
  — the inline plan that produced this scaffold.

## Layout

```
spk-editor-android-client/
  core/   # Pure JVM library (Kotlin). Pairing URL parsing, fingerprint pinning,
          # HMAC handshake, JSON-RPC envelope, WebSocket client. JDK-only,
          # no Android dependencies — easy to unit-test on CI without an SDK.
  cli/    # Pure JVM smoke client built on :core. No Android. Use it to issue
          # a single JSON-RPC call against a live editor for debugging.
  app/    # Android Compose UI. Depends on :core. Requires Android SDK to build.
```

## Building

### `:core` (no Android SDK required)

```sh
./gradlew :core:build :core:test
```

This is the surface that gets exercised in CI. Tests cover URL parsing,
fingerprint pinning, the HMAC challenge round, and JSON-RPC envelope
serialisation.

JDK 17+ is required (Kotlin JVM target is `17`). On this machine, JDK 25
(`~/.jdks/temurin-25.0.2`) is what we develop against.

### `:app` (Android SDK required)

```sh
export ANDROID_HOME=$HOME/Android/Sdk   # or wherever your SDK lives
./gradlew :app:assembleDebug
```

Without `ANDROID_HOME` (or a `local.properties` with `sdk.dir=...`), the
Android plugin halts at configure time with `SDK location not found`. That's
expected — the `:core` build is the one that's portable.

## Pairing URL

The editor produces a pairing URL of the form:

```
spk-remote://<host>:<port>?secret=<base64>&client=<name>&fp=<sha256-hex>
```

- `secret` — 32 bytes (base64-encoded) of shared key for HMAC handshake.
- `fp` — 32 bytes (lowercase hex) SHA-256 fingerprint of the server's leaf cert.
- `client` — display name shown in the editor's connections list.

`PairingUrl.parse(...)` validates all of these.

## Integration test

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

To run it:

```sh
SPK_EDITOR_PAIRING_URL='spk-remote://...' ./gradlew :core:test \
    -DincludeTags=integration
```

The default `:core:test` run skips integration tests (they're tagged
`integration` and excluded by the JUnit Platform filter in
`core/build.gradle.kts`).

## `:cli` smoke client

`:cli` is a pure-JVM terminal client over `:core`. Use it for quick manual
verification against a live editor without spinning up the Android UI:

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

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
