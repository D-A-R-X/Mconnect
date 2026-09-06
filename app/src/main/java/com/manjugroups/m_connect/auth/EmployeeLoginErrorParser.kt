package com.manjugroups.m_connect.auth

import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal object EmployeeLoginErrorParser {
    fun message(statusCode: Int, responseBody: String?, fallback: String): String {
        parseResponseMessage(responseBody)?.let { return it }
        return when (statusCode) {
            401 -> "Sign-in was rejected. Check your Employee ID and password. If they are correct, ask admin to reset your mobile device lock."
            408, 429 -> "The server is busy right now. Please try again in a moment."
            in 400..499 -> "We couldn't process that login. Please check your details and try again."
            500 -> "Something went wrong on our end. Please try again."
            502, 503, 504 -> "The server is temporarily unavailable. Please try again in a moment."
            else -> fallback
        }
    }

    fun parseResponseMessage(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(responseBody) }.getOrNull()
            ?: return null
        return findMessage(root)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun findMessage(element: JsonElement): String? {
        if (element.isJsonObject) {
            val objectValue = element.asJsonObject
            for (key in listOf("error", "message", "detail", "reason")) {
                val value = objectValue.entrySet()
                    .firstOrNull { it.key.equals(key, ignoreCase = true) }
                    ?.value
                    ?: continue
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
                findMessage(value)?.let { return it }
            }
            objectValue.entrySet().forEach { (_, value) ->
                if (value.isJsonObject || value.isJsonArray) {
                    findMessage(value)?.let { return it }
                }
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { value ->
                findMessage(value)?.let { return it }
            }
        } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString
        }
        return null
    }
}
