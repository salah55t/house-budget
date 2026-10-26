package dz.housebudget.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dz.housebudget.app.data.AppDatabase
import dz.housebudget.app.data.Categories
import dz.housebudget.app.data.QuickTemplate
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.sync.SyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * كلاس التطبيق: تهيئة المستودع، فرض اللغة العربية (RTL كامل)،
 * وزرع القوالب السريعة الافتراضية عند أول تشغيل.
 *
 * من الإصدار 1.5: حلقة المزامنة انتقلت إلى SyncForegroundService (مسار لحظي
 * كل 2-5 ثوانٍ + مزامنة كاملة كل دقيقة) — والنطاق العام يبقى هنا للشاشات.
 */
class HouseBudgetApp : Application() {

    // نطاق coroutine عام للتطبيق: تُستخدمه الشاشات للمزامنة الفورية الصامتة
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // فرض العربية الجزائرية (ar-DZ) على التطبيق كاملاً مهما كانت لغة الهاتف:
        // RTL كامل + أرقام لاتينية عادية 123 (وليس ١٢٣/۱۲۳) وتواريخ بالنمط الجزائري
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar-DZ"))

        // تهيئة المستودع قبل أي شاشة
        BudgetRepository.init(this)

        // زرع القوالب الافتراضية مرة واحدة فقط
        appScope.launch { seedDefaultTemplates() }

        // خدمة المزامنة اللحظية والإشعارات: محاولة قلع صامتة هنا أيضاً
        // (تفشل بهدوء إن كان الإقلاع من الخلفية على أندرويد 12+، والنشاط
        // الرئيسي وجهاز استقبال الإقلاع يضمنان القلع في الحالات الأخرى)
        SyncForegroundService.start(this)
    }

    /**
     * القوالب الجاهزة المُزرعة عند أول تشغيل — مصممة على التصنيفات السبعة:
     * مصروف يومي (خبز، حليب، ماء، ڨازوز) + خضر + لحوم.
     */
    private suspend fun seedDefaultTemplates() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("templates_seeded", false)) return

        val dao = AppDatabase.get(this).templateDao()
        if (dao.count() == 0) {
            dao.upsertAll(
                listOf(
                    QuickTemplate(name = "خبز", amount = 50.0, category = Categories.DAILY, sortOrder = 0),
                    QuickTemplate(name = "حليب", amount = 120.0, category = Categories.DAILY, sortOrder = 1),
                    QuickTemplate(name = "ماء", amount = 50.0, category = Categories.DAILY, sortOrder = 2),
                    QuickTemplate(name = "ڨازوز", amount = 100.0, category = Categories.DAILY, sortOrder = 3),
                    QuickTemplate(name = "خضر", amount = 400.0, category = Categories.VEG, sortOrder = 4),
                    QuickTemplate(name = "لحوم", amount = 1200.0, category = Categories.MEAT, sortOrder = 5)
                )
            )
        }
        prefs.edit().putBoolean("templates_seeded", true).apply()
    }
}
