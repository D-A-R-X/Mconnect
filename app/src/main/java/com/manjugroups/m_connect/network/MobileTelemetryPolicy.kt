package com.manjugroups.m_connect.network

/** Only fixed code dimensions cross this boundary, never arbitrary URLs or messages. */
internal object MobileTelemetryPolicy {
    const val MAX_QUEUE = 60
    const val BATCH_SIZE = 20
    const val FLUSH_MS = 60_000L
    const val REPEAT_MS = 600_000L
    const val MAX_AGE_MS = 3 * 86_400_000L
    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val segments = ("api hr auth attendance punch-in punch-out today my sessions session shifts " +
        "leaves leave permissions permission balance usage apply cancel list detail staff profile departments " +
        "designations roles policies policy settings mobile alerts notifications read unread mark-read " +
        "marketing clientPlaceVisits siteVisits cpVisits visits visit start end complete outcome approval " +
        "approve reject requests create update delete search projects project clients client lead leads " +
        "tracking geotrack location batch heartbeat tamper-events current day-status timeline trips " +
        "live route session-route snapped-route nearby-staff geocode-address places consent status stop " +
        "fleet travel-desk bookings dispatch driver drivers vehicles vehicle allocation cab cab-requests " +
        "chat channels messages poll typing presence members dms unread-count read-receipts send " +
        "storage upload download upload-url url lens enroll enrollment photos photo face captures " +
        "login logout verify-otp send-otp validate-session refresh password employee token device " +
        "daily-logs daily-log tasks dashboard summary stats stats-monthly monthly reports calendar " +
        "iam access work-from-home on-duty fines loans payroll payslips collections accounts land " +
        "front-desk visitors post-sales telecaller calls dialer notifications-list mobile-alerts").split(' ').toSet()

    fun route(path: String): String = path.substringBefore('?').substringBefore('#')
        .split('/').filter(String::isNotEmpty).take(12)
        .joinToString("/", prefix = "/") { if (it in segments) it else ":id" }

    fun method(method: String): String = method.takeIf { it in methods } ?: "OTHER"
    fun service(host: String, authority: String): String? = when (host.lowercase()) {
        authority.lowercase() -> "mms"
        "api-geo.theairix.com" -> "geotrack"
        "mg.theairix.com" -> "storage"
        "api-dialer.theairix.com", "dialer.theairix.com" -> "dialer"
        "api-telecaller.theairix.com" -> "telecaller"
        else -> null
    }
    fun symbol(value: String): String = value.take(160)
        .takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$<>:-]{0,159}")) } ?: "Unknown"

    fun retryDelayMs(status: Int?, failures: Int, retryAfterSeconds: Long?): Long {
        val exponential = (600_000L * (1L shl (failures - 1).coerceIn(0, 5))).coerceAtMost(21_600_000L)
        val minimum = if (status in listOf(400, 401, 403, 404, 413)) 21_600_000L else exponential
        return maxOf(minimum, (retryAfterSeconds ?: 0).coerceIn(0, 21_600) * 1_000)
    }
}

/** Runs only on the serial reporter worker; counters persist across launches. */
internal class MobileTelemetryBudget(var day: Long = -1, var errors: Int = 0, var timings: Int = 0, var exceptions: Int = 0) {
    private val seen = LinkedHashMap<String, Long>()
    private var successes = 0
    fun accept(signature: String, timing: Boolean, healthy: Boolean, now: Long, exception: Boolean = false): Boolean {
        val currentDay = now / 86_400_000
        if (day != currentDay) { day = currentDay; errors = 0; timings = 0; exceptions = 0; seen.clear() }
        if (healthy && ++successes % 20 != 0) return false
        if (timing && timings >= 20 || !timing && errors >= 40) return false
        if (exception && exceptions >= 8) return false
        seen[signature]?.let { if (now - it < MobileTelemetryPolicy.REPEAT_MS) return false }
        if (seen.size >= 64) seen.remove(seen.keys.first())
        seen[signature] = now
        if (timing) timings++ else errors++
        if (exception) exceptions++
        return true
    }
}
