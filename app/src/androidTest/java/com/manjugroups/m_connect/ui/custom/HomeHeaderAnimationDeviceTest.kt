package com.manjugroups.m_connect.ui.custom

import android.animation.Animator
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an authorized, signed-in debug handset with no active duty/trip. */
@RunWith(AndroidJUnit4::class)
class HomeHeaderAnimationDeviceTest {
    @Test fun floatingMotionFinishesAndDelayedMotionCanBeCancelled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiPerformanceVerification") == "true")
        assertTrue(BuildConfig.DEBUG)
        val field = HomeHeaderView::class.java.getDeclaredField("bannerFloatAnimators")
            .apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var header: HomeHeaderView? = null
            // Allow the normal entry transition to finish before the explicit animation check.
            Thread.sleep(2_000)
            scenario.onActivity { activity ->
                header = activity.findViewById(R.id.homeHeader)
                assertNotNull("Signed-in Home must be present", header)
                header!!.startFloatingAnimation()
                assertTrue("Must exercise actual animators", (field.get(header) as List<*>).isNotEmpty())
            }
            Thread.sleep(8_000)
            scenario.onActivity {
                val animators = field.get(header) as List<*>
                assertTrue("Decorative motion must settle", animators.none { (it as Animator).isStarted })
                header!!.playEntryAnimation()
                header!!.stopFloatingAnimation()
            }
            Thread.sleep(1_500)
            scenario.onActivity {
                assertTrue("A cancelled delayed start must not recreate floating animators",
                    (field.get(header) as List<*>).isEmpty())
            }
        }
    }
}
