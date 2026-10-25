package dz.housebudget.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dz.housebudget.app.util.FormatUtils
import java.util.Locale

/** رسم أعمدة بسيط لصرف آخر 7 أيام — بدون أي مكتبات خارجية */
class BarsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Double> = emptyList()
    private var labels: List<String> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F2937.toInt()
        textAlign = Paint.Align.CENTER
    }

    fun setData(values: List<Double>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val labelHeight = h * 0.14f
        val valueHeight = h * 0.10f
        val bottom = h - labelHeight - 8f
        val top = valueHeight + 12f
        val maxValue = maxOf(values.maxOrNull() ?: 0.0, 1.0)
        val slot = w / values.size
        val barWidth = slot * 0.5f

        labelPaint.textSize = labelHeight * 0.75f
        valuePaint.textSize = valueHeight

        values.forEachIndexed { index, value ->
            val cx = slot * index + slot / 2f

            if (value > 0) {
                val barHeight = (value / maxValue * (bottom - top)).toFloat()
                barPaint.color = 0xFF2E7D32.toInt()
                canvas.drawRoundRect(
                    cx - barWidth / 2f, bottom - barHeight,
                    cx + barWidth / 2f, bottom, 12f, 12f, barPaint
                )
                // قيمة مختصرة فوق العمود
                valuePaint.color = 0xFF1F2937.toInt()
                canvas.drawText(shorten(value), cx, bottom - barHeight - 8f, valuePaint)
            } else {
                // شرطة رمادية قصيرة لليوم بلا صرف
                barPaint.color = 0xFFE5E7EB.toInt()
                canvas.drawRoundRect(
                    cx - barWidth / 2f, bottom - 6f,
                    cx + barWidth / 2f, bottom, 6f, 6f, barPaint
                )
            }
            canvas.drawText(labels.getOrElse(index) { "" }, cx, h - 6f, labelPaint)
        }
    }

    /** اختصار القيم الكبيرة فوق الأعمدة لتفادي التكدس */
    private fun shorten(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fم", value / 1_000_000)
        value >= 10_000 -> String.format(Locale.US, "%.0fألف", value / 1000)
        else -> FormatUtils.number(value)
    }
}
