package dz.housebudget.app.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dz.housebudget.app.R
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.databinding.ItemMessageBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils

/**
 * محول المحادثة النصية:
 * رسائلي أنا في فقاعة خضراء بمحاذاة النهاية، ورسائل الهاتف الآخر
 * في فقاعة بيضاء بمحاذاة البداية (يتماشى تلقائياً مع الاتجاه RTL).
 */
class ChatAdapter : ListAdapter<HouseMessage, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HouseMessage>() {
            override fun areItemsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem == newItem
        }
    }

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val mine = msg.senderId == BudgetRepository.myDeviceId()

        b.txtText.text = msg.text
        b.txtTime.text = FormatUtils.dateTime(msg.createdAt)

        // اتجاه الفقاعة وخلفيتها ولون نصها حسب المرسل
        val lp = b.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (mine) Gravity.END else Gravity.START
        b.bubble.layoutParams = lp
        b.bubble.background = ContextCompat.getDrawable(
            ctx,
            if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_theirs
        )
        b.txtText.setTextColor(
            ContextCompat.getColor(ctx, if (mine) R.color.white else R.color.text_main)
        )
        b.txtTime.setTextColor(
            ContextCompat.getColor(ctx, if (mine) R.color.white else R.color.text_secondary)
        )
    }
}
