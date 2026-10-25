package dz.housebudget.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * قاعدة البيانات المحلية الآمنة (Room):
 * تخزين داخلي كامل بدون صلاحيات تخزين خارجي — متوافق 100% مع أندرويد 10 وما فوق.
 */
@Database(
    entities = [Expense::class, QuickTemplate::class, AppSettings::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun templateDao(): TemplateDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // نمط Singleton الآمن للخيوط: نسخة واحدة من القاعدة في كل التطبيق
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "house_budget.db"
                ).build().also { instance = it }
            }
    }
}
