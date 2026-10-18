package com.scanmap.org

import java.util.Locale

/**
 * OBJ exporter — metric units, Z-up. Emits wall boxes, triangulated room
 * floors and named AP marker groups (importable in 3ds Max via File → Import).
 */
object ObjExporter {

    fun export(floors: List<FloorData>, tx1m: Int, pathLossN: Float): String {
        val sb = StringBuilder()
        sb.append("# ScanMap Organizational — 3D export\n")
        sb.append("# units: meters — Z axis is up\n")
        sb.append("# 3ds Max: File > Import > Geometry > OBJ\n\n")
        var vc = 0

        fun v(x: Float, y: Float, z: Float): Int {
            vc++
            sb.append(String.format(Locale.US, "v %.3f %.3f %.3f%n", x, y, z))
            return vc
        }

        fun f4(a: Int, b: Int, c: Int, d: Int) {
            sb.append("f ").append(a).append(" ").append(b).append(" ").append(c).append(" ").append(d).append('\n')
        }

        fun f3(a: Int, b: Int, c: Int) {
            sb.append("f ").append(a).append(" ").append(b).append(" ").append(c).append('\n')
        }

        for (fl in floors) {
            val zOff = fl.level * 3.2f
            val fname = GeoUtils.sanitizeAscii(fl.name)

            // ── walls ──
            sb.append("g ").append(fname).append("_Walls\n")
            for (w in fl.walls) {
                val t = w.thickness / 2f
                var dx = w.b.x - w.a.x
                var dy = w.b.y - w.a.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                if (len < 1e-4f) continue
                dx /= len; dy /= len
                val px = -dy * t
                val py = dx * t
                val z0 = zOff
                val z1 = zOff + w.height
                val a1 = v(w.a.x + px, w.a.y + py, z0)
                val b1 = v(w.b.x + px, w.b.y + py, z0)
                val b2 = v(w.b.x - px, w.b.y - py, z0)
                val a2 = v(w.a.x - px, w.a.y - py, z0)
                val a1t = v(w.a.x + px, w.a.y + py, z1)
                val b1t = v(w.b.x + px, w.b.y + py, z1)
                val b2t = v(w.b.x - px, w.b.y - py, z1)
                val a2t = v(w.a.x - px, w.a.y - py, z1)
                f4(a1, b1, b1t, a1t)   // side +
                f4(b2, a2, a2t, b2t)   // side -
                f4(a2, a1, a1t, a2t)   // end A
                f4(b1, b2, b2t, b1t)   // end B
                f4(a1t, b1t, b2t, a2t) // top
            }

            // ── room floors ──
            for ((ri, r) in fl.rooms.withIndex()) {
                if (r.pts.size < 3) continue
                val area = r.area()
                sb.append("g ").append(fname).append("_Room").append(ri + 1)
                    .append("_Area").append(String.format(Locale.US, "%.1f", area)).append("sqm\n")
                val idx = ArrayList<Int>()
                for (p in r.pts) idx.add(v(p.x, p.y, zOff + 0.02f))
                for (tri in triangulate(r.pts)) {
                    f3(idx[tri[0]], idx[tri[1]], idx[tri[2]])
                }
            }

            // ── access points ──
            for ((ai, ap) in fl.aps.withIndex()) {
                val dist = Math.pow(10.0, (tx1m - ap.rssi) / (10.0 * pathLossN)).toFloat().coerceIn(0.5f, 120f)
                val label = if (ap.ssid.isNotEmpty()) GeoUtils.sanitizeAscii(ap.ssid) else "AP${ai + 1}"
                sb.append("g AP_").append(label)
                    .append("_RSSI").append(ap.rssiBest)
                    .append("_Dist").append(String.format(Locale.US, "%.1f", dist)).append("m\n")
                val cx = ap.x
                val cy = ap.y
                val cz = zOff + 2.55f
                val r = 0.28f
                val top = v(cx, cy, cz + r)
                val bot = v(cx, cy, cz - r)
                val n = v(cx, cy + r, cz)
                val e = v(cx + r, cy, cz)
                val s = v(cx, cy - r, cz)
                val wpt = v(cx - r, cy, cz)
                f3(top, n, e); f3(top, e, s); f3(top, s, wpt); f3(top, wpt, n)
                f3(bot, e, n); f3(bot, s, e); f3(bot, wpt, s); f3(bot, n, wpt)
                // drop line to floor (thin quad)
                val lw = 0.04f
                val l1 = v(cx - lw, cy, zOff)
                val l2 = v(cx + lw, cy, zOff)
                val l3 = v(cx + lw, cy, cz - r)
                val l4 = v(cx - lw, cy, cz - r)
                f4(l1, l2, l3, l4)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun exportCsv(floors: List<FloorData>, tx1m: Int, pathLossN: Float): String {
        val sb = StringBuilder()
        sb.append("Floor,BSSID,SSID,Band,Channel,Security,RSSI_best_dBm,RSSI_smooth_dBm,")
        sb.append("Distance_m,Confidence,Samples,X_m,Y_m,First_seen,Last_seen,Manual\n")
        for (fl in floors) {
            for (ap in fl.aps) {
                val dist = Math.pow(10.0, (tx1m - ap.rssi) / (10.0 * pathLossN)).toFloat().coerceIn(0.5f, 120f)
                val ssid = ap.ssid.replace(",", " ").ifEmpty { "Hidden" }
                sb.append('"').append(fl.name).append('"').append(',')
                sb.append(ap.bssid).append(',')
                sb.append('"').append(ssid).append('"').append(',')
                sb.append(ap.bandLabel()).append(',')
                sb.append(ap.channel()).append(',')
                sb.append('"').append(ap.security.replace(",", " ")).append('"').append(',')
                sb.append(ap.rssiBest).append(',')
                sb.append(String.format(Locale.US, "%.1f", ap.rssi)).append(',')
                sb.append(String.format(Locale.US, "%.2f", dist)).append(',')
                sb.append(String.format(Locale.US, "%.2f", ap.confidence)).append(',')
                sb.append(ap.samples.size).append(',')
                sb.append(String.format(Locale.US, "%.2f", ap.x)).append(',')
                sb.append(String.format(Locale.US, "%.2f", ap.y)).append(',')
                sb.append(ap.firstSeen).append(',')
                sb.append(ap.lastSeen).append(',')
                sb.append(if (ap.manual) "yes" else "no").append('\n')
            }
        }
        return sb.toString()
    }

    /** Ear-clipping triangulation of a simple polygon. */
    fun triangulate(pts: List<Pt>): List<IntArray> {
        val n = pts.size
        val res = ArrayList<IntArray>()
        if (n < 3) return res
        var sa = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            sa += a.x * b.y - b.x * a.y
        }
        val idx = ArrayList<Int>()
        if (sa < 0) {
            for (i in n - 1 downTo 0) idx.add(i)
        } else {
            for (i in 0 until n) idx.add(i)
        }
        var guard = 0
        while (idx.size > 3 && guard < 20000) {
            guard++
            var earFound = false
            for (k in 0 until idx.size) {
                val i0 = idx[(k + idx.size - 1) % idx.size]
                val i1 = idx[k]
                val i2 = idx[(k + 1) % idx.size]
                val a = pts[i0]; val b = pts[i1]; val c = pts[i2]
                val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
                if (cross <= 1e-7f) continue
                var contains = false
                for (j in idx) {
                    if (j == i0 || j == i1 || j == i2) continue
                    if (inTri(pts[j], a, b, c)) { contains = true; break }
                }
                if (!contains) {
                    res.add(intArrayOf(i0, i1, i2))
                    idx.removeAt(k)
                    earFound = true
                    break
                }
            }
            if (!earFound) break
        }
        if (idx.size == 3) {
            res.add(intArrayOf(idx[0], idx[1], idx[2]))
        } else if (idx.size > 3) {
            for (k in 1 until idx.size - 1) res.add(intArrayOf(idx[0], idx[k], idx[k + 1]))
        }
        return res
    }

    private fun sign(p: Pt, a: Pt, b: Pt): Float =
        (a.x - p.x) * (b.y - p.y) - (a.y - p.y) * (b.x - p.x)

    private fun inTri(p: Pt, a: Pt, b: Pt, c: Pt): Boolean {
        val d1 = sign(p, a, b)
        val d2 = sign(p, b, c)
        val d3 = sign(p, c, a)
        val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        val hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }
}
