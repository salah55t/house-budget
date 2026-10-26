package dz.housebudget.app.chat

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import dz.housebudget.app.R
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.data.MessageTypes
import dz.housebudget.app.databinding.ViewChatBubbleBinding
import dz.housebudget.app.databinding.ViewChatPanelBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.sync.SyncForegroundService
import dz.housebudget.app.ui.ChatAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min

/**
 * مدير الفقاعة العائمة للمحادثة (الإصدار 1.6 — مثل ماسنجر):
 *
 *  - فقاعة دائرية خضراء تطفو فوق الشاشات، قابلة للسحب بأي إصبع مع انزلاق
 *    تلقائي إلى أقرب حافة عند تركها، وتتذكر آخر مكان لها.
 *  - نقرة واحدة تتوسع إلى لوحة محادثة سريعة (قائمة رسائل + خانة كتابة +
 *    زر إرسال) فيمكن قراءة الرد وإرسال رسالة دون مغادرة الشاشة الحالية.
 *  - شارة حمراء تحمل عدد الرسائل وطلبات الشراء غير المقروءة.
 *
 *  وضعا التشغيل (يُختاران تلقائياً):
 *   1) الوضع الفائق للظهور (مثل ماسنجر تماماً): عند منح إذن
 *      SYSTEM_ALERT_WINDOW تُرفع الفقاعة عبر TYPE_APPLICATION_OVERLAY
 *      فتظهر فوق التطبيقات الأخرى أيضاً عند وصول رسالة جديدة.
 *   2) الوضع الداخلي (بلا أي أذونات): تُرفع عبر TYPE_APPLICATION_PANEL
 *      برمز نافذة النشاط الرئيسي فتطفو فوق كل شاشات التطبيق.
 *
 *  مصدر الحياة: تدفق BudgetRepository.messagesFlow الحي الذي تجمعه خدمة
 *  المزامنة اللحظية (كل 2-5 ثوانٍ) والنشاط الرئيسي معاً — أي تغيير في
 *  المراسلات يحدّث الشارة واللوحة والظهور فوراً.
 */
object ChatHeadManager {

    private const val PREFS = "app_prefs"
    private const val KEY_ENABLED = "chat_bubble_enabled"
    private const val KEY_READ_AT = "bubble_read_at"
    private const val KEY_POS_X = "bubble_pos_x"
    private const val KEY_POS_Y = "bubble_pos_y"

    // أبعاد ثابتة بالبكسل المضغوط
    private const val BUBBLE_SIZE_DP = 58
    private const val PANEL_MAX_WIDTH_DP = 320
    private const val PANEL_MAX_HEIGHT_DP = 430

    private enum class Mode { IN_APP, OVERLAY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var activityRef: WeakReference<Activity>? = null

    private var bubbleBinding: ViewChatBubbleBinding? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelBinding: ViewChatPanelBinding? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // وضع النافذة المرفوعة حالياً (null = لا نافذة مرفوعة)
    private var attachedMode: Mode? = null
    private var expanded = false

    @Volatile private var appInForeground = false
    @Volatile private var latestMessages: List<HouseMessage> = emptyList()

    private var chatAdapter: ChatAdapter? = null

    // ---------- دوال مساعدة ----------

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** التحقق من تفعيل الميزة من الإعدادات (مفعّلة افتراضياً) */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    /** هل لوحة المحادثة السريعة مفتوحة الآن؟ (تستخدمه الخدمة: استقصاء كل ثانيتين + بلا إشعارات مزدوجة) */
    @JvmStatic
    fun isPanelOpen(): Boolean = expanded

    private fun dp(value: Int): Int =
        (value * Resources.getSystem().displayMetrics.density).toInt()

    /** تنفيذ على الخيط الرئيسي دائماً (النوافذ تعمل على الخيط الرئيسي فقط) */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ---------- واجهة الاستدعاء العامة (من النشاط والخدمة والإعدادات) ----------

    /** MainActivity.onResume: التقاط حالة المقدمة وربط الفقاعة بالوضع المناسب */
    fun onAppResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        appInForeground = true
        onMain {
            // فتح التطبيق يطوي اللوحة العائمة (سلوك ماسنجر عند فتح المحادثة الكاملة)
            if (expanded) collapse()
            refresh(activity)
        }
    }

