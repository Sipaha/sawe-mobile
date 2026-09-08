package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [ForegroundEdgeDetector]:
 *   - the very first 0 -> 1 transition is suppressed (cold start),
 *   - the second 0 -> 1 transition fires (genuine resume),
 *   - intermediate 1 -> 2 -> 1 transitions during a config-change-style
 *     Activity recreation do NOT fire,
 *   - background → foreground (count returns to zero, then back to 1)
 *     repeatedly fires every time,
 *   - the symmetric 1 -> 0 background edge fires (and only on a genuine
 *     one), which is what parks the wire heartbeat while the app is away
 *     (N-19).
 */
class ForegroundEdgeDetectorTest {

    private class Counter {
        var fires: Int = 0
        fun fire() {
            fires += 1
        }
    }

    @Test
    fun `first start is suppressed`() {
        val counter = Counter()
        val detector = ForegroundEdgeDetector(counter::fire)

        detector.onActivityStarted()

        assertEquals(0, counter.fires)
    }

    @Test
    fun `second cold-resume edge fires`() {
        val counter = Counter()
        val detector = ForegroundEdgeDetector(counter::fire)

        // Cold start.
        detector.onActivityStarted()
        // User backgrounds.
        detector.onActivityStopped()
        // User foregrounds again — this is the edge we care about.
        detector.onActivityStarted()

        assertEquals(1, counter.fires)
    }

    @Test
    fun `config change recreate does not fire`() {
        val counter = Counter()
        val detector = ForegroundEdgeDetector(counter::fire)

        // Cold start (suppressed).
        detector.onActivityStarted()
        // Config change: new Activity is started BEFORE old one is stopped.
        detector.onActivityStarted()
        detector.onActivityStopped()

        assertEquals(0, counter.fires)
    }

    @Test
    fun `repeated background-foreground cycles each fire`() {
        val counter = Counter()
        val detector = ForegroundEdgeDetector(counter::fire)

        // Cold start (suppressed).
        detector.onActivityStarted()
        // Three real foreground cycles.
        repeat(3) {
            detector.onActivityStopped()
            detector.onActivityStarted()
        }

        assertEquals(3, counter.fires)
    }

    @Test
    fun `the background edge fires when the last activity stops`() {
        val foreground = Counter()
        val background = Counter()
        val detector = ForegroundEdgeDetector(foreground::fire, background::fire)

        // Cold start (foreground edge suppressed) then Home.
        detector.onActivityStarted()
        detector.onActivityStopped()

        assertEquals(1, background.fires)
        assertEquals(0, foreground.fires)
    }

    @Test
    fun `config change recreate does not fire the background edge`() {
        val background = Counter()
        val detector = ForegroundEdgeDetector({ }, background::fire)

        detector.onActivityStarted()
        // New Activity starts before the old one stops — the count never
        // reaches zero, so the app never actually went away.
        detector.onActivityStarted()
        detector.onActivityStopped()

        assertEquals(0, background.fires)
    }

    @Test
    fun `each background-foreground cycle fires both edges once`() {
        val foreground = Counter()
        val background = Counter()
        val detector = ForegroundEdgeDetector(foreground::fire, background::fire)

        detector.onActivityStarted()
        repeat(3) {
            detector.onActivityStopped()
            detector.onActivityStarted()
        }

        assertEquals(3, foreground.fires)
        assertEquals(3, background.fires)
    }

    @Test
    fun `a stop with nothing started does not fire a background edge`() {
        val background = Counter()
        val detector = ForegroundEdgeDetector({ }, background::fire)

        detector.onActivityStopped()

        assertEquals(0, background.fires)
    }

    @Test
    fun `stop when already zero does not underflow`() {
        val counter = Counter()
        val detector = ForegroundEdgeDetector(counter::fire)

        // Defensive: shouldn't happen in practice (the framework pairs
        // start/stop), but if it does the count must clamp at zero so
        // the next genuine start still counts as a 0 -> 1 edge.
        detector.onActivityStopped()
        detector.onActivityStopped()
        detector.onActivityStarted()
        // This is the first reported start — still suppressed.
        detector.onActivityStopped()
        detector.onActivityStarted()

        assertEquals(1, counter.fires)
    }
}
