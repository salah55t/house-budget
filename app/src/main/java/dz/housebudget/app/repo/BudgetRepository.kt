package dz.housebudget.app.repo

import android.content.Context
import dz.housebudget.app.data.AppDatabase
import dz.housebudget.app.data.AppSettings
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.Expense
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.data.MessageTypes
import dz.housebudget.app.data.QuickTemplate
import dz.housebudget.app.sync.ApiClient
import dz.housebudget.app.sync.ExpenseDto
import dz.housebudget.app.sync.MessageDto
import dz.housebudget.app.sync.MessagesPushRequest
import dz.housebudget.app.sync.SettingsDto
import dz.housebudget.app.sync.SyncRequest
import dz.housebudget.app.sync.SyncResponse
import dz.housebudget.app.sync.TemplateDto
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.PeriodInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * المستودع المركزي: الطبقة الوحيدة التي تلمس قاعدة البيانات والشبكة.
 * الواجهات (Fragments) لا تتواصل مع Room أو Retrofit مباشرة أبداً.
 */
object BudgetRepository {

    private lateinit var db: AppDatabase

    // معرف هذا الجهاز: يميّز المراسلات المرسلة من هذا الهاتف عن الواردة من الآخر
    private var deviceId: String = ""

    // معرفات المراسلات المعدّلة محلياً ولم تُرفع للسيرفر بعد (المسار اللحظي)
    private val dirtyMessageIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // سياق التطبيق للتخزين المحلي الصغير (آخر طابع جلب للمراسلات)
    private lateinit var appContext: Context

