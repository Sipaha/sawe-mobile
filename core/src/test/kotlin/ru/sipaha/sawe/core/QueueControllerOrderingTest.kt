package ru.sipaha.sawe.core

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * N-64: the queue flush must put frames on the wire in queue order.
 *
 * The dispatch is deliberately concurrent — waiting for each response
 * before sending the next would make a flush of a long offline queue take
 * one round trip per message. Only the *sends* need to be serialized, and
 * they used to be only by accident: the per-item children happen to run in
 * creation order while the host scope is single-threaded. On a
 * multi-threaded dispatcher, "do X" and "actually don't" could arrive in
 * either order.
 *
 * Uses a real multi-threaded dispatcher (not `TestScope`) because the bug
 * is precisely about thread scheduling.
 */
class QueueControllerOrderingTest {

    private object NoopTransport : RemoteTransport {
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteArray): Boolean = true
        override fun close(code: Int, reason: String) {}
    }

    @Test
    fun `flush sends queued messages in FIFO order on a multi-threaded scope`() = runBlocking {
        val count = 8
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryQueueStore()
        val enqueuedAt = System.currentTimeMillis()
        repeat(count) { i ->
            store.add(
                QueuedMessage(
                    id = "msg-$i",
                    method = "remote.solution_agent.send_message",
                    params = buildJsonObject { put("tag", i) },
                    enqueuedAtMs = enqueuedAt + i,
                ),
            )
        }
        val wireOrder = Collections.synchronizedList(mutableListOf<Int>())
        val controller = QueueController(
            scope = scope,
            nowMs = System::currentTimeMillis,
            queueStore = store,
            onMessageExpired = null,
            stateLock = Any(),
            transportAccessor = { NoopTransport },
            callRpc = { _, params, onSent ->
                val tag = params!!.jsonObject["tag"]!!.jsonPrimitive.content.toInt()
                // Later items are quicker to reach their send, so an
                // implementation that lets the children race will record
                // them roughly reversed.
                delay((count - tag) * 4L)
                wireOrder += tag
                onSent?.invoke(true)
                delay(20L) // response latency, must not hold up the next send
                JsonRpcResponse(id = tag.toLong())
            },
            events = Channel(Channel.UNLIMITED),
            connectionState = MutableStateFlow(ConnectionState.Connected),
        )
        controller.rehydrate()
        controller.flushQueue()

        withTimeout(10_000L) {
            while (wireOrder.size < count) delay(5L)
        }
        assertEquals((0 until count).toList(), wireOrder.toList(), "frames must go out in queue order")
        scope.cancel()
    }
}

private fun CoroutineScope.cancel() {
    (coroutineContext[kotlinx.coroutines.Job])?.cancel()
}
