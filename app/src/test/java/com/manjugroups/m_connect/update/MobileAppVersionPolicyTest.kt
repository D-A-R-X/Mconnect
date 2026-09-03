package com.manjugroups.m_connect.update

import com.manjugroups.m_connect.network.MobileAppVersionResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAppVersionPolicyTest {
    @Test
    fun `blocks only below the minimum numeric build`() {
        val policy = MobileAppVersionResponse(
            success = true,
            latestVersion = "1.2.0",
            latestBuildNumber = 50,
            minimumSupportedVersion = "1.1.0",
            minimumSupportedBuildNumber = 43,
            updateRequired = false,
        )

        assertTrue(requiresMandatoryMobileUpdate(policy, 42))
        assertFalse(requiresMandatoryMobileUpdate(policy, 43))
        assertFalse(requiresMandatoryMobileUpdate(policy, 49))
    }

    @Test
    fun `does not turn an optional latest release into a mandatory update`() {
        val policy = MobileAppVersionResponse(
            success = true,
            latestVersion = "2.0.0",
            latestBuildNumber = 80,
            minimumSupportedVersion = "1.0.0",
            minimumSupportedBuildNumber = 10,
            updateRequired = false,
        )

        assertFalse(requiresMandatoryMobileUpdate(policy, 20))
    }

    @Test
    fun `fails open when no authoritative build threshold exists`() {
        assertFalse(
            requiresMandatoryMobileUpdate(
                MobileAppVersionResponse(
                    success = true,
                    minimumSupportedVersion = "9.0.0",
                    updateRequired = true,
                ),
                installedBuildNumber = 11,
            ),
        )
        assertFalse(
            requiresMandatoryMobileUpdate(
                MobileAppVersionResponse(success = false, minimumSupportedBuildNumber = 43),
                installedBuildNumber = 11,
            ),
        )
    }
}
