package com.example.imeicheck

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class FindMyStatus { ON, OFF, UNKNOWN }

data class CheckResult(val status: FindMyStatus, val model: String?, val raw: String)

/**
 * У Apple НЕТ официального публичного API для проверки "Найти iPhone" по IMEI.
 * Такую проверку предлагают сторонние сервисы (обычно платные, нужен API-ключ).
 * Впишите адрес и ключ своего провайдера и подстройте parse() под формат его ответа.
 */
object FindMyChecker {
    const val API_URL = "https://YOUR-PROVIDER.example/api/check"   // TODO
    const val API_KEY = "YOUR_API_KEY"                                // TODO

    fun check(imei: String): CheckResult {
        val url = URL("$API_URL?imei=$imei&key=$API_KEY")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        return try {
            val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    // Пример: {"model":"iPhone 13","fmi":"ON"}  -- замените под своего провайдера
    private fun parse(body: String): CheckResult {
        return try {
            val j = JSONObject(body)
            val fmi = j.optString("fmi", "").uppercase()
            val status = when (fmi) {
                "ON", "TRUE", "YES" -> FindMyStatus.ON
                "OFF", "FALSE", "NO" -> FindMyStatus.OFF
                else -> FindMyStatus.UNKNOWN
            }
            CheckResult(status, j.optString("model").ifEmpty { null }, body)
        } catch (e: Exception) {
            CheckResult(FindMyStatus.UNKNOWN, null, body)
        }
    }
}
