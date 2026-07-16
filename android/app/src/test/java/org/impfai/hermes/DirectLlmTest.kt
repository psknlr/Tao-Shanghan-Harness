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

    @Test
    fun endpoint_url_never_doubles_v1() {
        // v1.4 修復：MiniMax 文檔 base_url 自帶 /v1，此前拼出 /v1/v1 必 404
        assertEquals("https://api.minimaxi.com/v1/chat/completions",
            DirectLlm.endpointUrl("https://api.minimaxi.com/v1",
                "chat/completions"))
        assertEquals("https://api.poe.com/v1/chat/completions",
            DirectLlm.endpointUrl("https://api.poe.com", "chat/completions"))
        assertEquals("https://x.cn/v1/chat/completions",
            DirectLlm.endpointUrl("https://x.cn/v1/", "chat/completions"))
        // 用戶把完整路徑貼進來也容忍
        assertEquals("https://x.cn/v1/chat/completions",
            DirectLlm.endpointUrl("https://x.cn/v1/chat/completions",
                "chat/completions"))
        assertEquals("https://api.anthropic.com/v1/messages",
            DirectLlm.endpointUrl("https://api.anthropic.com", "messages"))
    }

    @Test
    fun minimax_v1_base_roundtrip() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
        server.start()
        // 模擬 MiniMax 風格：base 自帶 /v1
        val base = server.url("/v1").toString()
        val r = DirectLlm.complete(DirectLlm.PROVIDER_OPENAI, "k", base,
            "MiniMax-M3", "s", "u")
        assertTrue(r.isSuccess)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
        server.shutdown()
    }

    @Test
    fun openai_stream_sse_parsing() = runBlocking {
        val server = MockWebServer()
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"太陽\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"中風\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setBody(sse))
        server.start()
        val deltas = StringBuilder()
        val r = DirectLlm.completeStream(
            DirectLlm.PROVIDER_OPENAI, "k",
            server.url("/").toString().trimEnd('/'), "m", "s", "u",
        ) { deltas.append(it) }
        assertTrue(r.isSuccess)
        assertEquals("太陽中風", r.getOrThrow())
        assertEquals("太陽中風", deltas.toString())
        server.shutdown()
    }

    @Test
    fun anthropic_stream_sse_parsing() = runBlocking {
        val server = MockWebServer()
        val sse = buildString {
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\"," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"往來\"}}\n\n")
            append("data: {\"type\":\"content_block_delta\"," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"寒熱\"}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setBody(sse))
        server.start()
        val r = DirectLlm.completeStream(
            DirectLlm.PROVIDER_ANTHROPIC, "k",
            server.url("/").toString().trimEnd('/'), "m", "s", "u") { }
        assertTrue(r.isSuccess)
        assertEquals("往來寒熱", r.getOrThrow())
        server.shutdown()
    }

    @Test
    fun presets_cover_minimax_and_poe() {
        val labels = DirectLlm.PRESETS.map { it.label }
        assertTrue(labels.any { it.contains("Poe") })
        assertTrue(labels.any { it.contains("MiniMax") })
        val mm = DirectLlm.PRESETS.first { it.label == "MiniMax 国内" }
        assertEquals("https://api.minimaxi.com/v1", mm.baseUrl)
        assertEquals("MiniMax-M3", mm.model)
    }
}
