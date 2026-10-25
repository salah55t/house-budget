package dz.housebudget.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dz.housebudget.app.data.AppDatabase
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.QuickTemplate
import dz.housebudget.app.repo.BudgetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * كلاس التطبيق: تهيئة المستودع، فرض اللغة العربية (RTL كامل)،
 * وزرع القوالب السريعة الافتراضية عند أول تشغيل.
 */
class HouseBudgetApp : Application() {

    companion object {
        // فترة المزامنة التلقائية: دقيقة واحدة بالضبط (60 ثانية)
        const val SYNC_INTERVAL_MS = 60_000L
        // مهلة قصيرة قبل المزامنة الأولى لإتاحة وقت لإقلاع التطبيق والشبكة
        const val FIRST_SYNC_DELAY_MS = 5_000L
    }

    // نطاق coroutine عام للتطبيق: يُستخدم للمزامنة الصامتة في الخلفية
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // فرض اللغة العربية على التطبيق كاملاً مهما كانت لغة الهاتف (RTL تلقائي)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))

        // تهيئة المستودع قبل أي شاشة
        BudgetRepository.init(this)

        // زرع القوالب الافتراضية مرة واحدة فقط
        appScope.launch { seedDefaultTemplates() }

        // بدء المزامنة التلقائية الدورية: كل دقيقة واحدة ما دام التطبيق يعمل
        appScope.launch { autoSyncLoop() }
    }

    /**
     * حلقة المزامنة التلقائية في الخلفية: تنفّذ مزامنة كاملة مع السيرفر
     * كل دقيقة واحدة (جلب الجديد من الهاتف الآخر + رفع ما أُدخل محلياً).
     *
     * النتائج تُتجاهل بصمت هنا؛ رسائل الخطأ تظهر فقط في المزامنة اليدوية
     * من شاشة الإعدادات. والدالة syncNow تتحقق داخلياً من ضبط رابط
     * السيرفر ورمز الربط قبل أي اتصال، لذا لا ضرر قبل الإعداد.
     */
    private suspend fun autoSyncLoop() {
        delay(FIRST_SYNC_DELAY_MS)
        while (true) {
            try {
                BudgetRepository.syncNow()
            } catch (_: Exception) {
                // مزامنة صامتة: نؤجّل المحاولة إلى الدورة القادمة بعد دقيقة
            }
            delay(SYNC_INTERVAL_MS)
        }
    }

    /** القوالب الجاهزة لأكثر المشتريات تكراراً: خبز، حليب، كهرباء، ماء، إنترنت */
    private suspend fun seedDefaultTemplates() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("templates_seeded", false)) return

        val dao = AppDatabase.get(this).templateDao()
        if (dao.count() == 0) {
            dao.upsertAll(
                listOf(
                    QuickTemplate(name = "خبز", amount = 50.0, category = Categories.FOOD, sortOrder = 0),
                    QuickTemplate(name = "حليب", amount = 120.0, category = Categories.FOOD, sortOrder = 1),
                    QuickTemplate(name = "كهرباء", amount = 1500.0, category = Categories.BILLS, sortOrder = 2),
                    QuickTemplate(name = "ماء", amount = 800.0, category = Categories.BILLS, sortOrder = 3),
                    QuickTemplate(name = "إنترنت", amount = 1000.0, category = Categories.BILLS, sortOrder = 4)
                )
            )
        }
        prefs.edit().putBoolean("templates_seeded", true).apply()
    }
}
