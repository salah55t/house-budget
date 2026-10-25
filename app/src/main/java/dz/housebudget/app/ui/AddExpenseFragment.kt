package dz.housebudget.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.MainActivity
import dz.housebudget.app.R
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.QuickTemplate
import dz.housebudget.app.databinding.FragmentAddExpenseBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.launch

/**
 * شاشة إضافة مصروف سريعة: مبلغ + اسم + تصنيف بنقرة واحدة،
 * مع قوالب جاهزة للمشتريات المتكررة (خبز، حليب، كهرباء...) وخيار حفظ قالب جديد.
 */
class AddExpenseFragment : Fragment() {

    private var _binding: FragmentAddExpenseBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSave.setOnClickListener { saveExpense() }
        binding.btnCancel.setOnClickListener { (activity as? MainActivity)?.showHome() }

        // تحميل القوالب السريعة من قاعدة البيانات
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.templatesFlow.collect { templates -> renderTemplates(templates) }
        }
    }

    /** بناء رقائق القوالب ديناميكياً: نقرة لتعبئة الحقول، نقرة مطولة للحذف */
    private fun renderTemplates(templates: List<QuickTemplate>) {
        val context = requireContext()
        binding.templatesGroup.removeAllViews()

        val hasTemplates = templates.isNotEmpty()
        binding.templatesGroup.visibility = if (hasTemplates) View.VISIBLE else View.GONE
        binding.txtTemplatesTitle.visibility = if (hasTemplates) View.VISIBLE else View.GONE
        binding.txtTemplatesHint.visibility = if (hasTemplates) View.VISIBLE else View.GONE

        templates.forEach { template ->
            val chip = Chip(context)
            chip.text = "${template.name} • ${FormatUtils.money(template.amount)}"
            chip.isCheckable = false
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener { fillFromTemplate(template) }
            chip.setOnLongClickListener {
                confirmDeleteTemplate(template)
                true
            }
            binding.templatesGroup.addView(chip)
        }
    }

    /** تعبئة الحقول من القالب بنقرة واحدة */
    private fun fillFromTemplate(template: QuickTemplate) {
        binding.inputAmount.setText(FormatUtils.number(template.amount))
        binding.inputName.setText(template.name)
        val chipId = when (template.category) {
            Categories.FOOD -> R.id.chipFood
            Categories.BILLS -> R.id.chipBills
            Categories.FUN -> R.id.chipFun
            else -> R.id.chipOther
        }
        binding.chipGroupCategories.check(chipId)
        binding.inputLayoutAmount.error = null
    }

    private fun confirmDeleteTemplate(template: QuickTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, template.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    BudgetRepository.deleteTemplate(template)
                    Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** التحقق من المدخلات ثم الحفظ + مزامنة صامتة فورية */
    private fun saveExpense() {
        val amount = FormatUtils.parseAmount(binding.inputAmount.text?.toString().orEmpty())
        if (amount == null || amount <= 0.0) {
            binding.inputLayoutAmount.error = getString(R.string.err_amount)
            return
        }
        binding.inputLayoutAmount.error = null

        val category = when (binding.chipGroupCategories.checkedChipId) {
            R.id.chipFood -> Categories.FOOD
            R.id.chipBills -> Categories.BILLS
            R.id.chipFun -> Categories.FUN
            else -> Categories.OTHER
        }
        val name = binding.inputName.text?.toString()?.trim()
            .orEmpty().ifBlank { Categories.arabicName(category) }
        val saveAsTemplate = binding.checkSaveTemplate.isChecked

        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.addExpense(name, amount, category)
            if (saveAsTemplate) BudgetRepository.saveAsTemplate(name, amount, category)

            Toast.makeText(requireContext(), R.string.saved_ok, Toast.LENGTH_SHORT).show()

            // مزامنة صامتة في نطاق التطبيق العام (لا تتأثر بإغلاق الشاشة)
            (requireActivity().application as HouseBudgetApp).appScope.launch {
                BudgetRepository.syncNow()
            }

            (activity as? MainActivity)?.showHome()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
