package org.impfai.hermes

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.impfai.hermes.core.llm.DirectLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直連大模型客戶端合同測試（MockWebServer 模擬 Poe/OpenAI 兼容端點與
 * Anthropic 端點）——請求路徑/頭/體與響應解析全鏈路驗證，
 * 排除「llm_error 來自客戶端實現」的可能性。
 */
class DirectLlmTest {

    @Test
    fun openai_compatible_roundtrip_poe_shape() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"id":"chatcmpl-1","choices":[{"index":0,"message":
               {"role":"assistant","content":"太陽中風，桂枝湯主之 [SHL_SONGBEN_0012]"},
               "finish_reason":"stop"}],"usage":{"total_tokens":42}}"""
                .replace("\n", "")))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val r = DirectLlm.complete(
            provider = DirectLlm.PROVIDER_OPENAI,
            apiKey = "sk-test", baseUrl = base,
            model = "Claude-Sonnet-4.6",
            system = "sys", user = "hi")
        assertTrue(r.isSuccess)
        assertTrue(r.getOrThrow().contains("桂枝湯"))
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"Claude-Sonnet-4.6\""))
        assertTrue(body.contains("\"role\":\"system\""))
        server.shutdown()
    }

    @Test
    fun anthropic_roundtrip() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"id":"msg_1","content":[{"type":"text","text":"answer-ok"}],
               "model":"claude-sonnet-5"}""".replace("\n", "")))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val r = DirectLlm.complete(
            provider = DirectLlm.PROVIDER_ANTHROPIC,
            apiKey = "ak-test", baseUrl = base, model = "claude-sonnet-5",
            system = "sys", user = "hi")
        assertTrue(r.isSuccess)
        assertEquals("answer-ok", r.getOrThrow())
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("ak-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        server.shutdown()
    }

    @Test
    fun http_error_is_actionable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"message":"Invalid API key"}}"""))
        server.start()
        val r = DirectLlm.complete(DirectLlm.PROVIDER_OPENAI, "bad",
            server.url("/").toString().trimEnd('/'), "m", "s", "u")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("401") || msg.contains("Key"))
        server.shutdown()
    }

    @Test
    fun poe_is_default_openai_endpoint() {
        assertEquals("https://api.poe.com",
            DirectLlm.defaultBaseUrl(DirectLlm.PROVIDER_OPENAI))
        assertEquals("Claude-Sonnet-4.6",
            DirectLlm.defaultModel(DirectLlm.PROVIDER_OPENAI))
    }
}
