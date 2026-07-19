package com.example

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini Request / Response Models ---

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float? = null,
    val responseMimeType: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content?
)

// --- Pal Assistant Parsed Response ---

@JsonClass(generateAdapter = true)
data class PalCommand(
    val type: String, // "CALL", "SEND_SMS", "LIST_FILES", "NONE"
    val recipient: String? = null, // phone number or contact name
    val message: String? = null // SMS text
)

@JsonClass(generateAdapter = true)
data class PalResponse(
    val reply: String,
    val emotion: String, // "HAPPY", "CURIOUS", "CONCERNED", "THINKING", "SPEAKING"
    val command: PalCommand? = null
)

// --- Retrofit Interface ---

interface GeminiApi {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

// --- Gemini Service Repository ---

object GeminiService {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val api: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApi::class.java)
    }

    private val palResponseAdapter = moshi.adapter(PalResponse::class.java)

    /**
     * Interacts with Gemini to parse commands, handle Persian input, and return PalResponse.
     */
    suspend fun askPal(userPrompt: String, history: List<Content> = emptyList()): PalResponse {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return PalResponse(
                reply = "کلید API گمینی تنظیم نشده است. لطفاً آن را در بخش Secrets تنظیم کنید.",
                emotion = "CONCERNED",
                command = null
            )
        }

        val systemPrompt = """
            You are Pal, an extremely advanced, empathetic, and intuitive Persian voice assistant. 
            You must understand and respond in Persian (Farsi) very clearly and perfectly.
            You have full virtual control over the user's phone, including calls, messaging (SMS), and files.
            
            Based on the user's input, analyze the meaning, intent, and sentiment. You must respond strictly in JSON format matching the following Kotlin structure:
            {
              "reply": "Your response to speak and display to the user in fluent Persian",
              "emotion": "One of: HAPPY, CURIOUS, CONCERNED",
              "command": {
                "type": "CALL" or "SEND_SMS" or "LIST_FILES" or "NONE",
                "recipient": "Contact name or phone number if the user wants to call or text, otherwise null",
                "message": "The message body if sending SMS, otherwise null"
              }
            }
            
            Command selection guidelines:
            1. If the user asks to call someone (e.g., "تماس بگیر با علی" or "زنگ بزن به مامان" or "زنگ بزن به 0912..."), set type to "CALL" and recipient to the name or number.
            2. If the user asks to text or message someone (e.g., "پیام بده به رضا که میام" or "به علی اس ام اس بده فردا ساعت ۵ جلسه داریم"), set type to "SEND_SMS", recipient to the name or number, and message to the text of the message.
            3. If the user asks to see/list files or applications, set type to "LIST_FILES".
            4. Otherwise, set type to "NONE" and command to null.
            
            Emotion selection guidelines:
            - HAPPY: For friendly, playful, happy, or standard informative responses.
            - CURIOUS: If the user is asking questions, being inquisitive, or if you need clarification.
            - CONCERNED: If the user is sad, angry, worried, or talking about issues, or if an action fails.
            
            Always ensure the JSON is valid and return ONLY the JSON block. Do not include any Markdown wrapper like ```json or trailing text.
        """.trimIndent()

        // Combine history and current prompt
        val contentsList = history + listOf(Content(parts = listOf(Part(text = userPrompt))))

        val request = GeminiRequest(
            contents = contentsList,
            generationConfig = GenerationConfig(
                temperature = 0.7f,
                responseMimeType = "application/json"
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
        )

        return try {
            val response = api.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                // Parse response
                palResponseAdapter.fromJson(jsonText) ?: fallbackResponse(userPrompt)
            } else {
                fallbackResponse(userPrompt)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            PalResponse(
                reply = "متأسفم، در برقراری ارتباط مشکلی پیش آمد: ${e.localizedMessage}",
                emotion = "CONCERNED",
                command = null
            )
        }
    }

    private fun fallbackResponse(prompt: String): PalResponse {
        return PalResponse(
            reply = "متوجه شدم که گفتی: $prompt. به عنوان دستیار شما همیشه در خدمتم.",
            emotion = "HAPPY",
            command = null
        )
    }
}
