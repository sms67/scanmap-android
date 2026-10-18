package com.scanmap.org

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Simple telemetry overlay drawn on top of the camera preview. */
class CamHud @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var lines: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
        setShadowLayer(4f, 0f, 0f, 0xE6000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        var y = 26f * density
        val x = 12f * density
        for (l in lines) {
            canvas.drawText(l, x, y, p)
            y += 17f * density
        }
    }
}
