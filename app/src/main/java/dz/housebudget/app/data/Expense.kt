package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** تصنيفات المصاريف المدعومة في التطبيق */
object Categories {
    const val FOOD = "FOOD"     // أغذية
    const val BILLS = "BILLS"   // فواتير
    const val FUN = "FUN"       // ترفيه
    const val OTHER = "OTHER"   // أخرى

    /** قائمة التصنيفات بترتيب العرض */
    val all = listOf(FOOD, BILLS, FUN, OTHER)

    /** الاسم العربي المعروض لكل تصنيف */
    fun arabicName(category: String): String = when (category) {
        FOOD -> "أغذية"
        BILLS -> "فواتير"
        FUN -> "ترفيه"
        else -> "أخرى"
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
