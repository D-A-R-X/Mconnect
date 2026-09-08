package com.manjugroups.m_connect.auth

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.EmployeePasswordLoginRequest
import com.manjugroups.m_connect.network.EmployeePasswordLoginResponse
import com.manjugroups.m_connect.network.SendOtpRequest
import com.manjugroups.m_connect.network.SendOtpResponse
import com.manjugroups.m_connect.network.DeviceBindingRecoveryConfirmRequest
import com.manjugroups.m_connect.network.DeviceBindingRecoveryRequest
import com.manjugroups.m_connect.network.VerifiedOtpDeviceRecoveryConfirmRequest
import com.manjugroups.m_connect.network.VerifyOtpResponse
import com.manjugroups.m_connect.network.VerifyOtpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class EmployeeLoginContractTest {
    @Test
    fun `mobile otp preflight sends device identity and parses account conflict`() {
        val request = Gson().toJsonTree(
            SendOtpRequest(
                phone = "9876543210",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
            )
        ).asJsonObject

        assertEquals("9876543210", request["phone"].asString)
        assertEquals("mobile", request["deviceType"].asString)
        assertEquals("android-id", request["deviceId"].asString)
        assertEquals("android", request["devicePlatform"].asString)
        assertEquals(BuildConfig.VERSION_NAME, request["appVersion"].asString)
        assertEquals(BuildConfig.VERSION_CODE, request["appBuild"].asInt)

        val response = Gson().fromJson(
            """{"success":false,"code":"DEVICE_BOUND_TO_ANOTHER_ACCOUNT","error":"conflict"}""",
            SendOtpResponse::class.java,
        )
        assertFalse(response.success)
        assertEquals("DEVICE_BOUND_TO_ANOTHER_ACCOUNT", response.code)
    }

    @Test
    fun `employee login request keeps backend field names`() {
        val json = Gson().toJson(
            EmployeePasswordLoginRequest(
                employeeId = "22026",
                password = "secret-value",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
                batteryPct = 80.0,
            ),
        )
        val objectValue = JsonParser.parseString(json).asJsonObject

        assertEquals("22026", objectValue["employeeId"].asString)
        assertEquals("secret-value", objectValue["password"].asString)
        assertEquals("mobile", objectValue["deviceType"].asString)
        assertEquals("android-id", objectValue["deviceId"].asString)
        assertEquals("android", objectValue["devicePlatform"].asString)
        assertEquals("Test phone", objectValue["deviceModel"].asString)
        assertEquals(80.0, objectValue["batteryPct"].asDouble, 0.0)
        assertEquals(BuildConfig.VERSION_NAME, objectValue["appVersion"].asString)
        assertEquals(BuildConfig.VERSION_CODE, objectValue["appBuild"].asInt)
        assertFalse(objectValue.has("a"))
    }

    @Test
    fun `otp verification explicitly declares a mobile device request`() {
        val request = Gson().toJsonTree(
            VerifyOtpRequest(
                phone = "9876543210",
                otp = "123456",
                deviceId = "android-id",
            )
        ).asJsonObject

        assertEquals("mobile", request["deviceType"].asString)
        assertEquals(BuildConfig.VERSION_NAME, request["appVersion"].asString)
        assertEquals(BuildConfig.VERSION_CODE, request["appBuild"].asInt)
    }

    @Test
    fun `employee login parses cross-account ownership response`() {
        val body = """{
            "success":false,
            "code":"DEVICE_BOUND_TO_ANOTHER_ACCOUNT",
            "error":"This device is already linked to SARA.R.",
            "boundAccountName":"SARA.R"
        }""".trimIndent()
        val response = Gson().fromJson(body, EmployeePasswordLoginResponse::class.java)

        assertEquals("DEVICE_BOUND_TO_ANOTHER_ACCOUNT", response.code)
        assertEquals("SARA.R", response.boundAccountName)
        assertTrue(EmployeeLoginErrorParser.isDeviceLinkedToAnotherAccount(body))
        assertFalse(EmployeeLoginErrorParser.isDeviceBound(body))
    }

    @Test
    fun `employee login parser tolerates string role level in release response`() {
        val response = EmployeePasswordLoginResponseParser.parse(
            """{
                "success":true,
                "token":"session-token",
                "mustChangePassword":false,
                "user":{
                    "_id":"staff-id",
                    "employeeId":"22026",
                    "name":"Test Staff",
                    "phone":9876543210,
                    "role":"staff",
                    "roleLevel":"40",
                    "isAdmin":"false",
                    "geoTrackingEnabled":"true"
                }
            }""".trimIndent(),
        )

        assertTrue(response?.success == true)
        assertEquals("session-token", response?.token)
        assertEquals("9876543210", response?.user?.phone)
        assertEquals(40, response?.user?.roleLevel)
        assertTrue(response?.user?.geoTrackingEnabled == true)
    }

    @Test
    fun `employee login parser rejects non json success response`() {
        assertEquals(null, EmployeePasswordLoginResponseParser.parse("<html>gateway error</html>"))
    }

    @Test
    fun `login error parser reads direct and nested server messages`() {
        assertEquals(
            "Invalid Employee ID or password",
            EmployeeLoginErrorParser.parseResponseMessage(
                """{"success":false,"error":"Invalid Employee ID or password"}""",
            ),
        )
        assertEquals(
            "This account is already locked to another device",
            EmployeeLoginErrorParser.parseResponseMessage(
                """{"error":{"detail":"This account is already locked to another device"}}""",
            ),
        )
    }

    @Test
    fun `empty 401 never leaks raw HTTP status`() {
        val message = EmployeeLoginErrorParser.message(401, null, "Unable to sign in")

        assertTrue(message.contains("Sign-in was rejected"))
        assertTrue(message.contains("device lock"))
        assertFalse(message.contains("HTTP 401"))
    }

    @Test
    fun `device recovery payloads preserve identity and challenge fields`() {
        val request = Gson().toJsonTree(
            DeviceBindingRecoveryRequest(
                employeeId = "22026",
                password = "secret-value",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
            )
        ).asJsonObject
        assertEquals("22026", request["employeeId"].asString)
        assertEquals("android-id", request["deviceId"].asString)
        assertFalse(request.has("attestationToken"))

        val confirm = Gson().toJsonTree(
            DeviceBindingRecoveryConfirmRequest(
                challengeId = "challenge-id",
                otp = "123456",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
            )
        ).asJsonObject
        assertEquals("challenge-id", confirm["challengeId"].asString)
        assertEquals("123456", confirm["otp"].asString)
        assertEquals("android-id", confirm["deviceId"].asString)

        val verifiedOtpConfirm = Gson().toJsonTree(
            VerifiedOtpDeviceRecoveryConfirmRequest(
                recoveryToken = "single-use-proof",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
            )
        ).asJsonObject
        assertEquals("single-use-proof", verifiedOtpConfirm["recoveryToken"].asString)
        assertEquals("android-id", verifiedOtpConfirm["deviceId"].asString)
        assertFalse(verifiedOtpConfirm.has("otp"))
    }

    @Test
    fun `only stable device-bound code enables recovery`() {
        assertTrue(
            EmployeeLoginErrorParser.isDeviceBound(
                """{"success":false,"code":"DEVICE_BOUND_TO_OTHER_DEVICE"}"""
            )
        )
        assertFalse(EmployeeLoginErrorParser.isDeviceBound("""{"error":"Invalid password"}"""))
        assertFalse(EmployeeLoginErrorParser.isDeviceBound(null))
    }

    @Test
    fun `canonical legacy binding response enables recovery but generic errors do not`() {
        assertTrue(
            EmployeeLoginErrorParser.isDeviceBound(
                """{"success":false,"error":"This account is already locked to another device (Pixel 7). Ask your admin to reset the device lock before signing in here."}""",
            ),
        )
        assertFalse(
            EmployeeLoginErrorParser.isDeviceBound(
                """{"success":false,"error":"Unable to sign in"}""",
            ),
        )
        assertFalse(
            EmployeeLoginErrorParser.isDeviceBound(
                """{"success":false,"error":"Invalid Employee ID or password"}""",
            ),
        )
    }

    @Test
    fun `otp binding conflict parses single use recovery proof`() {
        val response = Gson().fromJson(
            """{
                "success":false,
                "code":"DEVICE_BOUND_TO_OTHER_DEVICE",
                "error":"This account is already locked to another device.",
                "recoveryToken":"opaque-proof",
                "recoveryExpiresInSeconds":300
            }""".trimIndent(),
            VerifyOtpResponse::class.java,
        )

        assertFalse(response.success)
        assertEquals("DEVICE_BOUND_TO_OTHER_DEVICE", response.code)
        assertEquals("opaque-proof", response.recoveryToken)
        assertEquals(300, response.recoveryExpiresInSeconds)
    }

    @Test
    fun `device recovery methods use exact production routes`() {
        val routes = ApiService::class.java.methods
            .filter {
                it.name in setOf(
                    "requestDeviceBindingRecovery",
                    "confirmDeviceBindingRecovery",
                    "confirmVerifiedOtpDeviceRecovery",
                )
            }
            .associate { method -> method.name to method.getAnnotation(POST::class.java)?.value }

        assertEquals(
            "api/auth/device-binding/recovery/request",
            routes["requestDeviceBindingRecovery"],
        )
        assertEquals(
            "api/auth/device-binding/recovery/confirm",
            routes["confirmDeviceBindingRecovery"],
        )
        assertEquals(
            "api/auth/device-binding/recovery/confirm-verified-otp",
            routes["confirmVerifiedOtpDeviceRecovery"],
        )
    }
}
