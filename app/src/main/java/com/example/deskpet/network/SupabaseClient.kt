package com.example.deskpet.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Supabase REST client for posting events and reading state.
 * Replace the URL and KEY with your own project values.
 */
object SupabaseClient {

    // TODO: Replace with your Supabase project values
    private const val SUPABASE_URL = "https://dhtokbbbnrzbsedlmebh.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRodG9rYmJibnJ6YnNlZGxtZWJoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODMxNDIwOTEsImV4cCI6MjA5ODcxODA5MX0.l-567ptGRkT48UTZv2y-ZSZZTH56DmBgIYrAwpUYJzs"

    data class PetStateEntry(val key: String, val value: String)

    fun postGesture(type: String, x: Int, y: Int) {
        val body = JSONObject().apply {
            put("gesture_type", type)
            put("x", x)
            put("y", y)
        }
        post("pet_gesture_log", body)
    }

    fun postAppUsage(packageName: String) {
        val body = JSONObject().apply {
            put("package_name", packageName)
        }
        post("pet_app_usage", body)
    }

    fun getLatestState(): PetStateEntry? {
        return try {
            val url = URL("$SUPABASE_URL/rest/v1/pet_state?order=updated_at.desc&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(response)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    PetStateEntry(
                        key = obj.getString("state_key"),
                        value = obj.optString("state_value", "")
                    )
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun post(table: String, body: JSONObject) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
