package org.impfai.hermes.core.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * VIP 直連大模型（BYOK：用戶自帶 Key）。
 *
 * 安全邊界（與 docs/ANDROID.md 一致）：
 * - Key 僅存本機 DataStore（allowBackup=false，不隨雲備份外流）；
 * - 只發送至用戶明確配置的模型服務商端點（HTTPS）；
 * - 絕不發送到 Hermes 服務端或任何第三方；
 * - 回答須經本地 CitationGuard 核驗後帶徽章展示（弱於服務端全鏈路
 *   閘門——界面如實標注「本地核驗」）。
 */
object DirectLlm {

    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"   // OpenAI 及一切兼容端點（中轉/MiniMax 等）

    val PROVIDERS = listOf(PROVIDER_OPENAI, PROVIDER_ANTHROPIC)
    val PROVIDER_LABELS = mapOf(
        PROVIDER_ANTHROPIC to "Anthropic",
        PROVIDER_OPENAI to "OpenAI 兼容（Poe）",
    )

    // OpenAI 兼容端點默認指向 Poe（用戶要求）：一個 Poe Key 可調用
    // Claude/GPT/Gemini 等全系模型；填其他兼容端點（OpenAI 官方/中轉）
    // 只需改 Base URL。
    fun defaultBaseUrl(provider: String): String = when (provider) {
        PROVIDER_ANTHROPIC -> "https://api.anthropic.com"
        else -> "https://api.poe.com"
    }

    fun defaultModel(provider: String): String = when (provider) {
        PROVIDER_ANTHROPIC -> "claude-sonnet-5"
        else -> "Claude-Sonnet-4.6"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 單輪補全。失敗返回 Result.failure（含可讀中文信息）。 */
    suspend fun complete(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 2048,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 API Key"))
        }
        val base = (baseUrl.ifBlank { defaultBaseUrl(provider) }).trimEnd('/')
        val mdl = model.ifBlank { defaultModel(provider) }
        try {
            when (provider) {
                PROVIDER_ANTHROPIC -> anthropic(base, apiKey, mdl, system, user, maxTokens)
                else -> openAi(base, apiKey, mdl, system, user, maxTokens)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(IOException("网络请求失败：${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun anthropic(
        base: String, key: String, model: String,
        system: String, user: String, maxTokens: Int,
    ): Result<String> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
        }
        val req = Request.Builder()
            .url("$base/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(media))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return Result.failure(httpError(resp.code, text))
            val root = json.parseToJsonElement(text).jsonObject
            val answer = root["content"]?.jsonArray
                ?.mapNotNull { blk ->
                    val o = blk.jsonObject
                    if (o["type"]?.jsonPrimitive?.content == "text")
                        o["text"]?.jsonPrimitive?.content else null
                }
                ?.joinToString("\n")
                .orEmpty()
            return if (answer.isBlank())
                Result.failure(IllegalStateException("模型返回空内容"))
            else Result.success(answer)
        }
    }

    private fun openAi(
        base: String, key: String, model: String,
        system: String, user: String, maxTokens: Int,
    ): Result<String> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(media))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return Result.failure(httpError(resp.code, text))
            val root = json.parseToJsonElement(text).jsonObject
            val answer = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                .orEmpty()
            return if (answer.isBlank())
                Result.failure(IllegalStateException("模型返回空内容"))
            else Result.success(answer)
        }
    }

    private fun httpError(code: Int, body: String): Exception {
        val hint = when (code) {
            400 -> "请求被拒（HTTP 400，常见原因：模型名不存在或参数不符）"
            401, 403 -> "API Key 无效或无权限（HTTP $code）"
            404 -> "端点或模型名不存在（HTTP 404，检查 Base URL 与模型名）"
            429 -> "限流或额度不足（HTTP 429）"
            else -> "HTTP $code"
        }
        val detail = body.take(300).replace('\n', ' ')
        return IOException("$hint：$detail")
    }

    /** 設置頁「測試模型連接」：最小補全驗證 Key/端點/模型三件套。
     *  失敗信息盡量可行動（网络不可达≈需要中转端点；4xx≈Key/模型名）。 */
    suspend fun testConnection(
        provider: String, apiKey: String, baseUrl: String, model: String,
    ): Result<String> {
        val r = complete(provider, apiKey, baseUrl, model,
            system = "You are a connectivity probe. Reply with exactly: 连接正常",
            user = "ping", maxTokens = 16)
        return r.map { "模型应答：${it.take(40)}" }.recoverCatching { e ->
            val msg = e.message ?: "未知错误"
            val extra = if (msg.contains("网络请求失败") ||
                msg.contains("timeout", true) ||
                msg.contains("failed to connect", true)
            ) "\n提示：手机网络无法直连该端点时（如大陆网络访问 api.poe.com），" +
                "请在 Base URL 填可达的 OpenAI 兼容中转端点" else ""
            throw IOException(msg + extra)
        }
    }
}
