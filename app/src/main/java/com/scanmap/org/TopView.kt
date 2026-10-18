package com.scanmap.org

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/** Live 2D floor-plan view (metric grid, walls, rooms, path, AP pins). */
class TopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { VIEW, WALK, ROOM, WALL, AP }

    var floor: FloorData? = null
    var mode = Mode.VIEW
    var poseX = 0f
    var poseY = 0f
    var poseHeading = 0f
    var showPose = true
    var tx1m = -38
    var pathLossN = 3.0f
    var interactive = true

    val pendingPts = ArrayList<Pt>()

    var onTap: ((Pt) -> Unit)? = null
    var onLongTap: ((Pt) -> Unit)? = null

    private var camX = 0f
    private var camY = 0f
    private var scale = 60f // px per meter

    private val density = resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    // paints (light theme)
    private val pBg = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val pGrid = Paint().apply { color = 0xFFEEF2F7.toInt(); strokeWidth = 1f }
    private val pGrid5 = Paint().apply { color = 0xFFE2E8F0.toInt(); strokeWidth = 1f }
    private val pRoomFill = Paint().apply { color = 0x240D9488; style = Paint.Style.FILL }
    private val pRoomStroke = Paint().apply {
        color = 0xFF0F766E.toInt(); strokeWidth = 1.6f * density; isAntiAlias = true
    }
    private val pWall = Paint().apply {
        color = 0xFF334155.toInt(); strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val pPath = Paint().apply {
        color = 0xFF2563EB.toInt(); strokeWidth = 2.4f * density; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val pPending = Paint().apply {
        color = 0xFF0D9488.toInt(); strokeWidth = 2f * density; style = Paint.Style.STROKE
        isAntiAlias = true; pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    private val pVertex = Paint().apply { color = 0xFF0D9488.toInt(); isAntiAlias = true }
    private val pApRing = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * density; isAntiAlias = true }
    private val pAp = Paint().apply { isAntiAlias = true }
    private val pApRingFill = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        color = 0xFF0F172A.toInt(); textSize = sp(11f); isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
    }
    private val pApText = Paint().apply {
        color = 0xFF0F172A.toInt(); textSize = sp(10f); isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
    }
    private val pHud = Paint().apply {
        color = 0xFF64748B.toInt(); textSize = sp(10f); isAntiAlias = true
    }
    private val pNorth = Paint().apply {
        color = 0xFF94A3B8.toInt(); strokeWidth = 1.6f * density; isAntiAlias = true
    }

    private fun w2sX(wx: Float, w: Int) = w / 2f + (wx - camX) * scale
    private fun w2sY(wy: Float, h: Int) = h / 2f - (wy - camY) * scale
    private fun s2wX(sx: Float, w: Int) = (sx - w / 2f) / scale + camX
    private fun s2wY(sy: Float, h: Int) = (h / 2f - sy) / scale + camY

    fun centerOn(p: Pt) {
        camX = p.x; camY = p.y; invalidate()
    }

    fun fitContent() {
        val fl = floor ?: return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        fun add(px: Float, py: Float) {
            minX = min(minX, px); maxX = max(maxX, px)
            minY = min(minY, py); maxY = max(maxY, py); any = true
        }
        for (w in fl.walls) { add(w.a.x, w.a.y); add(w.b.x, w.b.y) }
        for (r in fl.rooms) for (p in r.pts) add(p.x, p.y)
        for (ap in fl.aps) add(ap.x, ap.y)
        for (p in fl.path) add(p.x, p.y)
        add(poseX, poseY)
        if (!any) { camX = 0f; camY = 0f; return }
        val pad = 2.5f
        camX = (minX + maxX) / 2f
        camY = (minY + maxY) / 2f
        val extent = max(maxX - minX, maxY - minY) + 2 * pad
        if (width > 0 && height > 0) {
            scale = (min(width, height) * 0.82f / max(extent, 4f)).coerceIn(8f, 400f)
        }
        invalidate()
    }

    fun renderToBitmap(mul: Int = 2): Bitmap {
        val b = Bitmap.createBitmap(width * mul, height * mul, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(mul.toFloat(), mul.toFloat())
        drawScene(c, width, height)
        return b
    }

    override fun onDraw(canvas: Canvas) {
        drawScene(canvas, width, height)
    }

    private fun drawScene(canvas: Canvas, w: Int, h: Int) {
        val fw = w.toFloat(); val fh = h.toFloat()
        canvas.drawRect(0f, 0f, fw, fh, pBg)

        // metric grid
        val x0 = camX - w / (2f * scale); val x1 = camX + w / (2f * scale)
        val y0 = camY - h / (2f * scale); val y1 = camY + h / (2f * scale)
        val step = if (scale >= 14f) 1f else 5f
        if ((x1 - x0) / step < 400) {
            var gx = floor(x0 / step) * step
            while (gx <= x1) {
                val sx = w2sX(gx, w)
                canvas.drawLine(sx, 0f, sx, fh, if (abs(gx % 5f) < 0.01f) pGrid5 else pGrid)
                gx += step
            }
            var gy = floor(y0 / step) * step
            while (gy <= y1) {
                val sy = w2sY(gy, h)
                canvas.drawLine(0f, sy, fw, sy, if (abs(gy % 5f) < 0.01f) pGrid5 else pGrid)
                gy += step
            }
        }

        val fl = floor
        if (fl != null) {
            // rooms
            val rp = Path()
            for (r in fl.rooms) {
                if (r.pts.size < 3) continue
                rp.reset()
                for ((i, p) in r.pts.withIndex()) {
                    val sx = w2sX(p.x, w); val sy = w2sY(p.y, h)
                    if (i == 0) rp.moveTo(sx, sy) else rp.lineTo(sx, sy)
                }
                rp.close()
                canvas.drawPath(rp, pRoomFill)
                canvas.drawPath(rp, pRoomStroke)
                val c = GeoUtils.centroid(r.pts)
                val area = r.area()
                if (area > 0.5f) {
                    val lx = w2sX(c.x, w); val ly = w2sY(c.y, h)
                    if (lx > 20 && lx < fw - 20 && ly > 20 && ly < fh - 20) {
                        canvas.drawText(String.format(java.util.Locale.US, "%.1f m²", area), lx, ly, pText)
                    }
                }
            }

            // walked path
            if (fl.path.size > 1) {
                val pp = Path()
                for ((i, p) in fl.path.withIndex()) {
                    val sx = w2sX(p.x, w); val sy = w2sY(p.y, h)
                    if (i == 0) pp.moveTo(sx, sy) else pp.lineTo(sx, sy)
                }
                canvas.drawPath(pp, pPath)
            }

            // walls
            for (wl in fl.walls) {
                pWall.strokeWidth = max(3f, wl.thickness * scale)
                canvas.drawLine(
                    w2sX(wl.a.x, w), w2sY(wl.a.y, h),
                    w2sX(wl.b.x, w), w2sY(wl.b.y, h), pWall
                )
            }

            // pending vertices (room / wall drawing)
            if (pendingPts.isNotEmpty()) {
                if (pendingPts.size > 1) {
                    val pnd = Path()
                    for ((i, p) in pendingPts.withIndex()) {
                        val sx = w2sX(p.x, w); val sy = w2sY(p.y, h)
                        if (i == 0) pnd.moveTo(sx, sy) else pnd.lineTo(sx, sy)
                    }
                    canvas.drawPath(pnd, pPending)
                }
                for (p in pendingPts) {
                    canvas.drawCircle(w2sX(p.x, w), w2sY(p.y, h), 4.5f * density, pVertex)
                }
            }

            // access points
            val maxDim = max(fw, fh)
            for (ap in fl.aps) {
                val ax = w2sX(ap.x, w); val ay = w2sY(ap.y, h)
                if (ax < -80 || ax > fw + 80 || ay < -80 || ay > fh + 80) continue
                val col = GeoUtils.gradeColor(ap.grade())
                val d = ap.let {
                    val rssi = it.rssi
                    val dd = Math.pow(10.0, (tx1m - rssi) / (10.0 * pathLossN)).toFloat()
                    dd.coerceIn(0.5f, 120f)
                } * scale
                if (d > 14f && d < maxDim) {
                    pApRing.color = col
                    pApRing.alpha = 70
                    canvas.drawCircle(ax, ay, d, pApRing)
                }
                pApRingFill.color = col
                pApRingFill.alpha = 40
                if (d > 14f && d < maxDim) canvas.drawCircle(ax, ay, d, pApRingFill)
                pAp.color = col
                canvas.drawCircle(ax, ay, 7f * density, pAp)
                canvas.drawCircle(ax, ay, 2.6f * density, pText.apply { color = 0xFFFFFFFF.toInt() })
                pText.color = 0xFF0F172A.toInt()
                val lbl = if (ap.ssid.isNotEmpty()) ap.ssid else ap.bssid.takeLast(5)
                canvas.drawText("$lbl  ${ap.rssiBest}dBm", ax + 10f * density, ay + 3.5f * density, pApText)
            }

            // current user pose
            if (showPose) {
                val px = w2sX(poseX, w); val py = w2sY(poseY, h)
                val rad = Math.toRadians(poseHeading.toDouble())
                val tipX = px + (15f * density * sin(rad)).toFloat()
                val tipY = py - (15f * density * cos(rad)).toFloat()
                pWall.color = 0xFF0EA5E9.toInt()
                pWall.strokeWidth = 2.6f * density
                canvas.drawLine(px, py, tipX, tipY, pWall)
                pAp.color = 0xFF0EA5E9.toInt()
                canvas.drawCircle(px, py, 5f * density, pAp)
                pWall.color = 0xFF334155.toInt()
            }
        }

        // north arrow
        val nx = fw - 24f * density; val ny = 30f * density
        canvas.drawLine(nx, ny + 11f * density, nx, ny - 11f * density, pNorth)
        canvas.drawText("N", nx - 4f * density, ny + 26f * density, pHud)

        // scale bar
        val candidates = floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 20f, 50f)
        var barM = 50f
        for (c in candidates) { if (c * scale >= 46f * density) { barM = c; break } }
        val barPx = barM * scale
        val bx = 14f * density; val by = fh - 16f * density
        canvas.drawLine(bx, by, bx + barPx, by, pNorth)
        canvas.drawLine(bx, by - 4f * density, bx, by + 4f * density, pNorth)
        canvas.drawLine(bx + barPx, by - 4f * density, bx + barPx, by + 4f * density, pNorth)
        canvas.drawText(
            if (barM < 1f) "50 cm" else "${barM.toInt()} m",
            bx + barPx + 6f * density, by + 4f * density, pHud
        )
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (interactive) onTap?.invoke(Pt(s2wX(e.x, width), s2wY(e.y, height)))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (interactive) onLongTap?.invoke(Pt(s2wX(e.x, width), s2wY(e.y, height)))
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            camX -= distanceX / scale
            camY += distanceY / scale
            invalidate()
            return true
        }
    })

    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val wx = s2wX(d.focusX, width)
            val wy = s2wY(d.focusY, height)
            scale = (scale * f).coerceIn(8f, 400f)
            camX = wx - (d.focusX - width / 2f) / scale
            camY = wy + (d.focusY - height / 2f) / scale
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        scaleDet.onTouchEvent(event)
        if (!scaleDet.isInProgress) gesture.onTouchEvent(event)
        return true
    }
}
