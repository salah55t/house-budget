package dz.housebudget.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dz.housebudget.app.R
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.Expense
import dz.housebudget.app.databinding.FragmentReportsBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** بيانات التقرير المجمعة في تدفق واحد */
private data class ReportsData(
    val periodExpenses: List<Expense>,
    val allExpenses: List<Expense>,
    val daysElapsed: Int
)

/**
 * شاشة التقارير: رسم دائري مجوف لتوزيع التصنيفات مع مفتاح تفصيلي،
 * أعمدة صرف آخر 7 أيام، وملخص رقمي (أعلى تصنيف، عدد العمليات، المتوسط اليومي).
 */
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.settingsFlow.combine(BudgetRepository.expensesFlow) { settings, expenses ->
                val period = BudgetLogic.currentPeriod(settings)
                val inPeriod = expenses.filter {
                    it.createdAt >= period.startMillis && it.createdAt <= period.endMillis
                }
                ReportsData(inPeriod, expenses, period.daysElapsed)
            }.collect { data -> render(data) }
        }
    }

    private fun render(data: ReportsData) {
        val context = requireContext()

        // 1) توزيع المصاريف على التصنيفات
        val byCategory = Categories.all.map { cat ->
            cat to data.periodExpenses.filter { it.category == cat }.sumOf { it.amount }
        }
        val totalSpent = byCategory.sumOf { it.second }
        binding.donutChart.setData(byCategory)

        // 2) مفتاح الرسم: صف لكل تصنيف (لون + اسم + مبلغ + نسبة)
        binding.legendContainer.removeAllViews()
        byCategory.forEachIndexed { index, pair ->
            val cat = pair.first
            val value = pair.second
            if (value <= 0.0) return@forEachIndexed

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val dot = View(context)
            dot.layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            dot.setBackgroundResource(R.drawable.bg_cat_dot)
            dot.backgroundTintList =
                ColorStateList.valueOf(dz.housebudget.app.view.DonutChartView.COLORS[index % 4])
            row.addView(dot)

            val nameView = TextView(context)
            nameView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
            nameView.text = Categories.arabicName(cat)
            nameView.setTextColor(ContextCompat.getColor(context, R.color.text_main))
            nameView.textSize = 14f
            row.addView(nameView)

            val amountView = TextView(context)
            amountView.text = "${FormatUtils.money(value)} (${FormatUtils.percent(if (totalSpent > 0) value / totalSpent * 100.0 else 0.0)})"
            amountView.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            amountView.textSize = 13f
            row.addView(amountView)

            binding.legendContainer.addView(row)
        }

        // 3) أعمدة صرف آخر 7 أيام
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val values = ArrayList<Double>()
        val labels = ArrayList<String>()
        for (offset in 6 downTo 0) {
            val day = today.minusDays(offset.toLong())
            val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            values.add(data.allExpenses.filter { it.createdAt in startMs..endMs }.sumOf { it.amount })
            labels.add("${day.dayOfMonth}")
        }
        binding.barsChart.setData(values, labels)

        // 4) الملخص الرقمي
        if (data.periodExpenses.isEmpty()) {
            binding.txtTopCategory.text = getString(R.string.no_data)
            binding.txtOpsTotal.text = ""
            binding.txtAvgDaily.text = ""
        } else {
            val top = byCategory.maxByOrNull { it.second }
            binding.txtTopCategory.text = getString(
                R.string.top_category,
                Categories.arabicName(top?.first ?: Categories.OTHER),
                FormatUtils.money(top?.second ?: 0.0)
            )
            binding.txtOpsTotal.text = getString(R.string.ops_total, data.periodExpenses.size)
            binding.txtAvgDaily.text =
                getString(R.string.avg_daily, FormatUtils.money(totalSpent / max(data.daysElapsed, 1)))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
