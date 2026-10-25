package dz.housebudget.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dz.housebudget.app.ui.AddExpenseFragment
import dz.housebudget.app.ui.ExpensesFragment
import dz.housebudget.app.ui.HomeFragment
import dz.housebudget.app.ui.MessagesFragment
import dz.housebudget.app.ui.ReportsFragment
import dz.housebudget.app.ui.SettingsFragment

/**
 * النشاط الرئيسي: شريط تنقل سفلي بخمس شاشات + زر عائم لإضافة مصروف سريع.
 * جميع الشاشات عبارة عن Fragments تُستبدل داخل حاوية واحدة (نمط Single-Activity).
 */
class MainActivity : AppCompatActivity() {

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

        // الشاشة الافتراضية عند أول إقلاع
        if (savedInstanceState == null) showFragment(HomeFragment())
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
