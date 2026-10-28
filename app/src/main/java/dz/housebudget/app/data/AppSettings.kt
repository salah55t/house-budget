package dz.housebudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** نوع دورة الميزانية */
object PeriodTypes {
    const val MONTHLY = "MONTHLY"   // دورة شهرية
    const val WEEKLY = "WEEKLY"     // دورة أسبوعية
}

/**
 * إعدادات التطبيق: صف وحيد بمعرّف ثابت = 1.
 * الحقول المتزامنة بين الهاتفين: الميزانية والدورة والادخار فقط.
 * الرابط ورمز الربط حقول محلية لكل هاتف.
 */
@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val totalBudget: Double = 0.0,
    val periodType: String = PeriodTypes.MONTHLY,
    // يوم بداية الدورة: 1..31 للدورة الشهرية، أو 1..7 (الاثنين..الأحد) للأسبوعية
    val startDay: Int = 1,
    val savingGoal: Double = 0.0,
    val savingModeEnabled: Boolean = false,
    // رمز الربط بين الهاتفين (محلي: لا يُزامن)
    val houseCode: String = "",
    // رابط سيرفر Render (محلي: لا يُزامن)
    val serverUrl: String = "",
    val updatedAt: Long = 0
)
