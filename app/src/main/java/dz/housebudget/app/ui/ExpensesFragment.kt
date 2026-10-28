package dz.housebudget.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.R
import dz.housebudget.app.data.Expense
import dz.housebudget.app.databinding.FragmentExpensesBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** شاشة سجل المصاريف: كل عمليات الدورة الحالية مع إمكانية الحذف */
class ExpensesFragment : Fragment() {

    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ExpenseAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ExpenseAdapter(onDelete = { expense -> confirmDelete(expense) })
        binding.expensesList.layoutManager = LinearLayoutManager(requireContext())
        binding.expensesList.adapter = adapter

        // مصاريف الدورة الحالية فقط مع الإجمالي
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.settingsFlow.combine(BudgetRepository.expensesFlow) { settings, expenses ->
                val period = BudgetLogic.currentPeriod(settings)
                val inPeriod = expenses.filter {
                    it.createdAt >= period.startMillis && it.createdAt <= period.endMillis
                }
                Pair(inPeriod, inPeriod.sumOf { it.amount })
            }.collect { (list, total) ->
                adapter.submitList(list)
                binding.txtTotalSpent.text = getString(R.string.spent_of, FormatUtils.money(total))
                binding.txtCount.text = getString(R.string.ops_count, list.size)
                binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.expensesList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun confirmDelete(expense: Expense) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, expense.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    BudgetRepository.deleteExpense(expense)
                    Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()

                    // مزامنة الحذف نحو الهاتف الآخر
                    (requireActivity().application as HouseBudgetApp).appScope.launch {
                        BudgetRepository.syncNow()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
