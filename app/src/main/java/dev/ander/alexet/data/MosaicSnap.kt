package dev.ander.alexet.data

import kotlin.math.abs

/** Pulling the edges of a moved or resized mosaic photo to the edges of the others. */
object MosaicSnap {
    /**
     * Where a piece [size] long, moved to [pos], should go: its start or end on the start or end of
     * one of [others] (as start to end), the nearest within [within]; [pos] itself when none is.
     */
    fun snapPosition(pos: Float, size: Float, others: List<Pair<Float, Float>>, within: Float): Float {
        var best = pos
        var bestDistance = within
        for ((start, end) in others) {
            for (candidate in floatArrayOf(start, end, start - size, end - size)) {
                val d = abs(candidate - pos)
                if (d <= bestDistance) {
                    bestDistance = d
                    best = candidate
                }
            }
        }
        return best
    }

    /** [edge] pulled to the nearest start or end of [others] within [within]; null when none is that near. */
    fun snapEdge(edge: Float, others: List<Pair<Float, Float>>, within: Float): Float? {
        var best: Float? = null
        var bestDistance = within
        for ((start, end) in others) {
            for (candidate in floatArrayOf(start, end)) {
                val d = abs(candidate - edge)
                if (d <= bestDistance) {
                    bestDistance = d
                    best = candidate
                }
            }
        }
        return best
    }

    /** The photos side by side in one row, in the given order, each in its own resolution. */
    fun initialRow(sources: List<MosaicSource>): List<MosaicPiece> {
        var x = 0f
        return sources.map { source ->
            MosaicPiece(source, x, 0f, 1f).also { x += source.width }
        }
    }
}
