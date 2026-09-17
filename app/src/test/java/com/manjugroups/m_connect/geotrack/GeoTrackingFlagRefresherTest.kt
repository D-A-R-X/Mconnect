package com.manjugroups.m_connect.geotrack

import com.google.gson.Gson
import com.manjugroups.m_connect.network.SessionFlagsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Staff clocked in, web showed the punch-in location, but never Online or a
 * route. The phone's geoTrackingEnabled was written only at login, so a stale
 * false made GeoTrackBootstrapSync end tracking before it ever started.
 */
class GeoTrackingFlagRefresherTest {

    @Test
    fun `server enabling GeoTrack turns it on`() {
        assertEquals(true, GeoTrackingFlagRefresher.resolve(success = true, serverValue = true))
    }

    @Test
    fun `server disabling GeoTrack turns it off`() {
        assertEquals(false, GeoTrackingFlagRefresher.resolve(success = true, serverValue = false))
    }

    @Test
    fun `a failed validation never changes the flag`() {
        assertNull(GeoTrackingFlagRefresher.resolve(success = false, serverValue = false))
        assertNull(GeoTrackingFlagRefresher.resolve(success = false, serverValue = true))
    }

    @Test
    fun `a payload without the field never switches tracking off`() {
        val parsed = Gson().fromJson("""{"success":true,"user":{"name":"x"}}""", SessionFlagsResponse::class.java)
        assertNull(GeoTrackingFlagRefresher.resolve(parsed.success, parsed.user?.geoTrackingEnabled))
    }

    @Test
    fun `the real validate-session shape is read`() {
        val parsed = Gson().fromJson(
            """{"success":true,"user":{"_id":"s1","geoTrackingEnabled":true,"isImpersonating":false}}""",
            SessionFlagsResponse::class.java,
        )
        assertEquals(true, GeoTrackingFlagRefresher.resolve(parsed.success, parsed.user?.geoTrackingEnabled))
    }
}
