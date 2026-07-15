package org.impfai.hermes.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.impfai.hermes.core.model.ApiError
import org.impfai.hermes.core.model.Envelope
import org.impfai.hermes.core.model.EnvelopeMeta
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** 統一調用結果：成功攜帶信封 meta（服務端裁定的角色回顯）。 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val meta: EnvelopeMeta?) : ApiResult<T>

    /** 服務端返回的合同錯誤（固定錯誤碼，客戶端按 code 分支）。 */
    data class Failure(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val httpStatus: Int,
    ) : ApiResult<Nothing>

    /** 網絡不可達/超時等傳輸層失敗——離線回退的觸發條件。 */
    data class Offline(val message: String) : ApiResult<Nothing>
}

object ApiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

/** 按 (baseUrl, token) 構建並緩存 Retrofit 客戶端。 */
class ApiClientFactory {
    @Volatile private var cachedKey: Pair<String, String>? = null
    @Volatile private var cachedApi: HermesApi? = null

    fun get(baseUrl: String, token: String): HermesApi {
        val normalized = normalizeBaseUrl(baseUrl)
        val key = normalized to token
        cachedApi?.let { if (cachedKey == key) return it }
        synchronized(this) {
            cachedApi?.let { if (cachedKey == key) return it }
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)   // agent/council 可能較慢
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply {
                    if (token.isNotBlank()) {
                        addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("Authorization", "Bearer $token")
                                    .build()
                            )
                        }
                    }
                }
                .build()
            val api = Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(
                    ApiJson.json.asConverterFactory("application/json".toMediaType())
                )
                .build()
                .create(HermesApi::class.java)
            cachedKey = key
            cachedApi = api
            return api
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) url = "http://10.0.2.2:8765/"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            if (!url.endsWith("/")) url += "/"
            return url
        }
    }
}

/**
 * 信封解包 + 錯誤映射。
 * - 2xx + data → Success
 * - 信封 error / 非 2xx → Failure(固定錯誤碼)
 * - IOException → Offline（觸發本地語料回退）
 */
suspend fun <T> safeCall(block: suspend () -> Response<Envelope<T>>): ApiResult<T> {
    val response = try {
        block()
    } catch (e: IOException) {
        return ApiResult.Offline(e.message ?: "網絡不可達")
    } catch (e: IllegalArgumentException) {
        return ApiResult.Offline("服務端地址無效：${e.message ?: ""}")
    } catch (e: Exception) {
        return ApiResult.Failure("CLIENT_ERROR", e.message ?: "客戶端解析失敗",
            retryable = false, httpStatus = -1)
    }
    val body = response.body()
    if (response.isSuccessful && body != null) {
        val err = body.error
        if (err != null) {
            return ApiResult.Failure(err.code, err.message, err.retryable, response.code())
        }
        val data = body.data
            ?: return ApiResult.Failure("INTERNAL_ERROR", "響應缺少 data",
                retryable = false, httpStatus = response.code())
        return ApiResult.Success(data, body.meta)
    }
    // 非 2xx：錯誤體也是 v1 信封
    val raw = try {
        response.errorBody()?.string()
    } catch (_: IOException) {
        null
    }
    val parsedError: ApiError? = raw?.let {
        try {
            ApiJson.json.decodeFromString<Envelope<JsonElement>>(it).error
        } catch (_: Exception) {
            null
        }
    }
    val err = parsedError ?: ApiError(
        code = "HTTP_${response.code()}",
        message = "HTTP ${response.code()}",
        retryable = response.code() in listOf(429, 503),
    )
    return ApiResult.Failure(err.code, err.message, err.retryable, response.code())
}
