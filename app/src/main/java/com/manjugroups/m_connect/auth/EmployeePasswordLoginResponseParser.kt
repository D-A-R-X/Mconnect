package com.manjugroups.m_connect.auth

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.EmployeePasswordLoginResponse
import com.manjugroups.m_connect.network.UserInfo

/**
 * Parses the small public login envelope without reflective DTO conversion.
 * This keeps Play/R8 builds tolerant of optional staff fields changing type.
 */
internal object EmployeePasswordLoginResponseParser {
    fun parse(responseBody: String?): EmployeePasswordLoginResponse? {
        val root = responseBody
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: return null

        return EmployeePasswordLoginResponse(
            success = root.boolean("success") ?: false,
            token = root.string("token"),
            user = root.objectValue("user")?.toUserInfo(),
            mustChangePassword = root.boolean("mustChangePassword") ?: false,
            error = root.string("error"),
            code = root.string("code"),
            boundAccountName = root.string("boundAccountName"),
        )
    }

    private fun JsonObject.toUserInfo(): UserInfo = UserInfo(
        staffId = string("_id") ?: string("staffId") ?: string("id"),
        employeeId = string("employeeId"),
        name = string("name"),
        role = string("role"),
        phone = string("phone"),
        email = string("email"),
        designation = string("designation"),
        department = string("department"),
        isAdmin = boolean("isAdmin") ?: false,
        roleLevel = int("roleLevel"),
        status = string("status"),
        geoTrackingEnabled = boolean("geoTrackingEnabled") ?: false,
        mustChangePassword = boolean("mustChangePassword") ?: false,
        canBill = boolean("canBill") ?: false,
    )

    private fun JsonObject.value(name: String): JsonElement? =
        entrySet().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?.takeUnless { it.isJsonNull }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        value(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.string(name: String): String? = value(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive
        ?.takeIf { it.isString || it.isNumber || it.isBoolean }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun JsonObject.boolean(name: String): Boolean? {
        val raw = string(name)?.lowercase() ?: return null
        return when (raw) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    private fun JsonObject.int(name: String): Int? = string(name)?.toDoubleOrNull()?.toInt()
}
