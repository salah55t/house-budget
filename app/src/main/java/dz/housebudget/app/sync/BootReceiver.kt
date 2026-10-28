package dz.housebudget.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * جهاز استقبال إقلاع الهاتف: يعيد تشغيل خدمة المزامنة اللحظية والإشعارات
 * تلقائياً بعد إعادة تشغيل الجهاز — فتبقى الإشعارات مثل واتساب تعمل
 * دون أن يفتح المستخدم التطبيق يدوياً.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SyncForegroundService.start(context)
        }
    }
}
