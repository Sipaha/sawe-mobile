package ru.sipaha.sawe.cli

import kotlin.system.exitProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.NotConnectedException
import ru.sipaha.sawe.core.PairingUrl
import ru.sipaha.sawe.core.RemoteClient

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
 * response then blocks for up to [SUBSCRIBE_LISTEN_MS] ms (30 seconds),
 * printing every notification that arrives on the SharedFlow.
 */
private const val SUBSCRIBE_LISTEN_MS = 30_000L

private const val USAGE = """
Usage:
  spk-remote-cli <pairing-url> [method] [params-json]
  SPK_EDITOR_PAIRING_URL=<url> spk-remote-cli [method] [params-json]

  pairing-url:  sawe-remote://host:port?secret=...&server_fp=...&client=...
  method:       JSON-RPC method (default: remote.editor.capabilities)
  params-json:  optional JSON for `params`, e.g. '{"kinds":["buffer_opened"]}'

workspace.* smoke commands (shorthand subcommands):
  spk-remote-cli <pairing-url> workspace snapshot
  spk-remote-cli <pairing-url> workspace list-solutions [--open|--closed|--all]
  spk-remote-cli <pairing-url> workspace open-solution <SOLUTION_ID>
  spk-remote-cli <pairing-url> workspace close-solution <SOLUTION_ID>
  spk-remote-cli <pairing-url> workspace open-session <SESSION_ID>
  spk-remote-cli <pairing-url> workspace close-session <SESSION_ID>

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
        // For `remote.editor.subscribe`, start collecting notifications
        // BEFORE we issue the subscribe call so we don't miss any that
        // may race in immediately after the server records us.
        //
        // The collector is a hot SharedFlow subscriber that `RemoteClient.close()`
        // does NOT complete, so we MUST cancel it in the finally block — otherwise
        // any error path leaves the child coroutine alive and `runBlocking` hangs
        // waiting for it.
        var notificationJob: Job? = null
        try {
            client.connect().getOrElse {
                System.err.println("error: connect failed: ${it.message}")
                return@runBlocking 1
            }
            if (method == "remote.editor.subscribe") {
                notificationJob = launch {
                    client.notifications
                        .onEach { println("notification: ${prettyJson.encodeToString(JsonElement.serializer(), it)}") }
                        .collect()
                }
            }

            val response = try {
                client.call(method, params)
            } catch (e: TimeoutCancellationException) {
                System.err.println(
                    "error: call to '$method' timed out after " +
                        "${RemoteClient.DEFAULT_CALL_TIMEOUT_MS / 1000} s"
                )
                return@runBlocking 1
            } catch (e: NotConnectedException) {
                System.err.println("error: lost connection to editor before call to '$method': ${e.message}")
                return@runBlocking 1
            }
            val err = response.error
            if (err != null) {
                System.err.println(
                    "error: server returned JSON-RPC error: " +
                        "code=${err.code} message=${err.message}",
                )
                return@runBlocking 1
            }
            response.result?.let {
                println(prettyJson.encodeToString(JsonElement.serializer(), it))
            } ?: println("(no result)")

            val activeJob = notificationJob
            if (activeJob != null) {
                // Subscribed — block for up to [SUBSCRIBE_LISTEN_MS] printing notifications.
                withTimeoutOrNull(SUBSCRIBE_LISTEN_MS) { activeJob.join() }
            }
            0
        } finally {
            notificationJob?.cancel()
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
    // Only treat `--help` / `-h` as a help request when it's the leading argv.
    // Scanning the whole argv would misfire if `--help` appears inside a
    // params-JSON literal (e.g. `remote.foo '{"x":"--help"}'`).
    val first = args.firstOrNull()
    if (first == "--help" || first == "-h") return null

    val env = System.getenv("SPK_EDITOR_PAIRING_URL")?.takeIf { it.isNotBlank() }

    // Heuristic: an argv that starts with the pairing URL scheme is the pairing URL.
    // Otherwise, fall back to the env var and treat argv[0] as the method.
    val (pairing, rest) = when {
        args.isNotEmpty() && args[0].startsWith("${PairingUrl.SCHEME}://") ->
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

    // `workspace <subcmd> [args...]` — translate to the real RPC method + params.
    if (rest.firstOrNull() == "workspace") {
        return parseWorkspaceSubcommand(pairing, rest.drop(1))
    }

    val method = rest.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "remote.editor.capabilities"
    val paramsJson = rest.getOrNull(1)
    return CliArgs(pairing, method, paramsJson)
}

/**
 * Translate `workspace <subcmd> [args]` into the matching MCP method + params.
 *
 * Subcommands:
 *   snapshot                                → workspace.snapshot            {}
 *   list-solutions [--open|--closed|--all]  → workspace.list_solutions      {"open": true|false|null}
 *   open-solution  <SOLUTION_ID>            → workspace.open_solution       {"solution_id": <number>}
 *   close-solution <SOLUTION_ID>            → workspace.close_solution      {"solution_id": <number>}
 *   open-session   <SESSION_ID>             → workspace.open_session        {"session_id": "..."}
 *   close-session  <SESSION_ID>             → workspace.close_session       {"session_id": "..."}
 */
private fun parseWorkspaceSubcommand(pairing: String, sub: List<String>): CliArgs? {
    val subcmd = sub.firstOrNull() ?: run {
        System.err.println("error: 'workspace' requires a subcommand (snapshot|list-solutions|open-solution|close-solution|open-session|close-session)")
        return null
    }
    return when (subcmd) {
        "snapshot" -> CliArgs(pairing, "workspace.snapshot", "{}")

        "list-solutions" -> {
            val flag = sub.getOrNull(1)
            val openValue: String = when (flag) {
                "--open"   -> "true"
                "--closed" -> "false"
                "--all", null -> "null"
                else -> {
                    System.err.println("error: list-solutions: unknown flag '$flag' (expected --open, --closed, or --all)")
                    return null
                }
            }
            CliArgs(pairing, "workspace.list_solutions", """{"open":$openValue}""")
        }

        "open-solution" -> {
            val id = sub.getOrNull(1)?.toLongOrNull() ?: run {
                System.err.println("error: open-solution requires a numeric <SOLUTION_ID>")
                return null
            }
            CliArgs(pairing, "workspace.open_solution", """{"solution_id":$id}""")
        }

        "close-solution" -> {
            val id = sub.getOrNull(1)?.toLongOrNull() ?: run {
                System.err.println("error: close-solution requires a numeric <SOLUTION_ID>")
                return null
            }
            CliArgs(pairing, "workspace.close_solution", """{"solution_id":$id}""")
        }

        "open-session" -> {
            val id = sub.getOrNull(1) ?: run {
                System.err.println("error: open-session requires <SESSION_ID>")
                return null
            }
            CliArgs(pairing, "workspace.open_session", """{"session_id":"$id"}""")
        }

        "close-session" -> {
            val id = sub.getOrNull(1) ?: run {
                System.err.println("error: close-session requires <SESSION_ID>")
                return null
            }
            CliArgs(pairing, "workspace.close_session", """{"session_id":"$id"}""")
        }

        else -> {
            System.err.println("error: unknown workspace subcommand '$subcmd'")
            null
        }
    }
}
