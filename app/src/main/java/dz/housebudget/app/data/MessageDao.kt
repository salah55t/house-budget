package dz.housebudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * واجهة الوصول لجدول المراسلات (طلبات الشراء + الرسائل النصية).
 * المحذوفة ناعماً لا تظهر في العرض لكنها تبقى لترفع في المزامنة.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE deleted = 0")
    fun observeAll(): Flow<List<HouseMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: HouseMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<HouseMessage>)

    /** جلب كل المراسلات بما فيها المحذوفة ناعماً — لرفعها في المزامنة */
    @Query("SELECT * FROM messages")
    suspend fun getAllIncludingDeleted(): List<HouseMessage>

    @Query("SELECT COUNT(*) FROM messages WHERE deleted = 0")
    suspend fun count(): Int
}
