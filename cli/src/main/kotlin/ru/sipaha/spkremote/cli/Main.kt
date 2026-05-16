package ru.sipaha.spkremote.cli

import kotlin.system.exitProcess
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.RemoteClient

/**
 * Pure-JVM smoke client for `:core`. Connects to a live editor instance via
 * the pairing URL and issues a single JSON-RPC call. The `:cli` module
 * intentionally has no Android dependency so it can be exercised on a dev
 * machine without an SDK.
 *
 * Usage:
 *
 *     ./gradlew :cli:run --args="<pairing-url> [method] [params-json]"
 *
 * or set `SPK_EDITOR_PAIRING_URL` and omit the first arg.
 *
 * If `method` is `remote.editor.subscribe`, the CLI prints the subscribe
 * response then blocks for up to 30 seconds, printing every notification
 * that arrives on the SharedFlow.
 */
private const val USAGE = """\
Usage:
  spk-remote-cli <pairing-url> [method] [params-json]
  SPK_EDITOR_PAIRING_URL=<url> spk-remote-cli [method] [params-json]

  pairing-url:  spk-remote://host:port?secret=...&fp=...&client=...
  method:       JSON-RPC method (default: remote.editor.capabilities)
  params-json:  optional JSON for `params`, e.g. '{"kinds":["buffer_opened"]}'

Exit codes:
  0  success
  1  bad args / connect / parse / JSON-RPC error response
"""

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

fun main(rawArgs: Array<String>) {
    val exit = runCatching { runMain(rawArgs.toList()) }.fold(
        onSuccess = { it },
        onFailure = { err ->
            System.err.println("error: ${err.message ?: err}")
            1
        },
    )
    exitProcess(exit)
}

private fun runMain(args: List<String>): Int {
    val (pairingUrl, method, paramsJson) = parseArgs(args) ?: run {
        System.err.print(USAGE)
        return 1
    }

    val parsed = PairingUrl.parse(pairingUrl).getOrElse {
        System.err.println("error: invalid pairing URL: ${it.message}")
        return 1
    }
    val params: JsonElement? = paramsJson?.let {
        runCatching { JsonRpc.json.parseToJsonElement(it) }.getOrElse { err ->
            System.err.println("error: invalid params JSON: ${err.message}")
            return 1
        }
    }

    val client = RemoteClient(parsed)
    return runBlocking {
        try {
            client.connect().getOrElse {
                System.err.println("error: connect failed: ${it.message}")
                return@runBlocking 1
            }
            // For `remote.editor.subscribe`, start collecting notifications
            // BEFORE we issue the subscribe call so we don't miss any that
            // may race in immediately after the server records us.
            val notificationJob = if (method == "remote.editor.subscribe") {
                launch {
                    client.notifications
                        .onEach { println("notification: ${prettyJson.encodeToString(JsonElement.serializer(), it)}") }
                        .collect()
                }
            } else {
                null
            }

            val response = client.call(method, params)
            if (response.error != null) {
                System.err.println(
                    "error: server returned JSON-RPC error: " +
                        "code=${response.error!!.code} message=${response.error!!.message}",
                )
                return@runBlocking 1
            }
            response.result?.let {
                println(prettyJson.encodeToString(JsonElement.serializer(), it))
            } ?: println("(no result)")

            if (notificationJob != null) {
                // Subscribed — block for up to 30 s printing notifications.
                withTimeoutOrNull(30_000) { notificationJob.join() }
                notificationJob.cancel()
            }
            0
        } finally {
            client.close()
        }
    }
}

private data class CliArgs(
    val pairingUrl: String,
    val method: String,
    val paramsJson: String?,
)

private fun parseArgs(args: List<String>): CliArgs? {
    if (args.any { it == "--help" || it == "-h" }) return null

    val env = System.getenv("SPK_EDITOR_PAIRING_URL")?.takeIf { it.isNotBlank() }

    // Heuristic: an argv that starts with `spk-remote://` is the pairing URL.
    // Otherwise, fall back to the env var and treat argv[0] as the method.
    val (pairing, rest) = when {
        args.isNotEmpty() && args[0].startsWith("spk-remote://") ->
            args[0] to args.drop(1)
        env != null ->
            env to args
        args.isEmpty() ->
            return null
        else -> {
            // argv provided but neither a pairing URL nor an env var to back it.
            return null
        }
    }

    val method = rest.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "remote.editor.capabilities"
    val paramsJson = rest.getOrNull(1)
    return CliArgs(pairing, method, paramsJson)
}
