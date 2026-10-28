package dz.housebudget.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dz.housebudget.app.data.Categories
import dz.housebudget.app.util.FormatUtils

/**
 * رسم دائري مجوّف (Donut Chart) مرسوم يدوياً بـ Canvas
 * بدون أي مكتبات خارجية — أثقل استقراراً في البناء الآلي.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var items: List<Pair<String, Double>> = emptyList()
    private var total: Double = 0.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F2937.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(data: List<Pair<String, Double>>) {
        items = data
        total = data.sumOf { it.second }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val stroke = size * 0.13f
        paint.strokeWidth = stroke
        val pad = stroke / 2f + 6f
        rect.set(pad, (h - size) / 2f + pad, w - pad, (h + size) / 2f - pad)

        // حالة فارغة: حلقة رمادية مع رسالة
        if (total <= 0.0) {
            paint.color = 0xFFE5E7EB.toInt()
            canvas.drawArc(rect, 0f, 360f, false, paint)
            centerPaint.textSize = size * 0.10f
            canvas.drawText("لا مصاريف بعد", w / 2f, h / 2f, centerPaint)
            return
        }

        // رسم قوس كل تصنيف بلونه (اللوحة الموحدة من Categories بنفس ترتيب البيانات)
        var startAngle = -90f
        items.forEachIndexed { index, pair ->
            val value = pair.second
            if (value > 0) {
                val sweep = (value / total * 360.0).toFloat()
                paint.color = Categories.PALETTE[index % Categories.PALETTE.size]
                canvas.drawArc(rect, startAngle, sweep, false, paint)
                startAngle += sweep
            }
        }

        // النص المركزي: الإجمالي
        centerPaint.textSize = size * 0.115f
        canvas.drawText(FormatUtils.money(total), w / 2f, h / 2f, centerPaint)
        subPaint.textSize = size * 0.065f
        canvas.drawText("إجمالي المصروف", w / 2f, h / 2f + size * 0.11f, subPaint)
    }
}
