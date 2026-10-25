package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * قالب سريع لمشتريات متكررة (خبز، حليب، كهرباء...) لتسريع الإدخال بنقرة واحدة.
 * القوالب محلية لكل هاتف ولا تُزامن.
 */
@Entity(tableName = "quick_templates")
data class QuickTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Double,
    val category: String,
    val sortOrder: Int = 0
)
