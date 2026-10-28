package dz.housebudget.app.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dz.housebudget.app.util.MediaCache
import java.io.File

/**
 * مشغّل الرسائل الصوتية المشترك (الإصدار 1.8 — مثل واتساب):
 *
 *  - مشغّل واحد للتطبيق كله: تشغيل تسجيل واحد في كل لحظة، والنقر على
 *    تسجيل آخر يوقف الأول فوراً.
 *  - النقر على نفس التسجيل: تشغيل ↔ إيقاف مؤقت ثم استكمال من نفس الموضع.
 *  - نبضة تقدم كل 200 ميلي ثانية عبر واجهة Listener (الشريط في الفقاعة).
 *  - عند انتهاء التسجيل تلقائياً: تحرير الموارد وإبلاغ الواجهة بالتوقف.
 */
object AudioPlayer {

    /** مستقبل أحداث التشغيل (يستخدمه محول المحادثة لتحديث الفقاعة) */
    interface Listener {
        /** تغيّر حالة تسجيل معين (بدأ تشغيله / توقف / انتهى) — يُعاد رسم الفقاعة */
        fun onPlaybackChanged(id: String)

        /** نبضة تقدم أثناء التشغيل: الموضع والمدة الكلية بالميلي ثانية */
        fun onProgress(id: String, positionMs: Int, durationMs: Int)
    }

    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            val id = currentId
            if (p != null && id != null && p.isPlaying) {
                listener?.onProgress(id, p.currentPosition, p.duration)
                handler.postDelayed(this, 200)
            }
        }
    }

    /** هل هذا التسجيل يعمل الآن (وليس متوقفاً مؤقتاً)؟ */
    fun isPlaying(id: String): Boolean = currentId == id && player?.isPlaying == true

    /** هل هذا التسجيل هو الحالي (يعمل أو متوقف مؤقتاً)؟ */
    fun isActive(id: String): Boolean = currentId == id

    /** موضع التشغيل الحالي للمسار النشط (لعرضه عند إعادة ربط الفقاعة) */
    fun currentPositionMs(): Int = if (currentId != null) player?.currentPosition ?: 0 else 0

    /** مدة المسار النشط الحالي */
    fun currentDurationMs(): Int = if (currentId != null) player?.duration ?: 0 else 0

    /** تنقّل داخل المسار النشط عبر شريط التقدم (سحب المستخدم للـ SeekBar) */
    @Synchronized
    fun seekTo(ms: Int) {
        val p = player ?: return
        try {
            p.seekTo(ms.coerceIn(0, p.duration.coerceAtLeast(0)))
        } catch (_: Exception) {}
    }

    /**
     * تشغيل/إيقاف مؤقت/استكمال تسجيل — الدالة الوحيدة التي تحتاجها الواجهة:
     *  - تسجيل آخر يعمل → يُوقف ويُحرر ثم يبدأ الجديد من الصفر.
     *  - نفس التسجيل يعمل → إيقاف مؤقت.
     *  - نفس التسجيل متوقف مؤقتاً → استكمال من نفس الموضع.
     */
    @Synchronized
    fun toggle(file: File, id: String, newListener: Listener?) {
        listener = newListener

        if (currentId == id) {
            val p = player ?: return
            if (p.isPlaying) {
                p.pause()
                listener?.onPlaybackChanged(id)
            } else {
                p.start()
                listener?.onPlaybackChanged(id)
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            return
        }

        // تسجيل مختلف: تحرير القديم كلياً ثم بدء جديد
        release()
        try {
            currentId = id
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                listener?.onProgress(id, p.duration, p.duration)
                release()
            }
            player = p
            p.start()
            listener?.onPlaybackChanged(id)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (_: Exception) {
            // ملف تالف أو مفقود: تنظيف هادئ
            release()
        }
    }

    /** إيقاف كل شيء وتحرير الموارد (يُستدعى عند إغلاق الشاشة أو بدء تسجيل جديد) */
    @Synchronized
    fun stop() {
        release()
    }

    private fun release() {
        val id = currentId
        handler.removeCallbacks(ticker)
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        currentId = null
        if (id != null) listener?.onPlaybackChanged(id)
    }

    /** إخفاء محتوى فقاعة صوتية لم تُحمل بعد: "جاري التنزيل…" بالمدة غير المعروفة */
    fun placeholderDuration(): String = MediaCache.formatDuration(0)
}
