package dz.housebudget.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * قاعدة البيانات المحلية الآمنة (Room):
 * تخزين داخلي كامل بدون صلاحيات تخزين خارجي — متوافق 100% مع أندرويد 10 وما فوق.
 *
 * النسخة 2: القوالب السريعة أصبحت قابلة للمزامنة (معرّف UUID + طابع زمني + حذف ناعم)
 * مع هجرة تحفظ القوالب القديمة كما هي.
 */
@Database(
    entities = [Expense::class, QuickTemplate::class, AppSettings::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun templateDao(): TemplateDao
    abstract fun settingsDao(): SettingsDao

    companion object {

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * هجرة 1 → 2: جدول quick_templates تغيّر مفتاحه من رقمي إلى UUID
         * وأضيف إليه حقلَي updatedAt وdeleted. نعيد بناء الجدول مع نقل
         * القوالب القديمة كما هي (المعرف القديم يصبح نصاً).
         */
        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE quick_templates_new (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "amount REAL NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "deleted INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO quick_templates_new " +
                        "(id, name, amount, category, sortOrder, updatedAt, deleted) " +
                        "SELECT CAST(id AS TEXT), name, amount, category, sortOrder, 0, 0 " +
                        "FROM quick_templates"
                )
                db.execSQL("DROP TABLE quick_templates")
                db.execSQL("ALTER TABLE quick_templates_new RENAME TO quick_templates")
            }
        }

        // نمط Singleton الآمن للخيوط: نسخة واحدة من القاعدة في كل التطبيق
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "house_budget.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
