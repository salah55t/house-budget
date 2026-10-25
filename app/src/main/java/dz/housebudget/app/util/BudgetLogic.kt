package dz.housebudget.app.util

import dz.housebudget.app.data.AppSettings
import dz.housebudget.app.data.PeriodTypes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

/** معلومات الدورة الحالية (شهرية أو أسبوعية) */
data class PeriodInfo(
    val startMillis: Long,   // بداية الدورة (أول لحظة)
    val endMillis: Long,     // نهاية الدورة (آخر لحظة شاملة)
    val daysTotal: Int,      // إجمالي أيام الدورة
    val daysElapsed: Int,    // الأيام الماضية
    val daysLeft: Int        // الأيام المتبقية
)

/** حالة مؤشر الصحة المالية (تنبيه الإفلاس التدريجي) */
enum class BudgetStatus { HEALTHY, WARNING, DANGER }

/** ملخص الميزانية الجاهز للعرض في الشاشة الرئيسية */
data class BudgetSummary(
    val period: PeriodInfo,
    val total: Double,               // الميزانية الكلية للدورة
    val spent: Double,               // ما صُرف حتى الآن
    val remaining: Double,           // المتبقي (قد يكون سالباً)
    val remainingPercent: Double,    // نسبة المتبقي المعروضة (0..100)
    val spentPercent: Double,        // نسبة ما صُرف (0..100)
    val status: BudgetStatus,        // الحالة اللونية: أخضر/أصفر/أحمر
    val statusLabel: String,         // نص حالة: ممتاز/حذر/خطر
    val dailyAllowed: Double,        // المسموح صرفه يومياً (بعد خصم هدف الادخار إن كان مفعلاً)
    val dailyAverageSpent: Double,   // معدل الصرف الفعلي اليومي حتى الآن
    val savingGoal: Double,          // هدف الادخار
    val savingModeEnabled: Boolean,  // هل الوضع الاقتصادي مفعّل؟
    val riskMessage: String?,        // رسالة التنبيه الذكي (null إن لا خطر)
    val daysUntilEmpty: Int?         // توقع عدد الأيام حتى نفاد الميزانية بالمعدل الحالي
)

/**
 * محرك حسابات الميزانية: كل المنطق المالي للتطبيق في مكان واحد.
 * يعتمد java.time (متوفر منذ API 26، ونحن على 29+) دون أي مكتبات إضافية.
 */
object BudgetLogic {

    /** حساب حدود الدورة الحالية حسب نوعها ويوم بدايتها */
    fun currentPeriod(settings: AppSettings, now: LocalDateTime = LocalDateTime.now()): PeriodInfo {
        val today = now.toLocalDate()
        val zone = ZoneId.systemDefault()

        val start: LocalDate
        val end: LocalDate

        if (settings.periodType == PeriodTypes.WEEKLY) {
            // الدورة الأسبوعية: يوم البداية 1..7 يحاكي الاثنين..الأحد
            val dow = DayOfWeek.of(settings.startDay.coerceIn(1, 7))
            start = today.with(TemporalAdjusters.previousOrSame(dow))
            end = start.plusDays(6)
        } else {
            // الدورة الشهرية: يوم البداية 1..31 مع معالجة الشهور الأقصر بأمان
            val day = settings.startDay.coerceIn(1, 31)
            var s = safeDate(today.year, today.monthValue, day)
            if (s.isAfter(today)) {
                val prev = s.minusMonths(1)
                s = safeDate(prev.year, prev.monthValue, day)
            }
            start = s
            val next = start.plusMonths(1)
            end = safeDate(next.year, next.monthValue, day).minusDays(1)
        }

        val daysTotal = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val elapsed = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, daysTotal)

        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = end.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

