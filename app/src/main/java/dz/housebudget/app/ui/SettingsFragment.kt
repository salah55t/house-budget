package dz.housebudget.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.R
import dz.housebudget.app.data.AppSettings
import dz.housebudget.app.data.PeriodTypes
import dz.housebudget.app.databinding.FragmentSettingsBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.sync.SyncForegroundService
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

/**
 * شاشة الإعدادات: الميزانية ودورتها، الوضع الاقتصادي (هدف ادخاري)،
 * إعدادات المزامنة بين الهاتفين (رابط السيرفر + رمز الربط)،
 * وقسم الإشعارات الفورية (مثل واتساب) — محلي لهذا الجهاز ولا يُزامن.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // آخر لقطة إعدادات محملة، تُستخدم لحساب معاينة الدورة الحية عند تحريك الشريط
    private var cachedSettings: AppSettings = AppSettings()

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

        // نطاق الشريط ونصوص المساعدة تتبع نوع الدورة (شهرية 1–31 / أسبوعية 1–7)
        binding.radioMonthly.setOnCheckedChangeListener { _, _ ->
            updateSalaryDayWidgets(cachedSettings.startDay)
        }
        binding.radioWeekly.setOnCheckedChangeListener { _, _ ->
            updateSalaryDayWidgets(cachedSettings.startDay)
        }

        // تحديث حي للقيمة والمعاينة أثناء تحريك شريط يوم الراتب
        binding.sliderSalaryDay.addOnChangeListener { _, _, _ -> refreshSalaryDayLabels() }

        // هدف الادخار يظهر فقط عند تفعيل الوضع الاقتصادي
        binding.switchSaving.setOnCheckedChangeListener { _, checked ->
            binding.inputLayoutSavingGoal.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.btnSaveBudget.setOnClickListener { saveBudget() }
        binding.btnSalaryToday.setOnClickListener { onSalaryDayToday() }
        binding.btnGenerateCode.setOnClickListener { binding.inputHouseCode.setText(randomCode()) }
        binding.btnTest.setOnClickListener { onTestConnection() }
        binding.btnSync.setOnClickListener { onSyncNow() }

        // الإشعارات الفورية: مفتاح محلي لهذا الجهاز (لا يُزامن) + حماية البطارية
        setupNotificationsSection()
    }

    /** قسم الإشعارات الفورية: مفتاح التفعيل + زر تجاهل تحسين البطارية */
    private fun setupNotificationsSection() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        // نضبط القيمة قبل ربط المستمع كي لا تنطلق رسالة زائفة عند الفتح
        binding.switchNotif.isChecked = prefs.getBoolean("notif_enabled", true)
        binding.switchNotif.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_enabled", checked).apply()
            if (checked) {
                // قلع الخدمة + طلب إذن الإشعارات على أندرويد 13+
                SyncForegroundService.start(requireContext())
                requestNotifPermissionIfNeeded()
                Toast.makeText(requireContext(), R.string.notif_enabled_toast, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.notif_disabled_toast, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    /** طلب إذن POST_NOTIFICATIONS على أندرويد 13+ */
    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2001
            )
        }
    }

    /**
     * حماية خدمة الإشعارات من قتل البطارية: بعض الأجهزة (شاومي، هواوي، سامسونج…)
     * توقف الخدمات الخلفية بعنف؛ تجاهل تحسين البطارية يجعل الإشعارات تعمل
     * باستمرار مثل واتساب حتى مع الشاشة مطفأة (وفي وضع Doze).
     */
    private fun requestBatteryExemption() {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            Toast.makeText(requireContext(), R.string.battery_already_ok, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + requireContext().packageName)
                )
            )
        } catch (_: Exception) {
            // بعض الأجهزة لا تدعم النافذة المباشرة: نفتح قائمة الإعدادات العامة
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun populate(settings: AppSettings) {
        // تحديث اللقطة أولاً لأن مستمعي الراديو والشريط يعتمدونها في المعاينة الحية
        cachedSettings = settings
        if (settings.totalBudget > 0) {
            binding.inputBudget.setText(FormatUtils.number(settings.totalBudget))
        }
        if (settings.periodType == PeriodTypes.WEEKLY) {
            binding.radioWeekly.isChecked = true
        } else {
            binding.radioMonthly.isChecked = true
        }
        binding.switchSaving.isChecked = settings.savingModeEnabled
        binding.inputLayoutSavingGoal.visibility =
            if (settings.savingModeEnabled) View.VISIBLE else View.GONE
        if (settings.savingGoal > 0) {
            binding.inputSavingGoal.setText(FormatUtils.number(settings.savingGoal))
        }
        binding.inputServerUrl.setText(settings.serverUrl)
        binding.inputHouseCode.setText(settings.houseCode)
        updateSalaryDayWidgets(settings.startDay)
    }

    /** ضبط شريط يوم الراتب ونطاقه حسب نوع الدورة ثم تحديث القيمة والمعاينة */
    private fun updateSalaryDayWidgets(savedDay: Int) {
        val weekly = binding.radioWeekly.isChecked
        binding.sliderSalaryDay.valueTo = (if (weekly) 7 else 31).toFloat()
        binding.sliderSalaryDay.value = savedDay.coerceIn(1, if (weekly) 7 else 31).toFloat()
        refreshSalaryDayLabels()
    }

    /** تحديث نص القيمة ونص المساعدة والمعاينة الحية لحدود الدورة من موضع الشريط */
    private fun refreshSalaryDayLabels() {
        val weekly = binding.radioWeekly.isChecked
        val day = binding.sliderSalaryDay.value.toInt()
        binding.txtSalaryDayValue.text = getString(
            if (weekly) R.string.salary_day_value_weekly
            else R.string.salary_day_value_monthly,
            day
        )
        binding.txtStartDayHelper.text = getString(
            if (weekly) R.string.start_day_hint_weekly
            else R.string.start_day_hint_monthly
        )

        // معاينة فورية: حدود الدورة الحالية لو اعتمد المستخدم هذا اليوم الآن
        val temp = cachedSettings.copy(
            periodType = if (weekly) PeriodTypes.WEEKLY else PeriodTypes.MONTHLY,
            startDay = day
        )
        val period = BudgetLogic.currentPeriod(temp)
        binding.txtCyclePreview.text = getString(
            R.string.cycle_preview,
            FormatUtils.shortDate(period.startMillis),
            FormatUtils.shortDate(period.endMillis)
        )
    }

    /** زر سريع: اعتماد يوم اليوم الحالي كبداية الشهر المالي (يوم استلام الراتب) */
    private fun onSalaryDayToday() {
        val weekly = binding.radioWeekly.isChecked
        val today = LocalDate.now()
        val day = if (weekly) today.dayOfWeek.value else today.dayOfMonth.coerceIn(1, 31)

        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.updateSettings { s -> s.copy(startDay = day) }
            cachedSettings = BudgetRepository.settingsSnapshot()
            updateSalaryDayWidgets(day)
            Toast.makeText(
                requireContext(),
                getString(R.string.salary_day_applied, day),
                Toast.LENGTH_SHORT
            ).show()

            (requireActivity().application as HouseBudgetApp).appScope.launch {
                BudgetRepository.syncNow()
            }
        }
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
        val startDay = binding.sliderSalaryDay.value.toInt()
            .coerceIn(1, if (periodType == PeriodTypes.WEEKLY) 7 else 31)
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
