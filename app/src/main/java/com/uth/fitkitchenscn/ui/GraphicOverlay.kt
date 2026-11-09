package com.uth.fitkitchenscn.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.uth.fitkitchenscn.model.DetectedBox
import kotlin.math.max

class GraphicOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC101A19.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var imageWidth = 1
    private var imageHeight = 1
    private var isFrontCamera = false
    private val boxes = mutableListOf<DetectedBox>()

    fun updateImageInfo(width: Int, height: Int, frontCamera: Boolean) {
        imageWidth = max(1, width)
        imageHeight = max(1, height)
        isFrontCamera = frontCamera
        invalidate()
    }

    fun setDetections(detections: List<DetectedBox>) {
        boxes.clear()
        boxes.addAll(detections)
        postInvalidate()
    }

    fun clear() {
        boxes.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)
        val translateX = (width - imageWidth * scale) / 2f
        val translateY = (height - imageHeight * scale) / 2f

        boxes.forEach { detection ->
            val source = detection.rect
            val left = source.left * scale * if (isFrontCamera) -1f else 1f +
                if (isFrontCamera) width - translateX else translateX
            val right = source.right * scale * if (isFrontCamera) -1f else 1f +
                if (isFrontCamera) width - translateX else translateX
            val top = source.top * scale + translateY
            val bottom = source.bottom * scale + translateY
            val rect = RectF(minOf(left, right), top, maxOf(left, right), bottom)
            canvas.drawRoundRect(rect, 18f, 18f, boxPaint)

            val label = "${detection.label.replaceFirstChar { it.uppercase() }} " +
                "(${(detection.confidence * 100).toInt()}%)"
            val textWidth = labelPaint.measureText(label)
            val labelTop = max(0f, rect.top - 52f)
            canvas.drawRoundRect(
                rect.left,
                labelTop,
                rect.left + textWidth + 28f,
                rect.top,
                10f,
                10f,
                labelBackgroundPaint
            )
            canvas.drawText(label, rect.left + 14f, rect.top - 16f, labelPaint)
        }
    }
}
