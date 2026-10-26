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

/** نسخة القالب السريع القابلة للنقل عبر الشبكة */
data class TemplateDto(
    val id: String,
    val name: String,
    val amount: Double,
    val category: String,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

/** نسخة المراسلة (طلب شراء أو رسالة نصية) القابلة للنقل عبر الشبكة */
data class MessageDto(
    val id: String,
    val type: String,
    val text: String,
    val suggestedPrice: Double = 0.0,
    val status: String = "PENDING",
    val purchasedPrice: Double = 0.0,
    val expenseId: String = "",
    val senderId: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false
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
    val templates: List<TemplateDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val settings: SettingsDto? = null
)

/** استجابة السيرفر: الحالة الموحدة بعد الدمج */
data class SyncResponse(
    val ok: Boolean,
    val expenses: List<ExpenseDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
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

/**
 * المسار اللحظي للمراسلات (الإصدار 1.5+):
 * طلب/إرسال المراسلات الجديدة فقط بحمولة صغيرة كل 2-5 ثوانٍ،
 * بدل المزامنة الكاملة كل دقيقة — سرعة محادثة قريبة من الفورية.
 */
data class MessagesResponse(
    val ok: Boolean,
    val messages: List<MessageDto> = emptyList(),
    val serverTime: Long = 0,
    val error: String? = null
)

/** رفع مراسلات جديدة/معدّلة عبر المسار اللحظي */
data class MessagesPushRequest(
    val messages: List<MessageDto>
)
