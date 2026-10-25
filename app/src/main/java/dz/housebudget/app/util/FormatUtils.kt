package dz.housebudget.app.util

import java.util.Locale

/** أدوات تنسيق الأرقام والمبالغ والتواريخ بالعربية */
object FormatUtils {

    const val CURRENCY = "د.ج"

    // تنسيق مبلغ مع فواصل الآلاف ورمز العملة: 12,500 د.ج
    fun money(value: Double): String {
        val rounded = kotlin.math.round(value).toLong()
        return String.format(Locale.US, "%,d %s", rounded, CURRENCY)
    }

    // تنسيق رقم مجرد بدون عملة
    fun number(value: Double): String = String.format(Locale.US, "%,.0f", value)

    // تنسيق نسبة مئوية: 42%
    fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    // تحويل رقم مكتوب بحقل إدخال إلى Double:
    // يتقبل الفاصلة العربية والنقطة + يوحّد الأرقام العربية ٠١٢٣ والفارسية ۰۱۲۳ إلى 0123
    fun parseAmount(text: String): Double? {
        val normalized = text.trim()
            .map { DIGIT_MAP[it] ?: it }
            .joinToString("")
            .replace(",", ".")
            .replace("٫", ".")
        return normalized.toDoubleOrNull()
    }

    // خريطة تحويل الأرقام العربية-الهندية والفارسية إلى اللاتينية 0-9
    private val DIGIT_MAP: Map<Char, Char> = buildMap {
        val arabicIndic = listOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val persian = listOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        arabicIndic.forEachIndexed { i, c -> put(c, ('0' + i)) }
        persian.forEachIndexed { i, c -> put(c, ('0' + i)) }
    }

    // تاريخ عربي مختصر: 12 أكتوبر
    fun shortDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = MONTHS_AR[cal.get(java.util.Calendar.MONTH)]
        return "$day $month"
    }

    // تاريخ مع الوقت: 12 أكتوبر • 14:30
    fun dateTime(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val h = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))
        val m = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE))
        return "${shortDate(millis)} • $h:$m"
    }

    // أسماء الأشهر بالعربية
    val MONTHS_AR = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    // أسماء أيام الأسبوع بالعربية (الاثنين أولاً)
    val DAYS_AR = arrayOf(
        "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"
    )
}
