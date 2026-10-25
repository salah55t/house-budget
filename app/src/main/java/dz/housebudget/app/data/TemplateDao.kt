package dz.housebudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** واجهة الوصول لجدول القوالب السريعة */
@Dao
interface TemplateDao {

    @Query("SELECT * FROM quick_templates ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<QuickTemplate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: QuickTemplate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<QuickTemplate>)

    @Query("SELECT COUNT(*) FROM quick_templates")
    suspend fun count(): Int

    @Query("DELETE FROM quick_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
