package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * قالب سريع لمشتريات متكررة (خبز، حليب، كهرباء...) لتسريع الإدخال بنقرة واحدة.
 * معرّف UUID + طابع زمني + علامة حذف ناعم ليشارك في المزامنة بين الهاتفين
 * بنفس قاعدة "الأحدث يفوز" المستخدمة للمصاريف.
 */
@Entity(tableName = "quick_templates")
data class QuickTemplate(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val category: String,
    val sortOrder: Int = 0,
    // طابع آخر تعديل: يحسم الأقدمية عند دمج نسختي الهاتفين
    val updatedAt: Long = 0,
    // حذف ناعم: السجل يبقى حتى تصل علامة الحذف للهاتف الآخر
    val deleted: Boolean = false
)
