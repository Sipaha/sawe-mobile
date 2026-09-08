package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SingletonHolder] is the shared half of eight repository companions, so
 * the rules it enforces are pinned once here instead of eight times.
 *
 * The one that matters is the rebind: it is invisible when it works and
 * silent when it doesn't — a dropped provider leaves the singleton reading
 * the *first* ViewModel's active-server id forever, which shows up much
 * later as drafts and queued messages filed under the wrong server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SingletonHolderTest {

    /** Stand-in for a repository: remembers its context and its provider. */
    private class Probe(val context: Context) {
        var provider: () -> String? = { null }
    }

    private lateinit var app: Context
    private lateinit var built: AtomicInteger
    private lateinit var holder: SingletonHolder<Probe>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        built = AtomicInteger(0)
        holder = SingletonHolder { ctx ->
            built.incrementAndGet()
            Probe(ctx)
        }
    }

    @Test
    fun `the instance is built once and shared`() {
        val first = holder.get(app)
        val second = holder.get(app)

        assertSame(first, second)
        assertEquals(1, built.get())
    }

    @Test
    fun `the provider is rebound on every get`() {
        val first = holder.get(app) { it.provider = { "server-a" } }
        assertEquals("server-a", first.provider())

        val second = holder.get(app) { it.provider = { "server-b" } }

        // Same object, second caller's lambda — the whole point.
        assertSame(first, second)
        assertEquals("server-b", first.provider())
    }

    @Test
    fun `an activity-shaped context never reaches the instance`() {
        // A repository outlives any Activity; holding one would leak it for
        // the life of the process.
        val wrapper = ContextWrapper(app)

        val probe = holder.get(wrapper)

        assertNotSame(wrapper, probe.context)
        assertSame(app.applicationContext, probe.context)
    }

    @Test
    fun `a race for the first get still builds one instance`() {
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Probe>())
        repeat(threads) {
            Thread {
                start.await()
                seen += holder.get(app) { p -> p.provider = { "s" } }
                done.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, built.get())
        assertEquals(1, seen.toSet().size)
    }

    @Test
    fun `resetForTest sends the next get back to construction`() {
        val first = holder.get(app)

        holder.resetForTest()
        val second = holder.get(app)

        assertNotSame(first, second)
        assertEquals(2, built.get())
    }
}
