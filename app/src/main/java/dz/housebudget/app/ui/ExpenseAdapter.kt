package dz.housebudget.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.Expense
import dz.housebudget.app.databinding.ItemExpenseBinding
import dz.housebudget.app.util.FormatUtils

/**
 * محول قائمة المصاريف: صف لكل عملية مع نقطة ملونة بالتصنيف.
 * زر الحذف يظهر فقط عندما يمرر الشاشة دالة حذف (تختفي في الرئيسية).
 */
class ExpenseAdapter(private val onDelete: ((Expense) -> Unit)?) :
    ListAdapter<Expense, ExpenseAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Expense>() {
            override fun areItemsTheSame(oldItem: Expense, newItem: Expense): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Expense, newItem: Expense): Boolean =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemExpenseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context

        holder.binding.txtName.text = item.name
        holder.binding.txtDate.text = FormatUtils.dateTime(item.createdAt)
        holder.binding.txtCategory.text = Categories.arabicName(item.category)
        holder.binding.txtAmount.text = "- " + FormatUtils.money(item.amount)

        // لون نقطة التصنيف من اللوحة الموحدة (القيم القديمة غير المعروفة = لون "أخرى")
        holder.binding.catDot.backgroundTintList =
            ColorStateList.valueOf(Categories.color(item.category))

        if (onDelete != null) {
            holder.binding.btnDelete.visibility = View.VISIBLE
            holder.binding.btnDelete.setOnClickListener { onDelete.invoke(item) }
        } else {
            holder.binding.btnDelete.visibility = View.GONE
        }
    }
}
