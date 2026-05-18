package ru.sipaha.spkremote.core

import java.net.URI
import java.util.Base64

/**
 * A parsed `spk-editor-remote://` pairing URL handed out by the editor.
 *
 * Wire form (matches `crates/remote_control_ui/src/qr_popover.rs::build_pairing_url`):
 * ```
 * spk-editor-remote://<host>:<port>?secret=<url-safe-base64>&client=<name>&server_fp=<url-safe-base64>
 * ```
 *
 * Both `secret` and `server_fp` are **URL-safe base64 without padding**
 * (`A-Z a-z 0-9 - _`, no `=`). The server emits this variant
 * intentionally — standard base64's `+` and `/` would be reserved
 * characters inside a URL query string. Java's `Base64.getUrlDecoder()`
 * accepts both padded and unpadded URL-safe input.
 *
 * - [secret] is exactly 32 bytes (the HMAC key).
 * - [fingerprint] is exactly 32 bytes (SHA-256 of the server's leaf certificate).
 * - [client] is the human-readable name shown in the editor's connections UI.
 */
data class PairingUrl(
    val host: String,
    val port: Int,
    val secret: ByteArray,
    val client: String,
    val fingerprint: ByteArray,
) {
    init {
        require(secret.size == SECRET_LEN) {
            "secret must be $SECRET_LEN bytes, was ${secret.size}"
        }
        require(fingerprint.size == FP_LEN) {
            "fingerprint must be $FP_LEN bytes, was ${fingerprint.size}"
        }
        require(port in 1..65_535) { "port out of range: $port" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingUrl) return false
        return host == other.host &&
            port == other.port &&
            client == other.client &&
            secret.contentEquals(other.secret) &&
            fingerprint.contentEquals(other.fingerprint)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + client.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + fingerprint.contentHashCode()
        return result
    }

    override fun toString(): String =
        "PairingUrl(host=$host, port=$port, client=$client, " +
            "secret=<${secret.size}B>, fingerprint=<${fingerprint.size}B>)"

    companion object {
        const val SCHEME = "spk-editor-remote"
        const val SECRET_LEN = 32
        const val FP_LEN = 32

        fun parse(uri: String): Result<PairingUrl> = runCatching {
            val parsed = URI(uri)
            require(parsed.scheme == SCHEME) {
                "expected scheme '$SCHEME', was '${parsed.scheme}'"
            }
            val host = requireNotNull(parsed.host) { "missing host" }
            val port = parsed.port.takeIf { it > 0 }
                ?: error("missing port (got ${parsed.port})")
            val params = parseQuery(parsed.rawQuery ?: "")

            val secretB64 = requireParam(params, "secret")
            val secret = runCatching { Base64.getUrlDecoder().decode(secretB64) }
                .getOrElse { error("secret is not valid URL-safe base64") }
            require(secret.size == SECRET_LEN) {
                "secret must decode to $SECRET_LEN bytes, was ${secret.size}"
            }

            val fpB64 = requireParam(params, "server_fp")
            val fingerprint = runCatching { Base64.getUrlDecoder().decode(fpB64) }
                .getOrElse { error("server_fp is not valid URL-safe base64") }
            require(fingerprint.size == FP_LEN) {
                "fingerprint must decode to $FP_LEN bytes, was ${fingerprint.size}"
            }

            val client = requireParam(params, "client")
            require(client.isNotBlank()) { "client must not be blank" }
            // [client] flows verbatim into the X-Spk-Remote-Client HTTP header
            // in OkHttpRemoteTransport. A crafted pairing URL with embedded
            // CR/LF (or other control chars) could otherwise inject extra
            // request headers. Reject anything in C0 (< 0x20) plus DEL (0x7F).
            require(client.none { it.code < 0x20 || it.code == 0x7F }) {
                "client must not contain control characters"
            }

            PairingUrl(
                host = host,
                port = port,
                secret = secret,
                client = client,
                fingerprint = fingerprint,
            )
        }

        private fun parseQuery(rawQuery: String): Map<String, String> {
            if (rawQuery.isEmpty()) return emptyMap()
            return rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val idx = pair.indexOf('=')
                    if (idx < 0) {
                        urlDecode(pair) to ""
                    } else {
                        urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
                    }
                }
        }

        private fun urlDecode(s: String): String =
            java.net.URLDecoder.decode(s, Charsets.UTF_8)

        private fun requireParam(params: Map<String, String>, name: String): String =
            params[name] ?: error("missing required param '$name'")
    }
}
