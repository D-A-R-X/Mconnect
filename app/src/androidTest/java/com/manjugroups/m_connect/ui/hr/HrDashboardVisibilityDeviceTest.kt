package com.manjugroups.m_connect.ui.hr

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit UI smoke only; requires signed-in debug phone without active duty/tracking. */
@RunWith(AndroidJUnit4::class)
class HrDashboardVisibilityDeviceTest {
    @Test fun prewarmedAttendanceDoesNotPollAndVisibleAttendanceStillRefreshes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hrVisibilityVerification") == "true")
        assertTrue(BuildConfig.DEBUG)
        val clock = HrDashboardFragment::class.java.getDeclaredField("clockStatusWatcherJob")
            .apply { isAccessible = true }
        val fence = HrDashboardFragment::class.java.getDeclaredField("geofenceWatcherJob")
            .apply { isAccessible = true }
        val select = MainActivity::class.java.getDeclaredMethod("selectTab", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val prewarm = MainActivity::class.java.getDeclaredField("prewarmRunnable")
            .apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                select.invoke(activity, MainActivity.TAB_HOME)
                activity.supportFragmentManager.executePendingTransactions()
                // Run the production prewarm path deterministically, not its delayed timer.
                (prewarm.get(activity) as Runnable).run()
                activity.supportFragmentManager.executePendingTransactions()
                val hr = activity.supportFragmentManager.findFragmentByTag("root_tab_hr") as HrDashboardFragment
                assertTrue("Exercise the real hidden prewarmed tab", hr.isHidden)
                assertFalse((clock.get(hr) as? Job)?.isActive == true)
                assertFalse((fence.get(hr) as? Job)?.isActive == true)
                select.invoke(activity, MainActivity.TAB_HR)
            }
            Thread.sleep(1_000)
            scenario.onActivity { activity ->
                val hr = activity.supportFragmentManager.findFragmentByTag("root_tab_hr") as HrDashboardFragment
                assertFalse(hr.isHidden)
                assertTrue("Visible attendance still polls for external punches", (clock.get(hr) as? Job)?.isActive == true)
                assertTrue("Visible clock-in fence remains enforced", (fence.get(hr) as? Job)?.isActive == true)
                select.invoke(activity, MainActivity.TAB_HOME)
            }
            Thread.sleep(1_000)
            scenario.onActivity { activity ->
                val hr = activity.supportFragmentManager.findFragmentByTag("root_tab_hr") as HrDashboardFragment
                assertTrue(hr.isHidden)
                assertFalse("Hiding must stop the attendance poll", (clock.get(hr) as? Job)?.isActive == true)
                assertFalse("Hiding must stop only the UI fence watcher", (fence.get(hr) as? Job)?.isActive == true)
            }
        }
    }
}
