package dz.housebudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** واجهة الوصول لجدول القوالب السريعة (المحذوفة ناعماً لا تظهر في العرض) */
@Dao
interface TemplateDao {

    @Query("SELECT * FROM quick_templates WHERE deleted = 0 ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<QuickTemplate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: QuickTemplate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<QuickTemplate>)

    /** جلب كل القوالب بما فيها المحذوفة ناعماً — لرفعها في المزامنة */
    @Query("SELECT * FROM quick_templates")
    suspend fun getAllIncludingDeleted(): List<QuickTemplate>

    @Query("SELECT COUNT(*) FROM quick_templates WHERE deleted = 0")
    suspend fun count(): Int
}
