package dz.housebudget.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dz.housebudget.app.sync.SyncForegroundService
import dz.housebudget.app.ui.AddExpenseFragment
import dz.housebudget.app.ui.ExpensesFragment
import dz.housebudget.app.ui.HomeFragment
import dz.housebudget.app.ui.MessagesFragment
import dz.housebudget.app.ui.ReportsFragment
import dz.housebudget.app.ui.SettingsFragment

/**
 * النشاط الرئيسي: شريط تنقل سفلي بخمس شاشات + زر عائم لإضافة مصروف سريع.
 * جميع الشاشات عبارة عن Fragments تُستبدل داخل حاوية واحدة (نمط Single-Activity).
 *
 * من الإصدار 1.5: يقلع خدمة المزامنة اللحظية والإشعارات عند فتح التطبيق،
 * ويطلب إذن الإشعارات على أندرويد 13+، ويستقبل نقرة إشعار لتفتح المراسلات.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // مفاتيح فتح تبويب معين عبر نقر إشعار (المراسلات)
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_MESSAGES = "messages"
        private const val REQ_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val fab = findViewById<FloatingActionButton>(R.id.fabAdd)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showFragment(HomeFragment()); true }
                R.id.nav_expenses -> { showFragment(ExpensesFragment()); true }
                R.id.nav_reports -> { showFragment(ReportsFragment()); true }
                R.id.nav_messages -> { showFragment(MessagesFragment()); true }
                R.id.nav_settings -> { showFragment(SettingsFragment()); true }
                else -> false
            }
        }

        fab.setOnClickListener { showFragment(AddExpenseFragment()) }

        // زر الرجوع: من شاشة الإضافة نعود للرئيسية، وإلا نتصرف طبيعياً
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.findFragmentById(R.id.container) is AddExpenseFragment) {
                    showHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // الشاشة الافتراضية عند أول إقلاع (أو المراسلات مباشرة عند النقر على إشعار)
        if (savedInstanceState == null) {
            if (isMessagesIntent(intent)) {
                bottomNav.selectedItemId = R.id.nav_messages
            } else {
                showFragment(HomeFragment())
            }
        }

        // إذن الإشعارات على أندرويد 13+ (نطلبها مرة واحدة عند أول فتح)
        requestNotificationPermissionIfNeeded()

        // قلع خدمة المزامنة اللحظية والإشعارات (لا تكرار إن كانت تعمل أصلاً)
        SyncForegroundService.start(this)
    }

    /** نقرة إشعار والتطبيق يعمل بالفعل: نفتح المراسلات مباشرة */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isMessagesIntent(intent)) {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_messages
        }
    }

    private fun isMessagesIntent(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_MESSAGES

    /** طلب إذن POST_NOTIFICATIONS على أندرويد 13 وما فوق (يُتجاهل على الأقدم) */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    /** استبدال الشاشة الحالية داخل الحاوية */
    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.container, fragment)
            .commit()
    }

    /** العودة إلى الشاشة الرئيسية (تُستدعى من شاشات أخرى مثل الإضافة) */
    fun showHome() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (nav.selectedItemId == R.id.nav_home) {
            showFragment(HomeFragment())
        } else {
            nav.selectedItemId = R.id.nav_home
        }
    }

    /** فتح تبويب معين برمجياً (مثل الانتقال للإعدادات من بطاقة "اضبط ميزانيتك") */
    fun openTab(itemId: Int) {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (nav.selectedItemId != itemId) nav.selectedItemId = itemId
    }
}
