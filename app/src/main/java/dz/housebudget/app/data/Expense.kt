package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** تصنيفات المصاريف المدعومة في التطبيق (قائمة مخصصة حسب احتياج البيت) */
object Categories {
    const val VEG = "VEG"           // الخضر
    const val FRUITS = "FRUITS"     // الفواكه
    const val MEAT = "MEAT"         // اللحوم والأسماك والبيض
    const val KIDS = "KIDS"         // مصروف الأولاد
    const val PROV = "PROV"         // الريفيطايما ومواد التضيف
    const val DAILY = "DAILY"       // مصروف يومي (خبز، حليب، ماء، ڨازوز)
    const val OTHER = "OTHER"       // أخرى

    /** قائمة التصنيفات بترتيب العرض */
    val all = listOf(VEG, FRUITS, MEAT, KIDS, PROV, DAILY, OTHER)

    /** الاسم العربي المعروض لكل تصنيف */
    fun arabicName(category: String): String = when (category) {
        VEG -> "الخضر"
        FRUITS -> "الفواكه"
        MEAT -> "اللحوم والأسماك والبيض"
        KIDS -> "مصروف الأولاد"
        PROV -> "الريفيطايما ومواد التضيف"
        DAILY -> "مصروف يومي (خبز، حليب، ماء، ڨازوز)"
        else -> "أخرى"
    }

    /**
     * لوحة ألوان التصنيفات بترتيبها في all — مصدر واحد للحقيقة
     * يستخدمه الرسم الدائري ومفتاح التقرير ورقائق الإدخال ونقاط القائمة.
     */
    val PALETTE = intArrayOf(
        0xFF2E7D32.toInt(), // الخضر — أخضر
        0xFFEF6C00.toInt(), // الفواكه — برتقالي
        0xFFC62828.toInt(), // اللحوم والأسماك والبيض — أحمر
        0xFF6A1B9A.toInt(), // مصروف الأولاد — بنفسجي
        0xFF00838F.toInt(), // الريفيطايما ومواد التضيف — أزرق مخضر
        0xFF1565C0.toInt(), // مصروف يومي — أزرق
        0xFF8D6E63.toInt()  // أخرى — بني
    )

    /** لون التصنيف بالاسم (القيم القديمة غير المعروفة ترجع لون "أخرى") */
    fun color(category: String): Int {
        val index = all.indexOf(category)
        return if (index >= 0) PALETTE[index] else PALETTE.last()
    }
}

/**
 * كيان المصروف: السجل الأساسي لكل عملية شراء.
 * المعرّف UUID عشوائي حتى لا يتصادم عند المزامنة بين هاتفين مختلفين.
 */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String,
    val name: String,
    val amount: Double,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    // علم "الحذف الناعم": يبقى السجل معلَّماً محذوفاً حتى تنتقل الحالة للهاتف الآخر
    val deleted: Boolean = false
)
