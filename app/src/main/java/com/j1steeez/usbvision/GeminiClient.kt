package com.j1steeez.usbvision

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * POSTs a JPEG frame to Gemini generateContent and returns the first text part.
 */
class GeminiClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun analyze(jpegBase64: String): String {
        if (apiKey.isBlank()) {
            return "ERR: missing GEMINI_KEY in local.properties"
        }

        val bodyJson = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", PROMPT))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/jpeg")
                                        .put("data", jpegBase64)
                                )
                            )
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("maxOutputTokens", 64)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingBudget", 0)
                    )
            )

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return "ERR: HTTP ${response.code}"
            }
            return try {
                JSONObject(raw)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                "ERR: bad response"
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"

        const val PROMPT =
            "You are a fast assistant. The image contains one multiple-choice question.\n" +
                "Reply with the correct letter on line one, then one sentence of justification.\n" +
                "No preamble, no restating the question. If it is not multiple choice, answer in\n" +
                "under 25 words. If the image is unreadable, reply exactly UNCLEAR."
    }
}
