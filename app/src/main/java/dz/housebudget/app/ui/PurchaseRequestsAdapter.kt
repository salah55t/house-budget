package dz.housebudget.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dz.housebudget.app.R
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.databinding.ItemPurchaseRequestBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import java.util.Locale

/**
 * محول قائمة انتظار الشراء:
 *  - الطلبات الواردة قيد الانتظار تُظهر خانة تحديد؛ تحديدها يُظهر خانة إدخال
 *    الثمن (معبأة مسبقاً بالسعر المقترح إن وجد) مع زرّي تأكيد الشراء ورفض الطلب.
 *  - تأكيد الشراء يُنشئ مصروفاً فيُخصم الثمن من الميزانية تلقائياً.
 *  - طلباتي أنا تُعرض بالحالة فقط دون خانة تحديد (لا أشتري من نفسي).
 */
class PurchaseRequestsAdapter(
    private val onConfirm: (HouseMessage, Double) -> Unit,
    private val onReject: (HouseMessage) -> Unit
) : ListAdapter<HouseMessage, PurchaseRequestsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HouseMessage>() {
            override fun areItemsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem == newItem
        }
    }

    class VH(val binding: ItemPurchaseRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPurchaseRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        val mine = msg.senderId == BudgetRepository.myDeviceId()
        val canAct = !mine && msg.status == MessageStatus.PENDING

        // العنوان والبيانات الوصفية: الاتجاه + وقت الإرسال
        b.txtTitle.text = msg.text
        val direction = ctx.getString(if (mine) R.string.from_me else R.string.from_other)
        b.txtMeta.text = "$direction • ${FormatUtils.dateTime(msg.createdAt)}"

        // شارة الحالة بألوانها الثلاثة
        val (bgRes, fgRes, labelRes) = when (msg.status) {
            MessageStatus.PURCHASED ->
                Triple(R.color.chip_bg_green, R.color.status_green, R.string.status_purchased)
            MessageStatus.REJECTED ->
                Triple(R.color.chip_bg_red, R.color.status_red, R.string.status_rejected)
            else ->
                Triple(R.color.chip_bg_yellow, R.color.status_yellow, R.string.status_pending)
        }
        b.txtStatus.text = ctx.getString(labelRes)
        b.txtStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bgRes))
        b.txtStatus.setTextColor(ContextCompat.getColor(ctx, fgRes))

        // سطر معلومات الثمن حسب الحالة
        when {
            msg.status == MessageStatus.PURCHASED ->
                b.txtPriceInfo.text =
                    ctx.getString(R.string.purchased_price, FormatUtils.money(msg.purchasedPrice))
            msg.suggestedPrice > 0 ->
                b.txtPriceInfo.text =
                    ctx.getString(R.string.suggested_price, FormatUtils.money(msg.suggestedPrice))
            else -> b.txtPriceInfo.text = ""
        }
        b.txtPriceInfo.visibility =
            if (b.txtPriceInfo.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // إعادة تهيئة الحالة البصرية قبل ربط المستمعين (تفادي تفاعل إعادة الاستخدام)
        b.chkSelect.setOnCheckedChangeListener(null)
        b.chkSelect.isChecked = false
        b.expandArea.visibility = View.GONE
        b.inputLayoutPrice.error = null

        // خانة التحديد: للطلبات الواردة قيد الانتظار فقط
        b.chkSelect.visibility = if (canAct) View.VISIBLE else View.GONE
        b.chkSelect.setOnCheckedChangeListener { _, checked ->
            b.expandArea.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) b.inputPrice.requestFocus()
        }

        // تعبئة خانة الثمن مسبقاً بالسعر المقترح (رقم عادي بلا فواصل آلاف
        // لأن parseAmount يفسر الفاصلة كفاصلة عشرية)
        b.inputPrice.setText(
            if (msg.suggestedPrice > 0) {
                String.format(Locale.US, "%.0f", msg.suggestedPrice)
            } else {
                ""
            }
        )

        // تأكيد الشراء: التحقق من الثمن ثم إرجاعه للشاشة لإنشاء المصروف
        b.btnConfirm.setOnClickListener {
            val price = FormatUtils.parseAmount(b.inputPrice.text?.toString().orEmpty()) ?: 0.0
            if (price <= 0.0) {
                b.inputLayoutPrice.error = ctx.getString(R.string.err_price)
            } else {
                b.inputLayoutPrice.error = null
                onConfirm(msg, price)
            }
        }

        // رفض الطلب
        b.btnReject.setOnClickListener { onReject(msg) }
    }
}