        return PeriodInfo(
            startMillis = startMillis,
            endMillis = endMillis,
            daysTotal = daysTotal,
            daysElapsed = elapsed,
            daysLeft = (daysTotal - elapsed).coerceAtLeast(0)
        )
    }

    // تاريخ آمن: يوم محدد من شهر مع تعديل تلقائي إن كان الشهر أقصر (مثل يوم 31 في فبراير)
    private fun safeDate(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val offset = (day - 1).coerceAtMost(first.lengthOfMonth() - 1)
        return first.plusDays(offset.toLong())
    }

    /** بناء الملخص الكامل: كل المؤشرات والتنبيهات الذكية */
    fun summarize(settings: AppSettings, spent: Double, period: PeriodInfo): BudgetSummary {
        val total = settings.totalBudget
        val remaining = total - spent

        val spentPercent = if (total > 0) (spent / total * 100.0) else 0.0
        val remainingPercent = if (total > 0) (remaining / total * 100.0).coerceIn(0.0, 100.0) else 0.0

        // مقارنة نسبة ما تبقى من المال بنسبة ما تبقى من الأيام
        val daysLeftPercent = 100.0 - (period.daysElapsed.toDouble() / period.daysTotal * 100.0)
        val lead = remainingPercent - daysLeftPercent

        val status = when {
            total <= 0.0 -> BudgetStatus.HEALTHY          // لم تُضبط الميزانية بعد
            remaining <= 0.0 -> BudgetStatus.DANGER       // نفدت الميزانية
            lead >= 5.0 -> BudgetStatus.HEALTHY           // المال يكفي بمرتاح
            lead >= -10.0 -> BudgetStatus.WARNING         // على الحافة تقريباً
            else -> BudgetStatus.DANGER                   // الصرف أسرع من الوقت
        }

        val statusLabel = when {
            total <= 0.0 -> "غير مضبوط"
            remaining <= 0.0 -> "نفدت"
            status == BudgetStatus.HEALTHY -> "ممتاز"
            status == BudgetStatus.WARNING -> "حذر"
            else -> "خطر"
        }

        // توقع يوم النفاد: المتبقي ÷ معدل الصرف اليومي الفعلي
        val daysElapsedSafe = max(period.daysElapsed, 1)
        val dailyAvg = spent / daysElapsedSafe
        val daysUntilEmpty = if (dailyAvg > 0 && remaining > 0) {
            kotlin.math.ceil(remaining / dailyAvg).toInt()
        } else null

        // بناء رسالة التنبيه الذكي (تظهر فقط عند وجود خطر فعلي)
        val riskMessage = when {
            total <= 0.0 -> null
            remaining <= 0.0 -> "نفدت الميزانية بالكامل! راجع تقاريرك واضبط ميزانية جديدة للدورة القادمة."
            status == BudgetStatus.DANGER && daysUntilEmpty != null && daysUntilEmpty < period.daysLeft ->
                "خطر! بهذا المعدل ستنفد ميزانيتك خلال $daysUntilEmpty يوم تقريباً، أي قبل نهاية الدورة بـ ${period.daysLeft - daysUntilEmpty} يوم. خفف الصرف فوراً."
            status == BudgetStatus.DANGER ->
                "خطر مرتفع: صرفك اليومي يتجاوز وتيرة الدورة الحالية بشكل خطير."
            status == BudgetStatus.WARNING ->
                "تنبيه: ميزانيتك تتناقص أسرع قليلاً من الأيام المتبقية. حاول خفض المصاريف غير الضرورية."
            else -> null
        }

        // المسموح اليومي: الوضع الاقتصادي يخصم هدف الادخار أولاً
        val daysLeftSafe = max(period.daysLeft, 1)
        val effectiveRemaining = if (settings.savingModeEnabled) remaining - settings.savingGoal else remaining
        val dailyAllowed = if (total > 0) max(effectiveRemaining, 0.0) / daysLeftSafe else 0.0

        return BudgetSummary(
            period = period,
            total = total,
            spent = spent,
            remaining = remaining,
            remainingPercent = remainingPercent,
            spentPercent = spentPercent.coerceIn(0.0, 100.0),
            status = status,
            statusLabel = statusLabel,
            dailyAllowed = dailyAllowed,
            dailyAverageSpent = dailyAvg,
            savingGoal = settings.savingGoal,
            savingModeEnabled = settings.savingModeEnabled,
            riskMessage = riskMessage,
            daysUntilEmpty = daysUntilEmpty
        )
    }
}