    /** MainActivity.onPause: النافذة الداخلية ترتبط بنافذة النشاط فتُفك آمناً */
    fun onAppPaused() {
        appInForeground = false
        onMain {
            if (attachedMode == Mode.IN_APP) {
                if (expanded) collapse()
                detachAll()
            } else {
                // الوضع الفائق: في الخلفية تظهر فقط عند وجود غير مقروء (سلوك ماسنجر)
                refreshVisibility()
            }
        }
    }

    /** MainActivity.onDestroy: تنظيف نهائي للنافذة الداخلية ومرجع النشاط */
    fun onAppDestroyed() {
        onMain {
            activityRef = null
            appInForeground = false
            if (attachedMode == Mode.IN_APP) {
                if (expanded) collapse()
                detachAll()
            }
        }
    }

    /**
     * إعادة تقييم كاملة بعد أي تغيير: تفعيل/تعطيل من الإعدادات، منح/سحب إذن
     * الظهور فوق التطبيقات، أو اقلاع الخدمة — تربط أو تفك النافذة حسب الحال.
     */
    fun refresh(context: Context) {
        onMain {
            val appCtx = context.applicationContext
            appContext = appCtx
            windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // اللوحة مفتوحة: نتركها والهيكلة تُعاد تلقائياً عند الانطواء
            if (expanded) return@onMain

            val desired = currentDesiredMode()
            if (desired == null) {
                detachAll()
                return@onMain
            }
            if (desired != attachedMode) detachAll()
            refreshVisibility()
        }
    }

    /** تدفق المراسلات الحي: يحدّث الشارة واللوحة والظهور مع كل تغيّر */
    fun onMessagesChanged(messages: List<HouseMessage>) {
        onMain {
            latestMessages = messages
            if (expanded) renderPanel() else updateBadge()
            refreshVisibility()
        }
    }

    /** شاشة المراسلات فُتحت أو أُغلقت: كل ما فيها يُعد مقروءاً والفقاعة تختبئ/تعود */
    fun onMessagesScreenVisibility(@Suppress("UNUSED_PARAMETER") open: Boolean) {
        onMain {
            markAllReadInternal()
            refreshVisibility()
        }
    }

    // ---------- قرار الظهور ----------

    /** الوضع المطلوب الآن: فائق للظهور مع الإذن، داخلي بدونه، ولا شيء معطلاً */
    private fun currentDesiredMode(): Mode? {
        val ctx = appContext ?: return null
        if (!prefs(ctx).getBoolean(KEY_ENABLED, true)) return null
        return if (Settings.canDrawOverlays(ctx)) Mode.OVERLAY else Mode.IN_APP
    }

    /** عدد غير المقروء: رسائل وطلبات شراء قيد الانتظار واردة من الهاتف الآخر */
    private fun unreadCount(): Int {
        val myId = BudgetRepository.myDeviceId()
        val readAt = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getLong(KEY_READ_AT, 0L) ?: 0L
        return latestMessages.count { m ->
            !m.deleted && m.senderId != myId && m.createdAt > readAt &&
                (m.type == MessageTypes.TEXT ||
                    (m.type == MessageTypes.REQUEST && m.status == MessageStatus.PENDING))
        }
    }

    private fun shouldShow(): Boolean {
        val ctx = appContext ?: return false
        if (!prefs(ctx).getBoolean(KEY_ENABLED, true)) return false
        if (expanded) return true
        if (SyncForegroundService.chatOpen) return false // شاشة المراسلات أمامه مباشرة
        // نقرر حسب الوضع المطلوب (وليس المرفوع) كي تُربَط الفقاعة في أول إقلاع
        return when (currentDesiredMode()) {
            Mode.IN_APP -> true // زر وصول سريع في كل شاشات التطبيق
            Mode.OVERLAY -> appInForeground || unreadCount() > 0
            null -> false
        }
    }

    /** رفع أو إخفاء نافذة الفقاعة حسب القرار الحالي */
    private fun refreshVisibility() {
        if (expanded) return // اللوحة المفتوحة تبقى حتى الانطواء
        if (shouldShow()) ensureBubbleAttached() else detachBubbleOnly()
    }

    // ---------- رفع الفقاعة وإدارتها ----------

