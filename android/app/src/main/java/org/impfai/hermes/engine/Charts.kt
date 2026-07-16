package org.impfai.hermes.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 論文/科研頁圖表：android.graphics 繪製 → 預覽（Compose Image）與
 *  docx 內嵌（PNG）共用同一位圖。 */
object Charts {

    private val BAR = Color.rgb(0x2E, 0x5E, 0x4E)
    private val BAR_ALT = Color.rgb(0xC9, 0xA2, 0x27)
    private val INK = Color.rgb(0x22, 0x24, 0x21)
    private val GRID = Color.rgb(0xD8, 0xDC, 0xD2)

    fun barChart(
        title: String,
        items: List<Pair<String, Int>>,
        width: Int = 1100,
        height: Int = 640,
        altColor: Boolean = false,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = INK
        paint.textSize = 40f
        paint.isFakeBoldText = true
        c.drawText(title, 40f, 64f, paint)
        paint.isFakeBoldText = false

        if (items.isEmpty()) {
            paint.textSize = 34f
            c.drawText("（无数据）", 40f, height / 2f, paint)
            return bmp
        }
        val maxV = items.maxOf { it.second }.coerceAtLeast(1)
        val left = 220f
        val right = width - 60f
        val top = 100f
        val rowH = ((height - top - 40f) / items.size).coerceAtMost(64f)

        paint.strokeWidth = 2f
        for (g in 0..4) {
            val x = left + (right - left) * g / 4
            paint.color = GRID
            c.drawLine(x, top - 10f, x, top + rowH * items.size, paint)
            paint.color = INK
            paint.textSize = 24f
            c.drawText("${maxV * g / 4}", x - 12f, top + rowH * items.size + 30f, paint)
        }
        items.forEachIndexed { i, (label, v) ->
            val y = top + i * rowH
            paint.color = INK
            paint.textSize = 30f
            val shown = if (label.length > 7) label.take(7) + "…" else label
            c.drawText(shown, 24f, y + rowH * 0.62f, paint)
            paint.color = if (altColor) BAR_ALT else BAR
            val w = (right - left) * v / maxV
            c.drawRect(left, y + rowH * 0.18f, left + w, y + rowH * 0.78f, paint)
            paint.color = INK
            paint.textSize = 26f
            c.drawText("$v", left + w + 10f, y + rowH * 0.62f, paint)
        }
        return bmp
    }
}
