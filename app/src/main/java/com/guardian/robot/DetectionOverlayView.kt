package com.guardian.robot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var frameWidth = 640
    private var frameHeight = 480
    private var currentZone = ZONE_GREEN

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val labelMap = mapOf(
        1 to "persona", 16 to "gato", 17 to "perro",
        18 to "caballo", 19 to "oveja", 20 to "vaca",
        21 to "elefante", 22 to "oso", 23 to "cebra", 24 to "jirafa"
    )

    private val intruderClasses = setOf(1, 16, 17, 18, 19, 20, 21, 22, 23, 24)

    companion object {
        const val ZONE_GREEN = 0
        const val ZONE_YELLOW = 1
        const val ZONE_RED = 2
    }

    init {
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 4f

        textPaint.color = Color.WHITE
        textPaint.textSize = 32f
        textPaint.typeface = Typeface.DEFAULT_BOLD

        textBgPaint.style = Paint.Style.FILL
        textBgPaint.color = Color.argb(180, 0, 0, 0)

        zonePaint.style = Paint.Style.FILL
    }

    fun setDetections(newDetections: List<Detection>, fw: Int, fh: Int, zone: Int) {
        detections = newDetections.filter {
            val catId = it.categories.firstOrNull()?.index ?: -1
            catId in intruderClasses
        }
        frameWidth = fw
        frameHeight = fh
        currentZone = zone
        postInvalidate()
    }

    fun clearDetections() {
        detections = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val scaleX = width.toFloat() / frameWidth
        val scaleY = height.toFloat() / frameHeight

        for (d in detections) {
            val catId = d.categories.firstOrNull()?.index ?: continue
            val score = d.categories.firstOrNull()?.score ?: continue
            val box = d.boundingBox

            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            val color = getZoneColor(catId)
            boxPaint.color = color

            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, boxPaint)

            val label = "${labelMap[catId] ?: "obj_$catId"} ${(score * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize

            val labelLeft = left
            val labelTop = top - textHeight - 8f
            val labelRight = left + textWidth + 16f
            val labelBottom = top

            canvas.drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 8f, 8f, textBgPaint)
            canvas.drawText(label, left + 8f, top - 8f, textPaint)
        }
    }

    private fun getZoneColor(catId: Int): Int {
        return when (catId) {
            1 -> Color.RED
            in 17..24 -> Color.YELLOW
            else -> Color.GREEN
        }
    }
}
