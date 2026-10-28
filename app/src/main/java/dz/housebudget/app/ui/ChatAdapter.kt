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
import dz.housebudget.app.chat.AudioPlayer
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageMedia
import dz.housebudget.app.databinding.ItemMessageBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import dz.housebudget.app.util.MediaCache

/**
 * محول المحادثة (الإصدار 1.8 — نصوص + صور + رسائل صوتية):
 *  - رسائلي أنا في فقاعة خضراء بمحاذاة النهاية، ورسائل الهاتف الآخر
 *    في فقاعة بيضاء بمحاذاة البداية (يتماشى تلقائياً مع الاتجاه RTL).
 *  - رسالة صورة: الصورة داخل الفقاعة (نقرة تعرضها بحجم كامل) + تعليق اختياري.
 *  - رسالة صوتية: زر تشغيل/إيقاف + شريط تقدم قابل للسحب + المدة — مثل واتساب،
 *    بمشغّل واحد مشترك (AudioPlayer) فتوقُف رسالة صوتية عند تشغيل أخرى.
 *  - مرفق لم يُحمّل بعد: يظهر "جاري التنزيل…" (التحميل يطلقه مالك القائمة).
 */
class ChatAdapter : ListAdapter<HouseMessage, ChatAdapter.VH>(DIFF), AudioPlayer.Listener {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HouseMessage>() {
            override fun areItemsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: HouseMessage, newItem: HouseMessage) =
                oldItem == newItem
        }
    }

    /** نقرة على صورة: تفتح العارض بحجم كامل (يربطه MessagesFragment) */
    var onImageClick: ((HouseMessage) -> Unit)? = null

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val mine = msg.senderId == BudgetRepository.myDeviceId()

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

        // حالة تحميل المرفق (صورة/صوت) من ذاكرة الوسائط المحلية
        val file = if (msg.mediaFile.isNotBlank()) {
            MediaCache.localFile(
                ctx, BudgetRepository.cachedHouseCode(), msg.mediaFile, msg.mediaType
            )
        } else null
        val ready = file != null && file.exists()

        when {
            // ---------- رسالة صورة ----------
            msg.mediaType == MessageMedia.IMAGE -> {
                b.audioRow.visibility = android.view.View.GONE

                if (ready) {
                    val bmp = MediaCache.displayBitmap(file!!)
                    if (bmp != null) {
                        b.imgMedia.visibility = android.view.View.VISIBLE
                        b.imgMedia.setImageBitmap(bmp)
                        b.imgMedia.setOnClickListener { onImageClick?.invoke(msg) }
                        b.txtText.visibility =
                            if (msg.text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                        b.txtText.text = msg.text
                    } else {
                        showDownloadState(b, msg, mine, ctx)
                    }
                } else {
                    showDownloadState(b, msg, mine, ctx)
                }
            }

            // ---------- رسالة صوتية ----------
            msg.mediaType == MessageMedia.AUDIO -> {
                b.imgMedia.visibility = android.view.View.GONE
                b.txtText.visibility = android.view.View.GONE
                b.audioRow.visibility = android.view.View.VISIBLE

                if (ready) {
                    val active = AudioPlayer.isActive(msg.id)
                    val playing = AudioPlayer.isPlaying(msg.id)

                    b.imgPlayState.alpha = 1f
                    b.imgPlayState.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    b.seekAudio.isEnabled = true
                    // المدة بميلي ثانية (Long): من المشغّل إن كان المسار نشطاً وإلا من الملف
                    val durationMs: Long = AudioPlayer.currentDurationMs()
                        .takeIf { active && it > 0 }
                        ?.toLong()
                        ?: MediaCache.audioDurationMs(file!!)
                    val positionMs = if (active) AudioPlayer.currentPositionMs() else 0

                    b.txtAudioDuration.text = MediaCache.formatDuration(
                        if (playing) positionMs.toLong() else durationMs
                    )
                    b.seekAudio.progress = if (durationMs > 0) {
                        (positionMs.toLong() * 1000L / durationMs).toInt()
                    } else 0

                    b.btnPlay.setOnClickListener {
                        AudioPlayer.toggle(file!!, msg.id, this@ChatAdapter)
                    }
                    b.seekAudio.setOnSeekBarChangeListener(object :
                        android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                        ) {
                            if (fromUser && AudioPlayer.isActive(msg.id) && durationMs > 0) {
                                AudioPlayer.seekTo((progress.toLong() * durationMs / 1000L).toInt())
                            }
                        }

                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                    })
                } else {
                    // لم يُحمّل التسجيل بعد: حالة معطلة حتى يكتمل التنزيل
                    b.imgPlayState.setImageResource(R.drawable.ic_play)
                    b.imgPlayState.alpha = 0.4f
                    b.btnPlay.setOnClickListener(null)
                    b.btnPlay.isClickable = false
                    b.seekAudio.progress = 0
                    b.seekAudio.isEnabled = false
                    b.txtAudioDuration.text = ctx.getString(R.string.media_downloading)
                }
            }

            // ---------- رسالة نصية / طلب ----------
            else -> {
                b.imgMedia.visibility = android.view.View.GONE
                b.audioRow.visibility = android.view.View.GONE
                b.txtText.visibility = android.view.View.VISIBLE
                b.txtText.text = msg.text
            }
        }
    }

    /** حالة "جاري التنزيل…" لصورة لم تصل بعد */
    private fun showDownloadState(
        b: ItemMessageBinding,
        msg: HouseMessage,
        mine: Boolean,
        ctx: android.content.Context
    ) {
        b.imgMedia.visibility = android.view.View.GONE
        b.txtText.visibility = android.view.View.VISIBLE
        b.txtText.text = if (msg.text.isNotBlank()) {
            ctx.getString(R.string.media_downloading) + "\n" + msg.text
        } else {
            ctx.getString(R.string.media_downloading)
        }
    }

    // ---------- استدعاءات المشغّل الصوتي المشترك ----------

    /** تغيّر حالة تشغيل تسجيل ظاهر في القائمة: نعيد ربط فقاعته فقط */
    override fun onPlaybackChanged(id: String) {
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** نبضة تقدم أثناء التشغيل: تحديث الشريط والمدة دون إعادة ربط كاملة */
    override fun onProgress(id: String, positionMs: Int, durationMs: Int) {
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx < 0) return
        val vh = recyclerView?.findViewHolderForAdapterPosition(idx) as? VH ?: return
        val b = vh.binding
        if (b.audioRow.visibility != android.view.View.VISIBLE) return
        b.txtAudioDuration.text = MediaCache.formatDuration(positionMs.toLong())
        if (durationMs > 0) {
            b.seekAudio.progress = positionMs * 1000 / durationMs
        }
    }

    /** مرجع القائمة لتحديث الفقاعة الظاهرة أثناء النبضات */
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        recyclerView = null
        super.onDetachedFromRecyclerView(rv)
    }
}
