package com.scanmap.org

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Live 3D view with a fixed 60° pitch camera orbiting the scene:
 * extruded walls, room floors, walked path and AP pins with labels.
 * Drag horizontally → yaw, vertically → zoom distance.
 */
class PerspectiveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var floor: FloorData? = null
    var poseX = 0f
    var poseY = 0f
    var poseHeading = 0f
    var wallHeightDefault = 2.8f

    private var yaw = 35f
    private var dist = 16f
    private val pitchDeg = 60f
    private var lastFloorRef: FloorData? = null

    private val density = resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private class Cam {
        var ex = 0f; var ey = 0f; var ez = 0f
        var rx = 0f; var ry = 0f; var rz = 0f
        var ux = 0f; var uy = 0f; var uz = 0f
        var fx = 0f; var fy = 0f; var fz = 0f
        var f = 900f

        fun proj(px: Float, py: Float, pz: Float, w: Float, h: Float, out: FloatArray): Boolean {
            val vx = px - ex; val vy = py - ey; val vz = pz - ez
            val zc = vx * fx + vy * fy + vz * fz
            if (zc < 0.25f) return false
            val xc = vx * rx + vy * ry + vz * rz
            val yc = vx * ux + vy * uy + vz * uz
            out[0] = w / 2f + f * xc / zc
            out[1] = h / 2f - f * yc / zc
            out[2] = zc
            return true
        }
    }

    private val cam = Cam()
    private val pa = FloatArray(3)
    private val pb = FloatArray(3)

    private class Face(val pts: FloatArray) {   // 4 points × 3 coords
        var scr = FloatArray(8)
        var ok = false
        var d = 0f
    }

    private class Box(val faces: ArrayList<Face>, var dcam: Float)

    // paints (light theme)
    private val pBg = Paint().apply { color = 0xFFE8EEF4.toInt() }
    private val pGrid = Paint().apply {
        color = 0xFFD3DDE7.toInt(); strokeWidth = 1f; isAntiAlias = true
    }
    private val pFace = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val pEdge = Paint().apply {
        color = 0xFF94A3B8.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val pRoomFill = Paint().apply { color = 0x330D9488; style = Paint.Style.FILL; isAntiAlias = true }
    private val pRoomStroke = Paint().apply {
        color = 0xFF0F766E.toInt(); strokeWidth = 1.4f * density; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val pPath = Paint().apply {
        color = 0xFF2563EB.toInt(); strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val pPin = Paint().apply { isAntiAlias = true }
    private val pPole = Paint().apply { strokeWidth = 1.6f * density; isAntiAlias = true }
    private val pLabel = Paint().apply {
        color = 0xFF0F172A.toInt(); textSize = sp(10f); isAntiAlias = true; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
    }
    private val pHud = Paint().apply {
        color = 0xFF64748B.toInt(); textSize = sp(10f); isAntiAlias = true
    }
    private val pNorth = Paint().apply {
        color = 0xFF94A3B8.toInt(); strokeWidth = 1.6f * density; isAntiAlias = true
    }

    fun renderToBitmap(mul: Int = 2): Bitmap {
        val b = Bitmap.createBitmap(width * mul, height * mul, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(mul.toFloat(), mul.toFloat())
        drawScene(c, width.toFloat(), height.toFloat())
        return b
    }

    override fun onDraw(canvas: Canvas) {
        drawScene(canvas, width.toFloat(), height.toFloat())
    }

    private fun drawScene(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(0xFFE8EEF4.toInt())
        val fl = floor

        // scene bounds
        var minX = poseX - 4f; var maxX = poseX + 4f
        var minY = poseY - 4f; var maxY = poseY + 4f
        if (fl != null) {
            for (wl in fl.walls) {
                minX = min(minX, min(wl.a.x, wl.b.x)); maxX = max(maxX, max(wl.a.x, wl.b.x))
                minY = min(minY, min(wl.a.y, wl.b.y)); maxY = max(maxY, max(wl.a.y, wl.b.y))
            }
            for (r in fl.rooms) for (p in r.pts) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            for (ap in fl.aps) {
                minX = min(minX, ap.x); maxX = max(maxX, ap.x)
                minY = min(minY, ap.y); maxY = max(maxY, ap.y)
            }
            for (p in fl.path) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
        }
        val pad = 2.5f
        minX -= pad; maxX += pad; minY -= pad; maxY += pad
        val tx = (minX + maxX) / 2f
        val ty = (minY + maxY) / 2f
        val extent = max(maxX - minX, maxY - minY)

        if (fl !== lastFloorRef) {
            dist = (extent * 1.15f + 6f).coerceIn(6f, 120f)
            lastFloorRef = fl
        }

        val pitch = Math.toRadians(pitchDeg.toDouble())
        val yawR = Math.toRadians(yaw.toDouble())
        val cp = cos(pitch).toFloat(); val spch = sin(pitch).toFloat()
        cam.ex = tx + dist * cp * sin(yawR)
        cam.ey = ty - dist * cp * cos(yawR)
        cam.ez = 1.2f + dist * spch
        var fx = tx - cam.ex; var fy = ty - cam.ey; var fz = 1.2f - cam.ez
        val flen = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= flen; fy /= flen; fz /= flen
        var rx = fy; var ry = -fx; var rz = 0f
        val rl = sqrt(rx * rx + ry * ry)
        if (rl < 1e-4f) { rx = 1f; ry = 0f } else { rx /= rl; ry /= rl }
        val ux = ry * fz
        val uy = -rx * fz
        val uz = rx * fy - ry * fx
        cam.fx = fx; cam.fy = fy; cam.fz = fz
        cam.rx = rx; cam.ry = ry; cam.rz = rz
        cam.ux = ux; cam.uy = uy; cam.uz = uz
        cam.f = 0.9f * max(w, h)

        // floor grid
        val step = if (extent > 40f) 5f else 1f
        var nLines = 0
        var gx = kotlin.math.floor(minX / step) * step
        while (gx <= maxX && nLines < 200) {
            if (cam.proj(gx, minY, 0f, w, h, pa) && cam.proj(gx, maxY, 0f, w, h, pb)) {
                canvas.drawLine(pa[0], pa[1], pb[0], pb[1], pGrid); nLines++
            }
            gx += step
        }
        var gy = kotlin.math.floor(minY / step) * step
        while (gy <= maxY && nLines < 400) {
            if (cam.proj(minX, gy, 0f, w, h, pa) && cam.proj(maxX, gy, 0f, w, h, pb)) {
                canvas.drawLine(pa[0], pa[1], pb[0], pb[1], pGrid); nLines++
            }
            gy += step
        }

        if (fl != null) {
            // room floors
            for (r in fl.rooms) {
                if (r.pts.size < 3) continue
                val path = Path()
                var ok = true
                for ((i, p) in r.pts.withIndex()) {
                    if (!cam.proj(p.x, p.y, 0.02f, w, h, pa)) { ok = false; break }
                    if (i == 0) path.moveTo(pa[0], pa[1]) else path.lineTo(pa[0], pa[1])
                }
                if (ok) {
                    path.close()
                    canvas.drawPath(path, pRoomFill)
                    canvas.drawPath(path, pRoomStroke)
                }
            }

            // walls (painter's algorithm)
            val boxes = ArrayList<Box>()
            for (wl in fl.walls) {
                val t = wl.thickness / 2f
                var dx = wl.b.x - wl.a.x
                var dy = wl.b.y - wl.a.y
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1e-4f) continue
                dx /= len; dy /= len
                val px = -dy * t; val py = dx * t
                val z0 = 0f; val z1 = wl.height
                val a1x = wl.a.x + px; val a1y = wl.a.y + py
                val a2x = wl.a.x - px; val a2y = wl.a.y - py
                val b1x = wl.b.x + px; val b1y = wl.b.y + py
                val b2x = wl.b.x - px; val b2y = wl.b.y - py
                val faces = ArrayList<Face>()
                faces.add(Face(floatArrayOf(a1x, a1y, z0, b1x, b1y, z0, b1x, b1y, z1, a1x, a1y, z1)))
                faces.add(Face(floatArrayOf(b2x, b2y, z0, a2x, a2y, z0, a2x, a2y, z1, b2x, b2y, z1)))
                faces.add(Face(floatArrayOf(a2x, a2y, z0, a1x, a1y, z0, a1x, a1y, z1, a2x, a2y, z1)))
                faces.add(Face(floatArrayOf(b1x, b1y, z0, b2x, b2y, z0, b2x, b2y, z1, b1x, b1y, z1)))
                faces.add(Face(floatArrayOf(a1x, a1y, z1, b1x, b1y, z1, b2x, b2y, z1, a2x, a2y, z1)))
                val cx = (wl.a.x + wl.b.x) / 2f
                val cy = (wl.a.y + wl.b.y) / 2f
                val dc = (cam.ex - cx) * (cam.ex - cx) + (cam.ey - cy) * (cam.ey - cy)
                boxes.add(Box(faces, dc))
            }
            boxes.sortByDescending { it.dcam }

            val lx = 0.4f; val ly = -0.5f; val lz = 0.75f
            for (box in boxes) {
                for (f in box.faces) {
                    f.ok = true
                    var zsum = 0f
                    var i = 0
                    while (i < 12) {
                        if (!cam.proj(f.pts[i], f.pts[i + 1], f.pts[i + 2], w, h, pa)) { f.ok = false; break }
                        f.scr[i / 3 * 2] = pa[0]
                        f.scr[i / 3 * 2 + 1] = pa[1]
                        zsum += pa[2]
                        i += 3
                    }
                    if (!f.ok) continue
                    f.d = zsum / 4f
                }
                box.faces.sortByDescending { it.d }
                for (f in box.faces) {
                    if (!f.ok) continue
                    val e1x = f.pts[3] - f.pts[0]; val e1y = f.pts[4] - f.pts[1]; val e1z = f.pts[5] - f.pts[2]
                    val e2x = f.pts[6] - f.pts[0]; val e2y = f.pts[7] - f.pts[1]; val e2z = f.pts[8] - f.pts[2]
                    var nx = e1y * e2z - e1z * e2y
                    var ny = e1z * e2x - e1x * e2z
                    var nz = e1x * e2y - e1y * e2x
                    val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    if (nl < 1e-6f) continue
                    nx /= nl; ny /= nl; nz /= nl
                    val dot = max(0f, nx * lx + ny * ly + nz * lz)
                    val br = 0.62f + 0.38f * dot
                    pFace.color = Color.rgb((248 * br).toInt(), (250 * br).toInt(), (252 * br).toInt())
                    val path = Path()
                    path.moveTo(f.scr[0], f.scr[1])
                    path.lineTo(f.scr[2], f.scr[3])
                    path.lineTo(f.scr[4], f.scr[5])
                    path.lineTo(f.scr[6], f.scr[7])
                    path.close()
                    canvas.drawPath(path, pFace)
                    canvas.drawPath(path, pEdge)
                }
            }

            // walked path
            if (fl.path.size > 1) {
                val path = Path()
                var ok = true
                for ((i, p) in fl.path.withIndex()) {
                    if (!cam.proj(p.x, p.y, 0.03f, w, h, pa)) { ok = false; break }
                    if (i == 0) path.moveTo(pa[0], pa[1]) else path.lineTo(pa[0], pa[1])
                }
                if (ok) canvas.drawPath(path, pPath)
            }

            // access points
            for (ap in fl.aps) {
                val topZ = 2.4f
                if (!cam.proj(ap.x, ap.y, 0f, w, h, pa)) continue
                if (!cam.proj(ap.x, ap.y, topZ, w, h, pb)) continue
                val col = GeoUtils.gradeColor(ap.grade())
                pPole.color = col
                canvas.drawLine(pa[0], pa[1], pb[0], pb[1], pPole)
                val r = (cam.f * 0.16f / pb[2]).coerceIn(4f * density, 26f * density)
                pPin.color = col
                canvas.drawCircle(pb[0], pb[1], r, pPin)
                val lbl = if (ap.ssid.isNotEmpty()) ap.ssid else ap.bssid.takeLast(5)
                if (pb[2] < dist * 1.6f) {
                    canvas.drawText("$lbl ${ap.rssiBest}", pb[0], pb[1] - r - 5f * density, pLabel)
                }
            }

            // pose marker
            if (cam.proj(poseX, poseY, 0.05f, w, h, pa)) {
                val rad = Math.toRadians(poseHeading.toDouble())
                val tipX = poseX + (0.5f * sin(rad)).toFloat()
                val tipY = poseY + (0.5f * cos(rad)).toFloat()
                val lX = poseX + (0.28f * sin(rad - 2.4)).toFloat()
                val lY = poseY + (0.28f * cos(rad - 2.4)).toFloat()
                val rX = poseX + (0.28f * sin(rad + 2.4)).toFloat()
                val rY = poseY + (0.28f * cos(rad + 2.4)).toFloat()
                if (cam.proj(tipX, tipY, 0.05f, w, h, pb)) {
                    val path = Path()
                    path.moveTo(pb[0], pb[1])
                    if (cam.proj(lX, lY, 0.05f, w, h, pb)) path.lineTo(pb[0], pb[1])
                    if (cam.proj(rX, rY, 0.05f, w, h, pb)) path.lineTo(pb[0], pb[1])
                    path.close()
                    pPin.color = 0xFF0EA5E9.toInt()
                    canvas.drawPath(path, pPin)
                }
            }
        }

        // north indicator
        if (cam.proj(tx, ty, 0f, w, h, pa) && cam.proj(tx, ty + 1.5f, 0f, w, h, pb)) {
            val dx = pb[0] - pa[0]; val dy = pb[1] - pa[1]
            val dl = sqrt(dx * dx + dy * dy)
            if (dl > 1f) {
                val nx = w - 26f * density; val ny = 34f * density
                val ux2 = dx / dl; val uy2 = dy / dl
                canvas.drawLine(nx, ny, nx + ux2 * 14f * density, ny + uy2 * 14f * density, pNorth)
                canvas.drawText("N", nx + ux2 * 20f * density - 4f * density, ny + uy2 * 20f * density + 4f * density, pHud)
            }
        }

        // HUD
        canvas.drawText("yaw ${yaw.toInt()}°  dist ${dist.toInt()}m", 12f * density, h - 10f * density, pHud)
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            yaw += distanceX * 0.4f
            yaw = ((yaw % 360f) + 360f) % 360f
            dist = (dist + distanceY * 0.06f).coerceIn(3f, 140f)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        return true
    }
}
