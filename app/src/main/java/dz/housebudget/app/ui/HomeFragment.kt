package dz.housebudget.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dz.housebudget.app.MainActivity
import dz.housebudget.app.R
import dz.housebudget.app.databinding.FragmentHomeBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.BudgetStatus
import dz.housebudget.app.util.BudgetSummary
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * الشاشة الرئيسية: بطاقة الحالة المالية بمؤشر الإفلاس التدريجي الملون،
 * إحصاءات سريعة، التنبيه الذكي، بطاقة الوضع الاقتصادي، وآخر العمليات.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var recentAdapter: ExpenseAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recentAdapter = ExpenseAdapter(onDelete = null)
        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentList.adapter = recentAdapter

        binding.btnGoSettings.setOnClickListener {
            (activity as? MainActivity)?.openTab(R.id.nav_settings)
        }

        // تدفق الملخص المالي: إعدادات + مصاريف → حساب حي يتحرك مع كل تغيير
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.settingsFlow.combine(BudgetRepository.expensesFlow) { settings, expenses ->
                val period = BudgetLogic.currentPeriod(settings)
                val spent = expenses.filter {
                    !it.deleted && it.createdAt >= period.startMillis && it.createdAt <= period.endMillis
                }.sumOf { it.amount }
                BudgetLogic.summarize(settings, spent, period)
            }.collect { summary -> renderSummary(summary) }
        }

        // تدفق آخر العمليات (أحدث 5)
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.recentExpensesFlow(5).collect { list ->
                recentAdapter.submitList(list)
                binding.txtEmptyRecent.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderSummary(summary: BudgetSummary) {
        val context = requireContext()

        // قبل ضبط الميزانية: بطاقة الإعداد فقط
        if (summary.total <= 0.0) {
            binding.cardSetup.visibility = View.VISIBLE
            binding.cardAlert.visibility = View.GONE
            binding.cardSaving.visibility = View.GONE
            binding.txtRemaining.text = FormatUtils.money(0.0)
            binding.txtBudgetLine.text = ""
            binding.txtCycleRange.visibility = View.GONE
            binding.chipStatus.text = summary.statusLabel
            binding.txtPaceInfo.text = ""
            binding.progressRemaining.setProgress(0, true)
            return
        }
        binding.cardSetup.visibility = View.GONE

        // القيم الأساسية
        binding.txtRemaining.text = FormatUtils.money(summary.remaining)
        binding.txtBudgetLine.text = getString(
            R.string.of_budget,
            FormatUtils.money(summary.total),
            FormatUtils.money(summary.spent)
        )

        // فترة الدورة الحالية (من يوم الراتب إلى قبل يوم الراتب التالي)
        binding.txtCycleRange.visibility = View.VISIBLE
        binding.txtCycleRange.text = getString(
            R.string.cycle_range_home,
            FormatUtils.shortDate(summary.period.startMillis),
            FormatUtils.shortDate(summary.period.endMillis)
        )

        // لون مؤشر الصحة المالية: أخضر / أصفر / أحمر
        val colorRes = when (summary.status) {
            BudgetStatus.HEALTHY -> R.color.status_green
            BudgetStatus.WARNING -> R.color.status_yellow
            BudgetStatus.DANGER -> R.color.status_red
        }
        val color = ContextCompat.getColor(context, colorRes)
        binding.progressRemaining.setProgress(summary.spentPercent.toInt().coerceIn(0, 100), true)
        binding.progressRemaining.setIndicatorColor(color)

        val bgRes = when (summary.status) {
            BudgetStatus.HEALTHY -> R.color.chip_bg_green
            BudgetStatus.WARNING -> R.color.chip_bg_yellow
            BudgetStatus.DANGER -> R.color.chip_bg_red
        }
        binding.chipStatus.text = summary.statusLabel
        binding.chipStatus.setTextColor(color)
        binding.chipStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, bgRes))

        // سطر الوتيرة: نسبة المال المتبقي مقابل نسبة الأيام المتبقية
        val daysLeftPercent = 100.0 - 100.0 * summary.period.daysElapsed / summary.period.daysTotal
        binding.txtPaceInfo.text = getString(
            R.string.pace_info,
            FormatUtils.percent(summary.remainingPercent),
            FormatUtils.percent(daysLeftPercent)
        )

        // الإحصاءات السريعة
        binding.txtDaysLeft.text = getString(R.string.days_value, summary.period.daysLeft)
        binding.txtDailyAllowed.text = FormatUtils.money(summary.dailyAllowed)
        binding.txtDailyAvg.text = FormatUtils.money(summary.dailyAverageSpent)

        // التنبيه الذكي (يظهر عند وجود خطر فعلي فقط)
        if (summary.riskMessage != null) {
            binding.cardAlert.visibility = View.VISIBLE
            binding.txtAlertMessage.text = summary.riskMessage
        } else {
            binding.cardAlert.visibility = View.GONE
        }

        // بطاقة الوضع الاقتصادي
        if (summary.savingModeEnabled) {
            binding.cardSaving.visibility = View.VISIBLE
            val remainingToSave = min(summary.savingGoal, maxOf(summary.remaining, 0.0))
            binding.txtSavingInfo.text = getString(
                R.string.saving_info,
                FormatUtils.money(summary.savingGoal),
                FormatUtils.money(remainingToSave)
            )
            binding.txtSavingDaily.text =
                getString(R.string.saving_daily, FormatUtils.money(summary.dailyAllowed))
        } else {
            binding.cardSaving.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
