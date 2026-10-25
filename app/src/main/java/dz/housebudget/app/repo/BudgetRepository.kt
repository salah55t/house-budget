package dz.housebudget.app.repo

import android.content.Context
import dz.housebudget.app.data.AppDatabase
import dz.housebudget.app.data.AppSettings
import dz.housebudget.app.data.Expense
import dz.housebudget.app.data.QuickTemplate
import dz.housebudget.app.sync.ApiClient
import dz.housebudget.app.sync.ExpenseDto
import dz.housebudget.app.sync.SettingsDto
import dz.housebudget.app.sync.SyncRequest
import dz.housebudget.app.sync.SyncResponse
import dz.housebudget.app.util.BudgetLogic
import dz.housebudget.app.util.PeriodInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * المستودع المركزي: الطبقة الوحيدة التي تلمس قاعدة البيانات والشبكة.
 * الواجهات (Fragments) لا تتواصل مع Room أو Retrofit مباشرة أبداً.
 */
object BudgetRepository {

    private lateinit var db: AppDatabase

    // يجب استدعاؤه مرة واحدة من Application.onCreate قبل أي استخدام
    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = AppDatabase.get(context)
        }
    }

    // ---------- التدفقات الحية (تحدّث الواجهة تلقائياً عند أي تغيير) ----------
    // ملاحظة: نستخدم خصائص get() وليس قيماً مخزنة، حتى تُبنى بعد تهيئة db

    val settingsFlow: Flow<AppSettings>
        get() = db.settingsDao().observe().map { it ?: AppSettings() }

    val expensesFlow: Flow<List<Expense>>
        get() = db.expenseDao().observeAll()

    val templatesFlow: Flow<List<QuickTemplate>>
        get() = db.templateDao().observeAll()

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

    /** تعديل الإعدادات المشتركة مع تحديث الطابع الزمني (لحسم الأقدمية عند المزامنة) */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = db.settingsDao().get() ?: AppSettings()
        db.settingsDao().upsert(transform(current).copy(id = 1, updatedAt = System.currentTimeMillis()))
    }

    /** حفظ عملية كقالب سريع جديد */
    suspend fun saveAsTemplate(name: String, amount: Double, category: String) {
        val count = db.templateDao().count()
        db.templateDao().upsert(
            QuickTemplate(name = name, amount = amount, category = category, sortOrder = count)
        )
    }

    /** حذف قالب سريع */
    suspend fun deleteTemplate(template: QuickTemplate) {
        db.templateDao().deleteById(template.id)
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
     * مزامنة كاملة بثلاث خطوات:
     * 1) رفع كل السجلات المحلية (بما فيها المحذوفة) ليندمجها السيرفر
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
            val localSettings = SettingsDto(
                totalBudget = settings.totalBudget,
                periodType = settings.periodType,
                startDay = settings.startDay,
                savingGoal = settings.savingGoal,
                savingModeEnabled = settings.savingModeEnabled,
                updatedAt = settings.updatedAt
            )

            val response = api.sync(settings.houseCode.trim(), SyncRequest(localExpenses.map { it.toDto() }, localSettings))
            if (!response.ok) {
                return SyncResult.Error(response.error ?: "خطأ غير معروف من السيرفر")
            }

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

    /** تطبيق الحالة الموحدة القادمة من السيرفر على القاعدة المحلية */
    private suspend fun applyServerState(response: SyncResponse) {
        // دمج سجلات المصاريف (السيرفر دمجها مسبقاً: الأحدث يفوز)
        db.expenseDao().upsertAll(response.expenses.map { it.toEntity() })

        // تحديث الإعدادات المشتركة فقط دون المساس بالحقول المحلية (الرابط ورمز الربط)
        val serverSettings = response.settings
        if (serverSettings != null) {
            val local = settingsSnapshot()
            if (serverSettings.updatedAt >= local.updatedAt) {
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

    // محولات بين كيانات Room ونماذج الشبكة
    private fun Expense.toDto() = ExpenseDto(id, name, amount, category, createdAt, updatedAt, deleted)
    private fun ExpenseDto.toEntity() = Expense(id, name, amount, category, createdAt, updatedAt, deleted)
}
