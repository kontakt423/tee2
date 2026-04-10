package com.kraeutertee.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ── Models ─────────────────────────────────────────────────────────────────────

data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)
data class GeminiContent(val role: String, val parts: List<GeminiPart>)
data class GeminiPart(val text: String)
data class GeminiGenerationConfig(
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int = 1024,
    val temperature: Float = 0.7f
)
data class GeminiResponse(val candidates: List<GeminiCandidate>?, val error: GeminiError?)
data class GeminiCandidate(
    val content: GeminiContent?,
    @SerializedName("finishReason") val finishReason: String?
)
data class GeminiError(val code: Int, val message: String, val status: String)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
}

// ── Service ────────────────────────────────────────────────────────────────────

class GeminiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ✅ gemini-2.0-flash-lite auf v1beta:
    //   - Bestätigt verfügbar mit AIzaSy-Schlüsseln aus Google AI Studio
    //   - 30 Anfragen/Min (Free Tier) – 3x mehr als gemini-2.0-flash
    //   - Schnell, mehrsprachig, ideal für diese App
    private val MODEL    = "gemini-2.0-flash-lite"
    private val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    // System-Prompt wird als Präambel in die erste Nutzernachricht eingebettet,
    // da v1beta kein separates system_instruction-Feld benötigt.
    private suspend fun generate(
        systemPrompt: String?,
        contents: List<GeminiContent>,
        maxTokens: Int = 1024,
        maxRetries: Int = 3
    ): ApiResult<String> = withContext(Dispatchers.IO) {

        if (apiKey.isBlank()) return@withContext ApiResult.Error(
            "Kein API-Schlüssel konfiguriert. Bitte in den Einstellungen (⚙️) eingeben."
        )

        // System-Prompt in erste User-Nachricht einbetten
        val finalContents = if (!systemPrompt.isNullOrBlank()) {
            val first = contents.firstOrNull()
            if (first != null && first.role == "user") {
                val combined = "SYSTEM: $systemPrompt\n\nANFRAGE: ${first.parts.first().text}"
                listOf(GeminiContent("user", listOf(GeminiPart(combined)))) + contents.drop(1)
            } else contents
        } else contents

        val body = gson.toJson(
            GeminiRequest(
                contents = finalContents,
                generationConfig = GeminiGenerationConfig(maxOutputTokens = maxTokens)
            )
        ).toRequestBody(JSON)

        var hit429 = false

        // Delays: 1. Versuch sofort, 2. nach 15s, 3. nach 30s
        val retryDelays = listOf(0L, 15_000L, 30_000L)

        repeat(maxRetries) { attempt ->
            if (attempt > 0) delay(retryDelays.getOrElse(attempt) { 30_000L })

            try {
                val request = Request.Builder()
                    .url("$ENDPOINT?key=$apiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val text     = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        val parsed = gson.fromJson(text, GeminiResponse::class.java)
                        val reply  = parsed.candidates
                            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        return@withContext if (reply != null) ApiResult.Success(reply)
                        else ApiResult.Error("Leere Antwort vom Server.")
                    }
                    response.code == 429 -> {
                        hit429 = true
                        // Continue to next retry with longer delay
                    }
                    else -> {
                        val msg = runCatching {
                            gson.fromJson(text, GeminiResponse::class.java).error?.message
                        }.getOrNull() ?: "Fehler ${response.code}"
                        return@withContext ApiResult.Error(msg)
                    }
                }
            } catch (e: IOException) {
                return@withContext ApiResult.Error("Netzwerkfehler: ${e.localizedMessage}")
            }
        }

        // All retries exhausted
        return@withContext if (hit429) {
            ApiResult.Error(
                "Das API-Limit ist vorübergehend erschöpft.\n\n" +
                "Bitte 1–2 Minuten warten und dann erneut versuchen.\n\n" +
                "Tipp: Das kostenlose Gemini-Kontingent erlaubt ca. 10 Anfragen pro Minute."
            )
        } else {
            ApiResult.Error("Keine Antwort nach $maxRetries Versuchen.")
        }
    }

    private suspend fun chat(
        systemPrompt: String, userMessage: String, maxTokens: Int = 1024
    ): ApiResult<String> = generate(
        systemPrompt, listOf(GeminiContent("user", listOf(GeminiPart(userMessage)))), maxTokens
    )

    suspend fun explainHerb(herbName: String, latinName: String): ApiResult<String> = chat(
        systemPrompt = """Du bist ein erfahrener Kräuterkundler und Naturheilkundler.
Erkläre Kräuter auf Deutsch, klar und praxisnah.
Struktur: 1) Wirkung & Inhaltsstoffe  2) Teezubereitung  3) Besonderheiten & Tipps.
Max. 300 Wörter, keine Markdown-Überschriften.""",
        userMessage  = "Erkläre '$herbName' ($latinName) für die Teezubereitung.",
        maxTokens    = 512
    )

    suspend fun suggestTeaBlends(availableHerbs: List<String>): ApiResult<String> = chat(
        systemPrompt = """Du bist ein Teemeister der Kräuterküche.
Antworte auf Deutsch. Schlage konkrete Teemischungen vor mit: Verhältnis, Wirkung, Zubereitungstipp.
Max. 400 Wörter.""",
        userMessage  = """Meine Kräuter: ${availableHerbs.joinToString(", ")}.
Empfiehl 3–5 Mischungen mit Name, Zutaten, Verhältnis, Wirkung und Tipp.""",
        maxTokens    = 700
    )

    suspend fun generateHerbInfo(herbName: String): ApiResult<String> = chat(
        systemPrompt = """Du bist ein Kräuterexperte. Antworte NUR mit einem gültigen JSON-Objekt.
Kein Text, keine Backticks davor oder danach.
Format: {"latinName":"...","emoji":"🌿","shortDescription":"1-2 Sätze DE","harvestPart":"Blätter","harvestMonths":[6,7,8],"harvestTips":"DE","dryingDays":7,"dryingTempMax":40,"dryingMethod":"DE","storageMonths":12,"teaInfo":"DE","brewingTempC":90,"brewingMinutes":7,"effects":["Wirkung1"],"gardenTips":"DE","warningsDE":"","category":"Beruhigung"}""",
        userMessage  = "Erstelle ein vollständiges Kräuterprofil für: $herbName",
        maxTokens    = 800
    )

    suspend fun getChatResponse(
        messages: List<Pair<String, String>>, herbContext: String = ""
    ): ApiResult<String> {
        if (apiKey.isBlank()) return ApiResult.Error(
            "Kein API-Schlüssel konfiguriert. Bitte in den Einstellungen (⚙️) eingeben."
        )
        val system = buildString {
            append("Du bist ein freundlicher Kräuterexperte und Teemeister. Antworte immer auf Deutsch, praxisnah und klar. Bei medizinischen Fragen weise auf einen Arzt hin.")
            if (herbContext.isNotBlank()) append(" Kräuter des Nutzers: $herbContext")
        }
        val contents = messages
            .dropWhile { it.first == "assistant" }
            .map { (role, content) ->
                GeminiContent(
                    role  = if (role == "assistant") "model" else "user",
                    parts = listOf(GeminiPart(content))
                )
            }
        return generate(system, contents, 1024)
    }
}
