package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** نوع المراسلة: طلب شراء أو رسالة نصية عادية */
object MessageTypes {
    const val TEXT = "TEXT"         // رسالة نصية عادية
    const val REQUEST = "REQUEST"   // طلب شراء
}

/**
 * نوع مرفق الوسائط (الإصدار 1.8: رسائل صوتية وصور مثل واتساب):
 *  - فارغة = مراسلة نصية/طلب بلا مرفق
 *  - IMAGE = صورة مضغوطة (mediaFile = معرّف الملف على السيرفر)
 *  - AUDIO = تسجيل صوتي قصير (mediaFile = معرّف الملف على السيرفر)
 */
object MessageMedia {
    const val NONE = ""
    const val IMAGE = "IMAGE"
    const val AUDIO = "AUDIO"
}

/** حالة طلب الشراء: قيد الانتظار ثم إما تم الشراء (بالصرف من الميزانية) أو مرفوض */
object MessageStatus {
    const val PENDING = "PENDING"       // قيد الانتظار في قائمة الشراء
    const val PURCHASED = "PURCHASED"   // تم شراؤه وأُضيف ثمنه إلى المصاريف
    const val REJECTED = "REJECTED"     // رفضه الهاتف المستقبل
}

/**
 * مراسلة بين الهاتفين عبر سيرفر المزامنة:
 *  - طلب شراء: اسم المنتج + سعر مقترح اختياري، يظهر لدى الهاتف الآخر في
 *    "قائمة انتظار الشراء"، وعند تحديده تظهر خانة إدخال الثمن؛ تأكيده يُنشئ
 *    مصروفاً فيُخصم من الميزانية تلقائياً (المتبقي مشتق من الميزانية والمصاريف).
 *  - رسالة نصية: نص حر يتبادل الهاتفان مثل محادثة بسيطة.
 *
 * معرّف UUID + طابع زمني + حذف ناعم بنفس قواعد "الأحدث يفوز" المستخدمة
 * للمصاريف والقوالب، مع senderId يميّز جهاز المرسل عن جهاز المستقبل.
 */
@Entity(tableName = "messages")
data class HouseMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String = MessageTypes.TEXT,
    // نص الرسالة أو اسم المنتج المطلوب (للصورة: تعليق اختياري)
    val text: String,
    // للطلبات: سعر مقترح اختياري يظهر مسبقاً في خانة الثمن (0 = بلا سعر)
    val suggestedPrice: Double = 0.0,
    val status: String = MessageStatus.PENDING,
    // الثمن الفعلي الذي خُصم من الميزانية عند تأكيد الشراء
    val purchasedPrice: Double = 0.0,
    // معرف المصروف المُنشأ عند الشراء (للتوثيق فقط)
    val expenseId: String = "",
    // معرف جهاز المرسل: يحدد إن كانت المراسلة "مني" أم "من الهاتف الآخر"
    val senderId: String = "",
    // نوع الوسائط المرفقة (الإصدار 1.8): "" / IMAGE / AUDIO
    val mediaType: String = MessageMedia.NONE,
    // معرف ملف الوسائط على السيرفر (يُحمل للجهاز الآخر عند فتح المحادثة)
    val mediaFile: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // طابع آخر تعديل: يحسم الأقدمية عند دمج نسختي الهاتفين (مثل تحديث حالة الطلب)
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)
