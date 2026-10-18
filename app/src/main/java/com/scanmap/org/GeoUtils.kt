package com.scanmap.org

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

object GeoUtils {

    fun dist(a: Pt, b: Pt): Float = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

    fun pathLength(pts: List<Pt>): Float {
        var s = 0f
        for (i in 1 until pts.size) s += dist(pts[i - 1], pts[i])
        return s
    }

    /** Absolute polygon area in m² (shoelace). */
    fun polygonArea(pts: List<Pt>): Float {
        if (pts.size < 3) return 0f
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s / 2.0).toFloat()
    }

    fun ptSegDist(p: Pt, a: Pt, b: Pt): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val wx = p.x - a.x
        val wy = p.y - a.y
        val c1 = vx * wx + vy * wy
        if (c1 <= 0f) return dist(p, a)
        val c2 = vx * vx + vy * vy
        if (c2 <= c1) return dist(p, b)
        val t = c1 / c2
        return dist(p, Pt(a.x + t * vx, a.y + t * vy))
    }

    fun pointInPoly(p: Pt, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if (((a.y > p.y) != (b.y > p.y)) &&
                (p.x < (b.x - a.x) * (p.y - a.y) / ((b.y - a.y) + 1e-9f) + a.x)
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun centroid(pts: List<Pt>): Pt {
        if (pts.isEmpty()) return Pt(0f, 0f)
        var sx = 0.0
        var sy = 0.0
        for (p in pts) { sx += p.x; sy += p.y }
        return Pt((sx / pts.size).toFloat(), (sy / pts.size).toFloat())
    }

    /**
     * Douglas–Peucker simplification that always keeps "anchor" vertices
     * (manually marked corners) so recorded walls follow real corners.
     */
    fun simplifyAnchored(pts: List<Pt>, anchors: Set<Int>, eps: Float): List<Pt> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        for (a in anchors) if (a in pts.indices) keep[a] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeLast()
            val s = seg[0]; val e = seg[1]
            if (e - s < 2) continue
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = ptSegDist(pts[i], pts[s], pts[e]).toDouble()
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > eps) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Signal grade color (matches the reference design system). */
    fun gradeColor(grade: Int): Int = when (grade) {
        0 -> 0xFF10B981.toInt()   // excellent
        1 -> 0xFF0D9488.toInt()   // good
        2 -> 0xFFF97316.toInt()   // fair
        else -> 0xFFEF4444.toInt() // weak
    }

    fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
    fun fmt0(v: Float): String = String.format(Locale.US, "%.0f", v)

    fun sanitizeName(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N}\\-_ ]"), "").replace(' ', '-').take(40).ifEmpty { "scanmap" }

    fun sanitizeAscii(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (c.code in 33..126 && c != ' ') sb.append(c)
        val r = sb.toString().take(24)
        return r.ifEmpty { "AP" }
    }
}