    // يجب استدعاؤه مرة واحدة من Application.onCreate قبل أي استخدام
    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = AppDatabase.get(context)
            appContext = context.applicationContext
            deviceId = loadOrCreateDeviceId(context.applicationContext)
        }
    }

    /** تحميل معرف الجهاز من التخزين المحلي أو توليد معرف جديد دائم عند أول تشغيل */
    private fun loadOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("device_id", null)
        return if (!saved.isNullOrBlank()) saved else {
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", fresh).apply()
            fresh
        }
    }

    /** معرف هذا الجهاز لتمييز اتجاه المراسلة (مني / من الهاتف الآخر) */
    fun myDeviceId(): String = deviceId

    // ---------- التدفقات الحية (تحدّث الواجهة تلقائياً عند أي تغيير) ----------
    // ملاحظة: نستخدم خصائص get() وليس قيماً مخزنة، حتى تُبنى بعد تهيئة db

    val settingsFlow: Flow<AppSettings>
        get() = db.settingsDao().observe().map { it ?: AppSettings() }

    val expensesFlow: Flow<List<Expense>>
        get() = db.expenseDao().observeAll()

    val templatesFlow: Flow<List<QuickTemplate>>
        get() = db.templateDao().observeAll()

    val messagesFlow: Flow<List<HouseMessage>>
        get() = db.messageDao().observeAll()

    fun recentExpensesFlow(limit: Int): Flow<List<Expense>> =
        db.expenseDao().observeRecent(limit)

    // ---------- عمليات محلية ----------

    /** إضافة مصروف جديد بمعرّف UUID فريد */
    suspend fun addExpense(name: String, amount: Double, category: String, now: Long = System.currentTimeMillis()) {
        val expense = Expense(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "مصروف" },
            amount = amount,
            category = category,
            createdAt = now,
            updatedAt = now,
            deleted = false
        )
        db.expenseDao().upsert(expense)
    }

    /** حذف ناعم: يُعلَّم السجل محذوفاً حتى تنتقل الحالة للهاتف الآخر عند المزامنة */
    suspend fun deleteExpense(expense: Expense) {
        db.expenseDao().upsert(expense.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** هل تغيّر حقل "متزامن" فعلاً بين نسختين من الإعدادات؟ (الميزانية/الدورة/الادخار فقط) */
    private fun syncableChanged(before: AppSettings, after: AppSettings): Boolean =
        before.totalBudget != after.totalBudget ||
            before.periodType != after.periodType ||
            before.startDay != after.startDay ||
            before.savingGoal != after.savingGoal ||
            before.savingModeEnabled != after.savingModeEnabled

    /**
     * تعديل الإعدادات مع ختم زمني ذكي: يُختم الوقت فقط إذا تغيّر حقل متزامن فعلاً.
     *
     * هذا إصلاح خلل جوهري: حفظ الحقول المحلية (رابط السيرفر ورمز الربط)
     * كان يجدد الطابع الزمني، فيصبح هاتف انضم حديثاً "الأحدث" بإعداداته
     * الفارغة (ميزانية 0، يوم راتب 1) ويطغى على السيرفر عند المزامنة،
     * فتمسح ميزانية ودورة الهاتف الآخر — وهذا ما كان يفعله المستخدم
     * بضغطة "مزامنة الآن" أو "فحص الاتصال" على الهاتف الثاني.
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = db.settingsDao().get() ?: AppSettings()
        val updated = transform(current).copy(id = 1)
        val stamped = if (syncableChanged(current, updated)) {
            updated.copy(updatedAt = System.currentTimeMillis())
        } else {
            updated
        }
        db.settingsDao().upsert(stamped)
    }

    /** حفظ عملية كقالب سريع جديد (قابل للمزامنة: طابع زمني لتحديد الأقدمية) */
    suspend fun saveAsTemplate(name: String, amount: Double, category: String) {
        val count = db.templateDao().count()
        db.templateDao().upsert(
            QuickTemplate(
                name = name,
                amount = amount,
                category = category,
                sortOrder = count,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** حذف قالب سريع (حذف ناعم: ينتقل للهاتف الآخر عند المزامنة) */
    suspend fun deleteTemplate(template: QuickTemplate) {
        db.templateDao().upsert(
            template.copy(deleted = true, updatedAt = System.currentTimeMillis())
        )
    }

    // ---------- المراسلات: طلبات الشراء والرسائل النصية ----------

    /** إرسال رسالة نصية عادية إلى الهاتف الآخر */
    suspend fun sendTextMessage(text: String, now: Long = System.currentTimeMillis()) {
        val message = HouseMessage(
            type = MessageTypes.TEXT,
            text = text.trim(),
            senderId = deviceId,
            createdAt = now,
            updatedAt = now
        )
        db.messageDao().upsert(message)
        dirtyMessageIds.add(message.id)
    }

    /** إرسال طلب شراء إلى الهاتف الآخر (يظهر لديه في قائمة انتظار الشراء) */
    suspend fun sendPurchaseRequest(
        name: String,
        suggestedPrice: Double,
        now: Long = System.currentTimeMillis()
    ) {
        val message = HouseMessage(
            type = MessageTypes.REQUEST,
            text = name.trim(),
            suggestedPrice = suggestedPrice,
            senderId = deviceId,
            createdAt = now,
            updatedAt = now
        )
        db.messageDao().upsert(message)
        dirtyMessageIds.add(message.id)
    }

    /**
     * تأكيد شراء طلب من قائمة الانتظار:
     * يُنشأ مصروف بالثمن الفعلي فيُخصم تلقائياً من الميزانية (المتبقي مشتق)،
     * وتتغير حالة الطلب إلى "تم الشراء" فتنتقل للهاتف الآخر عند المزامنة.
     */
    suspend fun purchaseRequest(
        message: HouseMessage,
        price: Double,
        category: String = Categories.OTHER
    ) {
        val now = System.currentTimeMillis()
        val expenseId = UUID.randomUUID().toString()
        db.expenseDao().upsert(
            Expense(
                id = expenseId,
                name = message.text.ifBlank { "طلب شراء" },
                amount = price,
                category = category,
                createdAt = now,
                updatedAt = now,
                deleted = false
            )
        )
        db.messageDao().upsert(
            message.copy(
                status = MessageStatus.PURCHASED,
                purchasedPrice = price,
                expenseId = expenseId,
                updatedAt = now
            )
        )
        dirtyMessageIds.add(message.id)
    }

    /** رفض طلب شراء: تبقى حالته معلّمة "مرفوض" وتنتقل للهاتف الآخر لحظياً */
    suspend fun rejectRequest(message: HouseMessage) {
        val updated = message.copy(
            status = MessageStatus.REJECTED,
            updatedAt = System.currentTimeMillis()
        )
        db.messageDao().upsert(updated)
        dirtyMessageIds.add(updated.id)
    }

    /** لقطة سريعة من الإعدادات الحالية */
    suspend fun settingsSnapshot(): AppSettings = db.settingsDao().get() ?: AppSettings()

    /** حساب الدورة الحالية من الإعدادات المحفوظة */
    suspend fun currentPeriod(): PeriodInfo = BudgetLogic.currentPeriod(settingsSnapshot())

    // ---------- المزامنة السحابية ----------

    /** نتيجة محاولة المزامنة لعرضها للمستخدم */
    sealed class SyncResult {
        object Success : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    /**
     * مزامنة كاملة بثلاث خطوات (كل دقيقة — الصمّام الأمان):
     * 1) رفع كل السجلات المحلية (بما فيها المحذوفة) ليندمجها السيرفر؛
     *    المراسلات تُرفع فقط المعدّلة محلياً (التاريخ كامل يمر عبر المسار اللحظي)
     * 2) السيرفر يدمج بقاعدة "الأحدث يفوز" حسب updatedAt
     * 3) تطبيق الحالة الموحدة القادمة من السيرفر على القاعدة المحلية
     */
    suspend fun syncNow(): SyncResult {
        val settings = settingsSnapshot()
        if (settings.houseCode.isBlank() || settings.serverUrl.isBlank()) {
            return SyncResult.Error("اضبط رابط السيرفر ورمز الربط في الإعدادات أولاً")
        }
        return try {
            val api = ApiClient.build(settings.serverUrl)

            val localExpenses = db.expenseDao().getAllIncludingDeleted()
            val localTemplates = db.templateDao().getAllIncludingDeleted()
            val localSettings = SettingsDto(
                totalBudget = settings.totalBudget,
                periodType = settings.periodType,
                startDay = settings.startDay,
                savingGoal = settings.savingGoal,
                savingModeEnabled = settings.savingModeEnabled,
                updatedAt = settings.updatedAt
            )

            // المراسلات: نرفع المعدّلة فقط إن وجدت؛ إن لا شيء يبقى الرد خفيفاً
            val dirtyMessages = if (dirtyMessageIds.isEmpty()) {
                emptyList()
            } else {
                db.messageDao().getAllIncludingDeleted()
                    .filter { dirtyMessageIds.contains(it.id) }
                    .map { it.toDto() }
            }

            val response = api.sync(
                settings.houseCode.trim(),
                SyncRequest(
                    expenses = localExpenses.map { it.toDto() },
                    templates = localTemplates.map { it.toDto() },
                    messages = dirtyMessages,
                    settings = localSettings
                )
            )
            if (!response.ok) {
                return SyncResult.Error(response.error ?: "خطأ غير معروف من السيرفر")
            }
            if (dirtyMessages.isNotEmpty()) dirtyMessageIds.clear()

            applyServerState(response)
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error("فشل الاتصال: ${e.message ?: "تحقق من الإنترنت والرابط"}")
        }
    }

    /** فحص الاتصال بالسيرفر: يُرجع null عند النجاح أو رسالة خطأ */
    suspend fun testConnection(): String? {
        val settings = settingsSnapshot()
        if (settings.houseCode.isBlank() || settings.serverUrl.isBlank()) {
            return "أدخل رابط السيرفر ورمز الربط أولاً"
        }
        return try {
            val api = ApiClient.build(settings.serverUrl)
            val response = api.ping(settings.houseCode.trim())
            if (response.ok) null else response.error ?: "استجابة غير صالحة"
        } catch (e: Exception) {
            "فشل الاتصال: ${e.message ?: ""}"
        }
    }

    // ---------- المسار اللحظي للمراسلات (الإصدار 1.5+) ----------

    /**
     * جلب المراسلات الجديدة فقط منذ آخر طابع زمني للسيرفر (حمولة صغيرة).
     * تستدعاه خدمة المزامنة كل 2-5 ثوانٍ لشعور المحادثة بالفورية.
     * يرجع true عند نجاح الدورة (حتى لو لا جديد) لتحديث الإشعارات بعدها.
     */
    suspend fun pullNewMessages(): Boolean {
        val settings = settingsSnapshot()
        if (settings.houseCode.isBlank() || settings.serverUrl.isBlank()) return false
        return try {
            val api = ApiClient.build(settings.serverUrl)
            val response = api.pullMessages(
                settings.houseCode.trim(),
                since = loadLastPullAt()
            )
            if (!response.ok) return false
            if (response.messages.isNotEmpty()) {
                db.messageDao().upsertAll(response.messages.map { it.toEntity() })
            }
            saveLastPullAt(response.serverTime)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * رفع المراسلات المعدّلة محلياً فوراً عبر المسار اللحظي (إرسال خلال ثانية)،
     * والاستجابة تعيد أيضاً الوارد الجديد منذ آخر جلب فيدمج هنا مباشرة.
     * تستدعى بعد كل إرسال/شراء/رفض لتصل المراسلة للهاتف الآخر خلال ثوانٍ.
     */
    suspend fun pushDirtyMessages(): Boolean {
        if (dirtyMessageIds.isEmpty()) return true
        val settings = settingsSnapshot()
        if (settings.houseCode.isBlank() || settings.serverUrl.isBlank()) return false
        return try {
            val api = ApiClient.build(settings.serverUrl)
            val dirty = db.messageDao().getAllIncludingDeleted()
                .filter { dirtyMessageIds.contains(it.id) }
                .map { it.toDto() }
            if (dirty.isEmpty()) {
                dirtyMessageIds.clear()
                return true
            }
            val response = api.pushMessages(
                settings.houseCode.trim(),
                since = loadLastPullAt(),
                body = MessagesPushRequest(messages = dirty)
            )
            if (!response.ok) return false
            dirtyMessageIds.removeAll(dirty.map { it.id }.toSet())
            if (response.messages.isNotEmpty()) {
                db.messageDao().upsertAll(response.messages.map { it.toEntity() })
            }
            saveLastPullAt(response.serverTime)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** آخر طابع زمني للسيرفر جُلبِت بعده المراسلات (محفوظ محلياً — محلي لهذا الجهاز) */
    private fun loadLastPullAt(): Long {
        val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("last_pull_at", 0L)
    }

    private fun saveLastPullAt(serverTime: Long) {
        if (serverTime > 0) {
            appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putLong("last_pull_at", serverTime).apply()
        }
    }

    /** تطبيق الحالة الموحدة القادمة من السيرفر على القاعدة المحلية */
    private suspend fun applyServerState(response: SyncResponse) {
        // دمج سجلات المصاريف (السيرفر دمجها مسبقاً: الأحدث يفوز)
        db.expenseDao().upsertAll(response.expenses.map { it.toEntity() })

        // دمج القوالب السريعة (بما فيها علامات الحذف الناعم)
        if (response.templates.isNotEmpty()) {
            db.templateDao().upsertAll(response.templates.map { it.toEntity() })
        }

        // دمج المراسلات: طلبات الشراء (بحالاتها) + الرسائل النصية
        if (response.messages.isNotEmpty()) {
            db.messageDao().upsertAll(response.messages.map { it.toEntity() })
        }

        // تحديث الإعدادات المشتركة فقط دون المساس بالحقول المحلية (الرابط ورمز الربط)
        val serverSettings = response.settings
        if (serverSettings != null) {
            val local = settingsSnapshot()
            if (serverSettings.updatedAt >= local.updatedAt) {
                if (serverSettings.totalBudget == 0.0 && local.totalBudget > 0.0) {
                    // حماية ذاتية: الميزانية الصفرية ليست اختياراً حقيقياً
                    // (الواجهة تمنع حفظ صفر) بل أثر الطغيان القديم — نرفض تطبيقها
                    // ونختم وقتنا المحلي ليفوز في المزامنة القادمة ويعيد الميزانية
                    // الصحيحة والدورة إلى الهاتف الآخر خلال دقيقة كحد أقصى
                    db.settingsDao().upsert(local.copy(updatedAt = System.currentTimeMillis()))
                } else {
                    db.settingsDao().upsert(
                        local.copy(
                            totalBudget = serverSettings.totalBudget,
                            periodType = serverSettings.periodType,
                            startDay = serverSettings.startDay,
                            savingGoal = serverSettings.savingGoal,
                            savingModeEnabled = serverSettings.savingModeEnabled,
                            updatedAt = serverSettings.updatedAt
                        )
                    )
                }
            }
        }
    }

    // محولات بين كيانات Room ونماذج الشبكة
    private fun Expense.toDto() = ExpenseDto(id, name, amount, category, createdAt, updatedAt, deleted)
    private fun ExpenseDto.toEntity() = Expense(id, name, amount, category, createdAt, updatedAt, deleted)
    private fun QuickTemplate.toDto() =
        TemplateDto(id, name, amount, category, sortOrder, updatedAt, deleted)
    private fun TemplateDto.toEntity() =
        QuickTemplate(id, name, amount, category, sortOrder, updatedAt, deleted)
    private fun HouseMessage.toDto() =
        MessageDto(
            id, type, text, suggestedPrice, status,
            purchasedPrice, expenseId, senderId, createdAt, updatedAt, deleted
        )
    private fun MessageDto.toEntity() =
        HouseMessage(
            id, type, text, suggestedPrice, status,
            purchasedPrice, expenseId, senderId, createdAt, updatedAt, deleted
        )
}
