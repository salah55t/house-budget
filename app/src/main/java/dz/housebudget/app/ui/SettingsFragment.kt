package dz.housebudget.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.R
import dz.housebudget.app.data.AppSettings
import dz.housebudget.app.data.PeriodTypes
import dz.housebudget.app.databinding.FragmentSettingsBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * شاشة الإعدادات: الميزانية ودورتها، الوضع الاقتصادي (هدف ادخاري)،
 * وإعدادات المزامنة بين الهاتفين (رابط السيرفر + رمز الربط).
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // تعبئة القيم المحفوظة مرة واحدة عند فتح الشاشة
        viewLifecycleOwner.lifecycleScope.launch {
            populate(BudgetRepository.settingsSnapshot())
        }

        // نص مساعدة يوم البداية يتبع نوع الدورة
        binding.radioMonthly.setOnCheckedChangeListener { _, _ -> updateStartDayHelper() }
        binding.radioWeekly.setOnCheckedChangeListener { _, _ -> updateStartDayHelper() }

        // هدف الادخار يظهر فقط عند تفعيل الوضع الاقتصادي
        binding.switchSaving.setOnCheckedChangeListener { _, checked ->
            binding.inputLayoutSavingGoal.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.btnSaveBudget.setOnClickListener { saveBudget() }
        binding.btnGenerateCode.setOnClickListener { binding.inputHouseCode.setText(randomCode()) }
        binding.btnTest.setOnClickListener { onTestConnection() }
        binding.btnSync.setOnClickListener { onSyncNow() }
    }

    private fun populate(settings: AppSettings) {
        if (settings.totalBudget > 0) {
            binding.inputBudget.setText(FormatUtils.number(settings.totalBudget))
        }
        if (settings.periodType == PeriodTypes.WEEKLY) {
            binding.radioWeekly.isChecked = true
        } else {
            binding.radioMonthly.isChecked = true
        }
        binding.inputStartDay.setText(settings.startDay.toString())
        binding.switchSaving.isChecked = settings.savingModeEnabled
        binding.inputLayoutSavingGoal.visibility =
            if (settings.savingModeEnabled) View.VISIBLE else View.GONE
        if (settings.savingGoal > 0) {
            binding.inputSavingGoal.setText(FormatUtils.number(settings.savingGoal))
        }
        binding.inputServerUrl.setText(settings.serverUrl)
        binding.inputHouseCode.setText(settings.houseCode)
        updateStartDayHelper()
    }

    private fun updateStartDayHelper() {
        binding.inputLayoutStartDay.helperText = getString(
            if (binding.radioWeekly.isChecked) R.string.start_day_hint_weekly
            else R.string.start_day_hint_monthly
        )
    }

    /** حفظ إعدادات الميزانية + مزامنة صامتة نحو الهاتف الآخر */
    private fun saveBudget() {
        val budget = FormatUtils.parseAmount(binding.inputBudget.text?.toString().orEmpty()) ?: 0.0
        if (budget <= 0.0) {
            binding.inputLayoutBudget.error = getString(R.string.err_budget)
            return
        }
        binding.inputLayoutBudget.error = null

        val periodType = if (binding.radioWeekly.isChecked) PeriodTypes.WEEKLY else PeriodTypes.MONTHLY
        val startDayRaw = binding.inputStartDay.text?.toString()?.toIntOrNull() ?: 1
        val startDay = startDayRaw.coerceIn(1, if (periodType == PeriodTypes.WEEKLY) 7 else 31)
        val goal = FormatUtils.parseAmount(binding.inputSavingGoal.text?.toString().orEmpty()) ?: 0.0
        val savingEnabled = binding.switchSaving.isChecked

        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.updateSettings { s ->
                s.copy(
                    totalBudget = budget,
                    periodType = periodType,
                    startDay = startDay,
                    savingGoal = if (savingEnabled) goal else 0.0,
                    savingModeEnabled = savingEnabled
                )
            }
            Toast.makeText(requireContext(), R.string.budget_saved_ok, Toast.LENGTH_SHORT).show()

            (requireActivity().application as HouseBudgetApp).appScope.launch {
                BudgetRepository.syncNow()
            }
        }
    }

    /** حفظ حقلي المزامنة (الرابط والرمز) قبل أي عملية شبكة */
    private suspend fun persistSyncFields() {
        BudgetRepository.updateSettings { s ->
            s.copy(
                serverUrl = binding.inputServerUrl.text?.toString()?.trim().orEmpty(),
                houseCode = binding.inputHouseCode.text?.toString()?.trim()?.uppercase().orEmpty()
            )
        }
    }

    private fun onTestConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            persistSyncFields()
            binding.txtSyncStatus.text = getString(R.string.syncing)
            val error = BudgetRepository.testConnection()
            binding.txtSyncStatus.text =
                if (error == null) getString(R.string.connection_ok) else error
        }
    }

    private fun onSyncNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            persistSyncFields()
            binding.txtSyncStatus.text = getString(R.string.syncing)
            binding.btnSync.isEnabled = false
            val result = BudgetRepository.syncNow()
            binding.btnSync.isEnabled = true
            binding.txtSyncStatus.text = when (result) {
                is BudgetRepository.SyncResult.Success -> getString(R.string.sync_ok)
                is BudgetRepository.SyncResult.Error -> result.message
            }
        }
    }

    /** توليد رمز ربط من 6 محارف سهلة القراءة (بدون أحرف متشابهة) */
    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
