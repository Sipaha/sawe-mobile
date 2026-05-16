package ru.sipaha.spkremote.core

import java.net.URI
import java.util.Base64

/**
 * A parsed `spk-remote://` pairing URL handed out by the editor.
 *
 * Wire form:
 * ```
 * spk-remote://<host>:<port>?secret=<base64>&client=<name>&fp=<sha256-hex>
 * ```
 *
 * - [secret] is exactly 32 bytes, base64-decoded.
 * - [fingerprint] is exactly 32 bytes (SHA-256 of the server's leaf certificate)
 *   parsed from a hex string (case-insensitive).
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
        const val SCHEME = "spk-remote"
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
            val secret = runCatching { Base64.getDecoder().decode(secretB64) }
                .getOrElse { error("secret is not valid base64") }
            require(secret.size == SECRET_LEN) {
                "secret must decode to $SECRET_LEN bytes, was ${secret.size}"
            }

            val fpHex = requireParam(params, "fp")
            val fingerprint = decodeHex(fpHex)
            require(fingerprint.size == FP_LEN) {
                "fingerprint must be $FP_LEN bytes, was ${fingerprint.size}"
            }

            val client = requireParam(params, "client")
            require(client.isNotBlank()) { "client must not be blank" }

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

        private fun decodeHex(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "hex string must have even length" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "invalid hex char in '$hex'" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
