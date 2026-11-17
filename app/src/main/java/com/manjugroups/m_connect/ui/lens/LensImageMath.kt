package com.manjugroups.m_connect.ui.lens

/**
 * Pixel math for the capture checks, on a plain luma array (0..255, row-major)
 * so it runs and tests without Android.
 */
object LensImageMath {

    /** Rec. 601 luma of an ARGB pixel. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    fun toLuma(argb: IntArray): IntArray = IntArray(argb.size) { luma(argb[it]) }

    fun mean(gray: IntArray): Double {
        if (gray.isEmpty()) return 0.0
        var sum = 0L
        for (v in gray) sum += v
        return sum.toDouble() / gray.size
    }

    /** Mean luma of the left and right halves of a [w] x [h] image. */
    fun halves(gray: IntArray, w: Int, h: Int): Pair<Double, Double> {
        if (w < 2 || h < 1) return 0.0 to 0.0
        val mid = w / 2
        var left = 0L
        var right = 0L
        var nl = 0
        var nr = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = gray[row + x]
                if (x < mid) { left += v; nl++ } else { right += v; nr++ }
            }
        }
        return (if (nl == 0) 0.0 else left.toDouble() / nl) to (if (nr == 0) 0.0 else right.toDouble() / nr)
    }

    /**
     * Variance of the 4-neighbour Laplacian: the standard focus measure. A
     * sharp face has strong edges (high variance); motion blur flattens them.
     */
    fun laplacianVariance(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n) - mean * mean
    }
}
