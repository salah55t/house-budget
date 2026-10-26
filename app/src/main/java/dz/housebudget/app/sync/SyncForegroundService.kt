package dz.housebudget.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dz.housebudget.app.MainActivity
import dz.housebudget.app.R
import dz.housebudget.app.data.AppDatabase
import dz.housebudget.app.chat.ChatHeadManager
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.data.MessageTypes
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * خدمة المزامنة اللحظية والإشعارات (الإصدار 1.5+ — مثل واتساب):
 *
 *  - خدمة أمامية دائمة (Foreground Service) من نوع remoteMessaging:
 *    تستقصي المراسلات الجديدة من السيرفر كل 2 ثانية وأثناء فتح شاشة
 *    المراسلات، كل 5 ثوانٍ والشاشة مشغّلة، وكل 30 ثانية والشاشة مطفأة
 *    (توفيراً للبطارية). المزامنة الكاملة الكلاسيكية تبقى كل دقيقة كصمّام أمان.
 *
 *  - إشعار فوري عند وصول رسالة نصية أو طلب شراء جديد أو تغيّر حالة طلب
 *    (تم الشراء/مرفوض) حتى لو كان التطبيق مغلقاً تماماً — بنقرية تفتح
 *    شاشة المراسلات مباشرة.
 *
 *  - تُقلع تلقائياً مع إقلاع الهاتف (BootReceiver) وتُعاد إن أُوقفت
 *    (START_STICKY) — مع زر حماية من قتل البطارية في الإعدادات.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var powerManager: PowerManager

    companion object {
        // شاشة المراسلات ظاهرة الآن؟ (يضبطها MessagesFragment) → استقصاء كل ثانيتين
        @Volatile
        var chatOpen: Boolean = false

        // هل الخدمة تعمل حالياً؟
        @Volatile
        var running: Boolean = false
            private set

        // إيقاعات الاستقصاء (مثل واتساب: لحظية في المحادثة، اقتصادية في الخلفية)
        const val INTERVAL_CHAT_MS = 2_000L       // المحادثة مفتوحة: ثانيتان
        const val INTERVAL_SCREEN_ON_MS = 5_000L  // الشاشة مشغّلة: 5 ثوانٍ
        const val INTERVAL_SCREEN_OFF_MS = 30_000L // الشاشة مطفأة: 30 ثانية
        const val FULL_SYNC_MS = 60_000L          // مزامنة كاملة كل دقيقة (صمّام أمان)
        const val FIRST_TICK_DELAY_MS = 1_500L    // مهلة إقلاع قصيرة قبل أول جلب

        // مفاتيح التخزين المحلي
        const val PREFS = "app_prefs"
        const val KEY_NOTIF_ENABLED = "notif_enabled"
        const val KEY_SEEN_IDS = "notif_seen_ids"
        private const val MAX_SEEN_IDS = 400

        // معرفات قنوات الإشعارات
        const val CHANNEL_SERVICE = "sync_service"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_REQUESTS = "purchase_requests"

        private const val SERVICE_NOTIF_ID = 1001

        /** بدء الخدمة بأمان من أي مكان (النشاط، جهاز استقبال الإقلاع) */
        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // على بعض الأجهزة قد يُرفض البدء من الخلفية — ستُعاد عند فتح التطبيق
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForegroundWithNotification()
        running = true
        loopJob = scope.launch { syncLoop() }

        // الفقاعة العائمة (مثل ماسنجر): ربطها فوق التطبيقات إن أُذن بذلك
        scope.launch(Dispatchers.Main) { ChatHeadManager.refresh(applicationContext) }

        // تدفق حي للمراسلات يغذي الفقاعة العائمة حتى والتطبيق في الخلفية
        scope.launch(Dispatchers.Main) {
            BudgetRepository.messagesFlow.collect { ChatHeadManager.onMessagesChanged(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // بدء صامت متكرر (من كل فتح للتطبيق): الخدمة تكمل عملها
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** حلقة المزامنة اللحظية: جلب خفيف كل 2-5 ثوانٍ + مزامنة كاملة كل دقيقة */
    private suspend fun syncLoop() {
        delay(FIRST_TICK_DELAY_MS)
        var lastFullSyncAt = 0L // صفر = مزامنة كاملة فورية عند الإقلاع (تعويض أي فترة انقطاع)

        while (coroutineContext.isActive) {
            try {
                if (isConfigured()) {
                    // 1) المسار اللحظي: المراسلات الجديدة فقط (حمولة صغيرة)
                    val pulled = BudgetRepository.pullNewMessages()
                    if (pulled) checkNotifications()

                    // رفع سريع لأي مراسلة معدّلة لم تُرفع بعد (رسائل أُرسلت أثناء غياب الشبكة)
                    BudgetRepository.pushDirtyMessages()

                    // 2) الصمّام الأمان: مزامنة كاملة كل دقيقة (مصاريف + إعدادات + قوالب)
                    val now = System.currentTimeMillis()
                    if (now - lastFullSyncAt >= FULL_SYNC_MS) {
                        BudgetRepository.syncNow()
                        lastFullSyncAt = now
                    }
                }
            } catch (_: Exception) {
                // صامتة: تُعاد المحاولة في الدورة القادمة
            }
            delay(currentInterval())
        }
    }

    /** الإيقاع الحالي: ثانيتان بالمحادثة أو بلوحة الفقاعة المفتوحة، 5 والشاشة مشغّلة، 30 والشاشة مطفأة */
    private fun currentInterval(): Long = when {
        chatOpen || ChatHeadManager.isPanelOpen() -> INTERVAL_CHAT_MS
        powerManager.isInteractive -> INTERVAL_SCREEN_ON_MS
        else -> INTERVAL_SCREEN_OFF_MS
    }

    private suspend fun isConfigured(): Boolean {
        val settings = BudgetRepository.settingsSnapshot()
        return settings.houseCode.isNotBlank() && settings.serverUrl.isNotBlank()
    }

    // ---------- الإشعارات الفورية (مثل واتساب) ----------

    /**
     * فحص المراسلات الجديدة ونشر الإشعارات:
     *  - رسالة نصية من الهاتف الآخر → "رسالة جديدة"
     *  - طلب شراء جديد من الهاتف الآخر → "طلب شراء جديد"
     *  - طلب أرسلته أنت وتغيّرت حالته (تم الشراء/مرفوض) → إشعار حالة
     * المراسلات المعالجة تُخزّن معرّفاتها محلياً حتى لا تتكرر الإشعارات أبداً،
     * وعند أول تشغيل يُعلَّم التاريخ الموجود كمقروء (لا إزعاج بالقديم).
     */
    private suspend fun checkNotifications() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_NOTIF_ENABLED, true)) return

        val all = AppDatabase.get(this).messageDao().getAllIncludingDeleted()
            .filter { !it.deleted }
        val myId = BudgetRepository.myDeviceId()

        // أول تشغيل: علّم كل الموجود كمشاهد فلا تُشعَر بقائمة قديمة كاملة
        if (!prefs.contains(KEY_SEEN_IDS)) {
            prefs.edit().putStringSet(KEY_SEEN_IDS, all.map { it.id }.toSet()).apply()
            return
        }

        val seen = (prefs.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet()).toMutableSet()
        val fresh = all.filter { it.id !in seen }

        // علّم كل المعرفات الحالية كمشاهدة (بما فيها رسائلي) لتفادي أي تكرار لاحق
        val updatedSeen = (seen + all.map { it.id }.toSet()).toMutableSet()
        if (updatedSeen.size > MAX_SEEN_IDS) {
            // تقليم: أبقِ معرفات أحدث 300 مراسلة فقط
            val keep = all.sortedByDescending { it.updatedAt }.take(MAX_SEEN_IDS - 100)
                .map { it.id }.toSet()
            updatedSeen.retainAll(keep)
        }
        prefs.edit().putStringSet(KEY_SEEN_IDS, updatedSeen).apply()

        // المستخدم يرى شاشة المراسلات أو لوحة الفقاعة العائمة الآن → لا إشعار مزدوج
        if (fresh.isEmpty() || chatOpen || ChatHeadManager.isPanelOpen()) return

        for (m in fresh) {
            when {
                m.type == MessageTypes.TEXT && m.senderId != myId ->
                    notify(m, Kind.NEW_MESSAGE)

                m.type == MessageTypes.REQUEST && m.senderId != myId &&
                    m.status == MessageStatus.PENDING ->
                    notify(m, Kind.NEW_REQUEST)

                m.type == MessageTypes.REQUEST && m.senderId == myId &&
                    m.status != MessageStatus.PENDING ->
                    notify(m, if (m.status == MessageStatus.PURCHASED) Kind.PURCHASED else Kind.REJECTED)
            }
        }
    }

    private enum class Kind { NEW_MESSAGE, NEW_REQUEST, PURCHASED, REJECTED }

    /** نشر إشعار بنقرة تفتح شاشة المراسلات مباشرة */
    private fun notify(message: dz.housebudget.app.data.HouseMessage, kind: Kind) {
        val (channelId, title, body) = when (kind) {
            Kind.NEW_MESSAGE -> Triple(
                CHANNEL_MESSAGES,
                getString(R.string.notif_new_message_title),
                message.text
            )
            Kind.NEW_REQUEST -> {
                val extra = if (message.suggestedPrice > 0) {
                    "\n" + getString(R.string.notif_request_price_body, FormatUtils.money(message.suggestedPrice))
                } else ""
                Triple(CHANNEL_REQUESTS, getString(R.string.notif_new_request_title), message.text + extra)
            }
            Kind.PURCHASED -> Triple(
                CHANNEL_REQUESTS,
                getString(R.string.notif_purchased_title),
                getString(R.string.notif_purchased_body, message.text, FormatUtils.money(message.purchasedPrice))
            )
            Kind.REJECTED -> Triple(
                CHANNEL_REQUESTS,
                getString(R.string.notif_rejected_title),
                getString(R.string.notif_rejected_body, message.text)
            )
        }

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_MESSAGES)
        }
        val pending = PendingIntent.getActivity(
            this,
            message.id.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_messages)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(message.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // إذن الإشعارات غير ممنوح على أندرويد 13+ — لا شيء يظهر ولا ينكسر شيء
        }
    }

    /** إشعار الخدمة الدائم (هامس: خلفية الإشعارات الفورية تعمل) */
    private fun startForegroundWithNotification() {
        createChannels()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_messages)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // النوع من التصريح في Manifest (remoteMessaging) — متوافق مع أندرويد 14+
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                SERVICE_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(SERVICE_NOTIF_ID, notification)
        }
    }

    /** قنوات الإشعارات: خدمة صامتة + قناتان عاليتا الأهمية للرسائل والطلبات */
    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                getString(R.string.notif_channel_requests),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
