package dev.ander.alexet.data

import android.os.SystemClock
import android.view.Choreographer
import dev.ander.alexet.data.AppLog as Log

/**
 * Measures how smooth the UI is after start: gaps between frames handed out by the Choreographer.
 *
 * - The first [LOG_AFTER_MS] go to the debug log ("Debugowanie logów") as a summary.
 * - [recentAverageGapMs] gives the average gap over the last few seconds; the first-run check
 *   uses it to decide on light mode (see [isTooSlow]).
 *
 * Frames are only requested while this runs, so it stops by itself after [maxRunMs].
 * Main thread only.
 */
class FrameMonitor(private val maxRunMs: Long = LOG_AFTER_MS) {

    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var frames = 0
    private var slow = 0
    private var worstGapMs = 0L
    private var worstAtMs = 0L
    private var gapSumMs = 0L
    private var firstFrameMs = -1L
    private val slowGaps = ArrayList<String>()
    private var summaryLogged = false
    private var running = false
    private var finished = false
    private var resumedAt = 0L

    /** (time of the frame, gap before it) for the last [RECENT_MS]. */
    private val recent = ArrayDeque<Pair<Long, Long>>()

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (firstFrameMs < 0) firstFrameMs = now - startedAt
            if (lastFrameAt != 0L) {
                val gap = now - lastFrameAt
                frames++
                gapSumMs += gap
                if (gap > SLOW_GAP_MS) {
                    slow++
                    if (slowGaps.size < MAX_LISTED) slowGaps += "+${now - startedAt}ms gap=${gap}ms"
                }
                if (gap > worstGapMs) {
                    worstGapMs = gap
                    worstAtMs = now - startedAt
                }
                if (now - resumedAt >= WARMUP_MS) recent.addLast(now to gap)
                while (recent.isNotEmpty() && now - recent.first().first > RECENT_MS) recent.removeFirst()
            }
            lastFrameAt = now
            val elapsed = now - startedAt
            if (!summaryLogged && elapsed >= LOG_AFTER_MS) logSummary()
            if (elapsed >= maxRunMs) {
                running = false
                finished = true
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** Starts, or continues after [stop] (app came back to the foreground). */
    fun start() {
        if (running || finished) return
        running = true
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        // A pause (app in the background draws no frames) must not look like one long slow frame.
        lastFrameAt = 0L
        resumedAt = SystemClock.elapsedRealtime()
        recent.clear()
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /**
     * Average gap between frames over the last [RECENT_MS], or null when there are too few frames
     * to tell (nothing is drawn while the app is idle or in the background).
     */
    fun recentAverageGapMs(): Float? {
        if (recent.size < MIN_FRAMES) return null
        return recent.sumOf { it.second }.toFloat() / recent.size
    }

    private fun logSummary() {
        summaryLogged = true
        val avg = if (frames > 0) gapSumMs.toFloat() / frames else 0f
        val fps = if (avg > 0f) 1000f / avg else 0f
        Log.event("FrameMonitor") {
            "first ${LOG_AFTER_MS / 1000} s: first frame after ${firstFrameMs} ms, $frames frames, " +
                "avg gap ${"%.1f".format(avg)} ms (~${"%.1f".format(fps)} fps while drawing), " +
                "worst gap $worstGapMs ms at +${worstAtMs} ms, gaps over $SLOW_GAP_MS ms: $slow"
        }
        slowGaps.forEach { gap -> Log.event("FrameMonitor") { "slow frame $gap" } }
    }

    companion object {
        const val LOG_AFTER_MS = 10_000L
        /** Longest the first-run check waits for the folders to appear. */
        const val FIRST_RUN_MAX_MS = 120_000L
        private const val RECENT_MS = 4_000L
        /** Fewer frames say nothing: one janky frame after a resume used to decide alone. */
        private const val MIN_FRAMES = 30
        /** Frames right after (re)start are slow on any phone (resume, first composition). */
        private const val WARMUP_MS = 1_500L
        private const val SLOW_GAP_MS = 100L
        private const val MAX_LISTED = 40

        /** Below about 10 fps on average. */
        const val TOO_SLOW_GAP_MS = 100f

        fun isTooSlow(averageGapMs: Float?): Boolean = averageGapMs != null && averageGapMs > TOO_SLOW_GAP_MS
    }
}
