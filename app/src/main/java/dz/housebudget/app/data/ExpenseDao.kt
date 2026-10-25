package dz.housebudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** واجهة الوصول لجدول المصاريف */
@Dao
interface ExpenseDao {

    // كل المصاريف غير المحذوفة من الأحدث إلى الأقدم (تدفق حي يحدّث الواجهة تلقائياً)
    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Expense>>

    // أحدث N مصروف (لقائمة "آخر العمليات" في الشاشة الرئيسية)
    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Expense>>

    // كل السجلات بما فيها المعلَّمة محذوفة (لاستعدادها للمزامنة)
    @Query("SELECT * FROM expenses")
    suspend fun getAllIncludingDeleted(): List<Expense>

    // إدراج أو تحديث دفعة سجلات (عند تطبيق حالة السيرفر)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<Expense>)

    // إدراج أو تحديث سجل واحد
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: Expense)

    @Query("DELETE FROM expenses")
    suspend fun clearAll()
}
