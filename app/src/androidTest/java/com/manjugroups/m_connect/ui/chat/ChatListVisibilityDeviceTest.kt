package com.manjugroups.m_connect.ui.chat

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Opt-in signed-in debug smoke. Chat requests are injected, not sent to production. */
@RunWith(AndroidJUnit4::class)
class ChatListVisibilityDeviceTest {
    @Test fun hiddenChatDoesNotReadAndHidingCancelsRealChildRequests() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("chatVisibilityVerification") == "true")
        assertTrue(BuildConfig.DEBUG)
        val session = SessionManager(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("Sign in normally before this opt-in screen test", session.isLoggedIn && !session.mustChangePassword)
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val fake = Proxy.newProxyInstance(ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)) { _, method, args ->
            check(method.name in listOf("getConversations", "getChannels", "getStaff"))
            val block: suspend () -> Any? = {
                calls.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
            @Suppress("UNCHECKED_CAST")
            block.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
        } as ApiService
        val chat = ChatListFragment()
        ChatListFragment::class.java.getDeclaredField("api").apply { isAccessible = true }.set(chat, fake)
        val select = MainActivity::class.java.getDeclaredMethod("selectTab", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun job(name: String): Job? = ChatListFragment::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(chat) as? Job
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                select.invoke(activity, MainActivity.TAB_HOME)
                activity.supportFragmentManager.executePendingTransactions()
                activity.supportFragmentManager.findFragmentByTag("root_tab_chat")?.let {
                    activity.supportFragmentManager.beginTransaction().remove(it).commitNow()
                }
                activity.supportFragmentManager.beginTransaction()
                    .add(R.id.fragmentContainer, chat, "root_tab_chat").hide(chat).commitNow()
                assertTrue(chat.isHidden)
                assertFalse(job("refreshJob")?.isActive == true)
                assertEquals(0, calls.get())
                select.invoke(activity, MainActivity.TAB_CHAT)
            }
            waitUntil { calls.get() == 3 }
            scenario.onActivity { activity ->
                assertTrue(job("refreshJob")?.isActive == true)
                assertTrue(job("loadJob")?.isActive == true)
                select.invoke(activity, MainActivity.TAB_HOME)
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(chat.isHidden)
                assertFalse(job("refreshJob")?.isActive == true)
            }
            waitUntil { cancelled.get() == 3 }
            Thread.sleep(5_500)
            assertEquals("No hidden five-second refresh", 3, calls.get())
            scenario.onActivity { activity -> select.invoke(activity, MainActivity.TAB_CHAT) }
            waitUntil { calls.get() == 6 }
            scenario.onActivity { activity -> select.invoke(activity, MainActivity.TAB_HOME) }
            waitUntil { cancelled.get() == 6 }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Expected bounded read/cancellation state", predicate())
    }
}