    private fun ensureBubbleAttached() {
        if (bubbleBinding != null) return
        val wm = windowManager ?: return
        val ctx = appContext ?: return
        val mode = currentDesiredMode() ?: return

        val themed = ContextThemeWrapper(ctx, R.style.Theme_HouseBudget)
        val binding = ViewChatBubbleBinding.inflate(LayoutInflater.from(themed))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (mode == Mode.OVERLAY) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        if (mode == Mode.IN_APP) {
            // النافذة الداخلية تحتاج رمز نافذة النشاط — إن لم يجهز بعد ننتظر القلع التالي
            val token = activityRef?.get()?.window?.decorView?.windowToken ?: return
            params.token = token
        }
        params.gravity = Gravity.TOP or Gravity.LEFT

        val dm = Resources.getSystem().displayMetrics
        val size = dp(BUBBLE_SIZE_DP)
        val savedX = prefs(ctx).getInt(KEY_POS_X, -1)
        val savedY = prefs(ctx).getInt(KEY_POS_Y, -1)
        params.x = if (savedX >= 0) savedX.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        else (dm.widthPixels - size - dp(12))
        params.y = if (savedY >= 0) savedY.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        else dp(160)

        binding.root.contentDescription = ctx.getString(R.string.bubble_desc)
        binding.root.setOnTouchListener(makeBubbleTouchListener())

        try {
            wm.addView(binding.root, params)
        } catch (_: Exception) {
            // توكن ميت أو نافذة غير مهيأة — تُعاد المحاولة عند القلع/التحديث التالي
            return
        }
        bubbleBinding = binding
        bubbleParams = params
        attachedMode = mode
        updateBadge()
    }

    private fun detachBubbleOnly() {
        val view = bubbleBinding?.root ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        bubbleBinding = null
        bubbleParams = null
        attachedMode = null
    }

    private fun detachAll() {
        bubbleBinding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelBinding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleBinding = null
        bubbleParams = null
        panelBinding = null
        panelParams = null
        expanded = false
        attachedMode = null
    }

    /** تحديث شارة غير المقروء على الفقاعة */
    private fun updateBadge() {
        val badge = bubbleBinding?.txtBadge ?: return
        val unread = unreadCount()
        if (unread > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (unread > 99) "99+" else unread.toString()
        } else {
            badge.visibility = View.GONE
        }
    }

