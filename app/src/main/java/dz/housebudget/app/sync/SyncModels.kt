package dz.housebudget.app.sync

/**
 * نماذج البيانات المتبادلة مع سيرفر المزامنة (تُحول تلقائياً JSON عبر Gson).
 * ملاحظة: هذه النماذج مستقلة عن كيانات Room لفصل طبقات الشبكة عن التخزين.
 */

/** نسخة المصروف القابلة للنقل عبر الشبكة */
data class ExpenseDto(
    val id: String,
    val name: String,
    val amount: Double,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean
)

/** الحقول المشتركة من الإعدادات (بدون الرابط ورمز الربط المحليين) */
data class SettingsDto(
    val totalBudget: Double,
    val periodType: String,
    val startDay: Int,
    val savingGoal: Double,
    val savingModeEnabled: Boolean,
    val updatedAt: Long
)

/** طلب المزامنة: نرفع كل بياناتنا المحلية ليندمجها السيرفر (الأحدث يفوز) */
data class SyncRequest(
    val expenses: List<ExpenseDto>,
    val settings: SettingsDto?
)

/** استجابة السيرفر: الحالة الموحدة بعد الدمج */
data class SyncResponse(
    val ok: Boolean,
    val expenses: List<ExpenseDto> = emptyList(),
    val settings: SettingsDto? = null,
    val serverTime: Long = 0,
    val error: String? = null
)

/** استجابة فحص الاتصال بالسيرفر */
data class PingResponse(
    val ok: Boolean,
    val name: String = "",
    val error: String? = null
)
