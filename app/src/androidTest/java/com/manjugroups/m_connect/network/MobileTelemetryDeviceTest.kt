package com.manjugroups.m_connect.network

import android.app.Application
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjugroups.m_connect.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit handset verification only. No production API request, crash or release hook. */
@RunWith(AndroidJUnit4::class)
class MobileTelemetryDeviceTest {
    @Test fun controlledErrorUsesTheRealQueueAndIsAcknowledgedByPostHog() {
        assumeTrue("External ingestion verification requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("posthogVerification") == "true")
        assertTrue("Never run this on a release app", BuildConfig.DEBUG)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        IssueReporter.install(app)
        val type = IssueReporter::class.java
        // White-box invocation keeps test controls out of production source and the manifest.
        val build = type.getDeclaredMethod("errorEvent", Throwable::class.java).apply { isAccessible = true }
        val sentinel = "DEVICE_TEST_MESSAGE_MUST_NOT_LEAVE_PHONE"
        val report = build.invoke(IssueReporter, IllegalStateException(sentinel)) as JSONObject
        assertFalse("Exception messages must not be captured", report.toString().contains(sentinel))
        val run = UUID.randomUUID().toString()
        val properties = report.getJSONObject("properties")
        properties.put("verification", true).put("verification_run", run)
            .put("validation_source", "android-instrumentation")
            .put("\$exception_fingerprint", "device-verification-$run")
        report.put("fingerprint", "device-verification-$run")
        properties.getJSONArray("\$exception_list").getJSONObject(0)
            .put("type", "MobileTelemetryDeviceVerification")
            .put("value", "Controlled on-phone telemetry verification, not a real crash")
            .getJSONObject("mechanism").put("handled", true).put("synthetic", true)
        val add = type.getDeclaredMethod("add", JSONObject::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val submit = type.getDeclaredMethod("submit", Class.forName("kotlin.jvm.functions.Function0"))
            .apply { isAccessible = true }
        val action: () -> Unit = { add.invoke(IssueReporter, report, false, false); Unit }
        assertTrue("Reporter ingress rejected test", submit.invoke(IssueReporter, action) as Boolean)
        val file = File(app.filesDir, "mobile_posthog_queue_v1.json")
        fun queued(): Boolean = runCatching {
            val queue = JSONArray(file.readText())
            (0 until queue.length()).any {
                queue.getJSONObject(it).getJSONObject("properties").optString("verification_run") == run
            }
        }.getOrDefault(false)
        val admittedUntil = SystemClock.elapsedRealtime() + 10_000
        while (!queued() && SystemClock.elapsedRealtime() < admittedUntil) Thread.sleep(100)
        assertTrue("Test was not persisted (check daily budget/network diagnostics)", queued())
        println("MOBILE_TELEMETRY_DEVICE_RUN=$run")
        // Use the real minute-spaced flush, never force it or bypass its network/backoff policy.
        val ackUntil = SystemClock.elapsedRealtime() + 100_000
        while (queued() && SystemClock.elapsedRealtime() < ackUntil) Thread.sleep(1_000)
        assertFalse("PostHog did not acknowledge the queued test within 100 seconds", queued())
        println("MOBILE_TELEMETRY_DEVICE_ACKNOWLEDGED=$run")
    }
}