    // ---------- السحب والانزلاق للحافة ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun makeBubbleTouchListener(): View.OnTouchListener {
        val appCtx = appContext
        val slop = if (appCtx != null) ViewConfiguration.get(appCtx).scaledTouchSlop else 12
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = bubbleParams ?: return@OnTouchListener false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = bubbleParams ?: return@OnTouchListener false
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val dm = Resources.getSystem().displayMetrics
                        val w = v.width.coerceAtLeast(dp(BUBBLE_SIZE_DP))
                        val h = v.height.coerceAtLeast(dp(BUBBLE_SIZE_DP))
                        p.x = (startX + dx).coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                        p.y = (startY + dy).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
                        try {
                            windowManager?.updateViewLayout(v, p)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition()
                        snapToNearestEdge(v)
                    } else {
                        expand()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** حفظ آخر مكان للفقاعة لتتذكره في المرة القادمة */
    private fun savePosition() {
        val ctx = appContext ?: return
        val p = bubbleParams ?: return
        prefs(ctx).edit()
            .putInt(KEY_POS_X, p.x)
            .putInt(KEY_POS_Y, p.y)
            .apply()
    }

    /** انزلاق ناعم إلى أقرب حافة يسار/يمين عند ترك الفقاعة */
    private fun snapToNearestEdge(view: View) {
        val p = bubbleParams ?: return
        val dm = Resources.getSystem().displayMetrics
        val margin = dp(10)
        val w = view.width.coerceAtLeast(dp(BUBBLE_SIZE_DP))
        val target = if (p.x + w / 2 < dm.widthPixels / 2) margin else dm.widthPixels - w - margin

        ValueAnimator.ofInt(p.x, target).apply {
            duration = 180
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(view, p)
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    // ---------- توسيع لوحة المحادثة السريعة ----------

    private fun expand() {
        if (expanded) return
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val mode = attachedMode ?: currentDesiredMode() ?: return

        // نأخذ مكان الفقاعة الحالي لو كانت مرفوعة
        val bubbleX = bubbleParams?.x
        val bubbleY = bubbleParams?.y
        detachBubbleOnly()

        val themed = ContextThemeWrapper(ctx, R.style.Theme_HouseBudget)
        val binding = ViewChatPanelBinding.inflate(LayoutInflater.from(themed))

        val dm = Resources.getSystem().displayMetrics
        val width = min(dm.widthPixels - dp(24), dp(PANEL_MAX_WIDTH_DP))
        val height = min(dm.heightPixels - dp(140), dp(PANEL_MAX_HEIGHT_DP)).coerceAtLeast(dp(280))

        val params = WindowManager.LayoutParams(
            width,
            height,
            if (mode == Mode.OVERLAY) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, // قابل للتركيز: لوحة المفاتيح تعمل
            PixelFormat.TRANSLUCENT
        )
        if (mode == Mode.IN_APP) {
            val token = activityRef?.get()?.window?.decorView?.windowToken
            if (token == null) {
                // لا رمز متاح: نعيد الفقاعة فقط
                expanded = false
                refreshVisibility()
                return
            }
            params.token = token
        }
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        params.x = (bubbleX ?: ((dm.widthPixels - width) / 2))
            .coerceIn(dp(8), (dm.widthPixels - width - dp(8)).coerceAtLeast(dp(8)))
        params.y = (bubbleY ?: dp(120))
            .coerceIn(dp(8), (dm.heightPixels - height - dp(8)).coerceAtLeast(dp(8)))

        setupPanel(binding)
        panelBinding = binding
        panelParams = params
        expanded = true

        try {
            wm.addView(binding.root, params)
        } catch (_: Exception) {
            panelBinding = null
            panelParams = null
            expanded = false
            refreshVisibility()
            return
        }
        markAllReadInternal()
        renderPanel()
    }

    /** ربط عناصر لوحة المحادثة: زر إغلاق، قائمة، حالة فراغ، إرسال */
    private fun setupPanel(binding: ViewChatPanelBinding) {
        val ctx = appContext ?: return

        // قص المحتوى على زوايا البطاقة الدائرية
        binding.root.clipToOutline = true

        // لمس خارج اللوحة (في تطبيق آخر أو سطح المكتب) يطويها مثل ماسنجر
        binding.root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapse()
                true
            } else {
                false
            }
        }

        binding.btnClose.setOnClickListener { collapse() }

        chatAdapter = chatAdapter ?: ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(ctx)
        binding.rvChat.adapter = chatAdapter
        binding.txtEmpty.text = ctx.getString(R.string.bubble_empty)
        binding.inputBubble.hint = ctx.getString(R.string.hint_message)

        binding.btnSend.setOnClickListener {
            val text = binding.inputBubble.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            binding.inputBubble.setText("")

            scope.launch {
                // حفظ محلي + رفع لحظي: تصل الرسالة للهاتف الآخر خلال ثوانٍ
                BudgetRepository.sendTextMessage(text)
                val ok = BudgetRepository.pushDirtyMessages()
                if (!ok) {
                    Toast.makeText(ctx, R.string.sync_failed_short, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** تعبئة قائمة رسائل اللوحة من آخر حالة معروفة للمراسلات */
    private fun renderPanel() {
        val binding = panelBinding ?: return
        val texts = latestMessages
            .filter { !it.deleted && it.type == MessageTypes.TEXT }
            .sortedBy { it.createdAt }

        chatAdapter?.submitList(texts) {
            if (texts.isNotEmpty()) binding.rvChat.scrollToPosition(texts.size - 1)
        }
        binding.txtEmpty.visibility = if (texts.isEmpty()) View.VISIBLE else View.GONE
        binding.rvChat.visibility = if (texts.isEmpty()) View.GONE else View.VISIBLE
    }

    /** الانطواء: إزالة اللوحة وإعادة الفقاعة (مع إعادة هيكلة إن تغيّر الوضع) */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        panelBinding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelBinding = null
        panelParams = null

        val desired = currentDesiredMode()
        if (desired != attachedMode) detachAll()
        refreshVisibility()
    }

    /** ختم كل المراسلات الحالية كمقروءة (يُحدث الشارة) */
    private fun markAllReadInternal() {
        val ctx = appContext ?: return
        prefs(ctx).edit().putLong(KEY_READ_AT, System.currentTimeMillis()).apply()
        updateBadge()
    }
}
