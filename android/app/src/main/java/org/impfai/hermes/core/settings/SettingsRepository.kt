package org.impfai.hermes.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.impfai.hermes.BuildConfig

/**
 * 客戶端設置。
 *
 * 存儲分層（外部評審建議五 + VIP BYOK 合流）：
 * - **秘密**（Hermes 訪問令牌 + VIP 直連大模型 API Key）→
 *   [SecureTokenStore]（Android Keystore 加密），本類只做轉發；歷史版本
 *   存在 DataStore 里的明文值首次讀取時一次性遷移並抹除。BYOK Key 是
 *   真正的供應商密鑰，密級高於角色令牌——同樣只存本機、只發往用戶
 *   配置的模型端點，絕不發送到 Hermes 服務端或第三方。
 * - **非機密設置**（地址/角色/顯示/閱讀器偏好/收藏）→ DataStore。
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiToken: String = "",
    val requestedRole: String = "student",
    val simplifiedDisplay: Boolean = true,
    // VIP 默認純端側：全量數據隨包，未顯式配置服務端前不發任何遠端請求
    val offlineOnly: Boolean = BuildConfig.VIP,
    val favorites: Set<String> = emptySet(),
    // —— VIP 直連大模型（BYOK；Key 經 Keystore 加密僅存本機）——
    val llmProvider: String = "openai",     // 默認 OpenAI 兼容（Poe 端點）
    val llmApiKey: String = "",
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    // 最大輸出 tokens（v1.6：MiniMax-M3 等長答截斷修復；上限交給服務商）
    val llmMaxTokens: Int = 8192,
    // —— 古籍閱讀器（v1.4 Kindle 式體驗）——
    val readerFontSize: Int = 18,           // sp，14..26
    val readerTheme: String = "paper",      // paper | white | green | night
    val libraryFavorites: Set<String> = emptySet(),
    val libraryRecents: List<String> = emptyList(),
    /** 智能體會話模式（空 = 跟隨「我的」頁角色）。 */
    val agentMode: String = "",
    /** 智能體推理深度（max_steps 請求值，服務端裁剪至 1..12）。 */
    val agentDepth: Int = DEFAULT_AGENT_DEPTH,
    /** 直連通道深度思考：檢索規劃→多輪取證→Skill 指引→評估補檢。 */
    val deepThink: Boolean = false,
    /** false = 本機 Keystore 不可用，秘密降級明文存儲（設置頁警示）。 */
    val secureTokenStorage: Boolean = true,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8765/"
        const val DEFAULT_AGENT_DEPTH = 5
        val ROLES = listOf("patient", "student", "researcher", "doctor")
        val ROLE_LABELS = mapOf(
            "patient" to "患者", "student" to "学生",
            "researcher" to "研究者", "doctor" to "医师",
        )

        /** 智能體模式 → 角色請求映射（誠實映射：服務端本就按角色裁剪
         *  工具面與安全策略，客戶端不虛構後端不存在的檔位）。 */
        val AGENT_MODES = listOf("", "student", "doctor", "researcher")
        val AGENT_MODE_LABELS = mapOf(
            "" to "跟随角色", "student" to "学习模式",
            "doctor" to "临床辅助", "researcher" to "科研模式",
        )
        val AGENT_DEPTHS = listOf(3, 5, 8)
        val AGENT_DEPTH_LABELS = mapOf(3 to "快速", 5 to "标准", 8 to "深研")
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("hermes_settings")

class SettingsRepository(
    private val context: Context,
    private val secureStore: SecureTokenStore = SecureTokenStore(context),
) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")

        /** 僅遺留遷移用：新版本秘密不再寫入 DataStore。 */
        val LEGACY_API_TOKEN = stringPreferencesKey("api_token")
        val LEGACY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val ROLE = stringPreferencesKey("requested_role")
        val SIMPLIFIED = booleanPreferencesKey("simplified_display")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")
        val FAVORITES = stringSetPreferencesKey("favorite_clauses")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        val READER_FONT = intPreferencesKey("reader_font_size")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val LIB_FAVORITES = stringSetPreferencesKey("library_favorites")
        val LIB_RECENTS = stringPreferencesKey("library_recents")   // "id|id|…"
        val AGENT_MODE = stringPreferencesKey("agent_mode")
        val AGENT_DEPTH = intPreferencesKey("agent_depth")
        val DEEP_THINK = booleanPreferencesKey("deep_think")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        migrateLegacySecrets(p)
        AppSettings(
            baseUrl = p[Keys.BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
            apiToken = secureStore.token(),
            requestedRole = p[Keys.ROLE] ?: "student",
            simplifiedDisplay = p[Keys.SIMPLIFIED] ?: true,
            offlineOnly = p[Keys.OFFLINE_ONLY] ?: BuildConfig.VIP,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            llmProvider = p[Keys.LLM_PROVIDER] ?: "openai",
            llmApiKey = secureStore.llmApiKey(),
            llmBaseUrl = p[Keys.LLM_BASE_URL] ?: "",
            llmModel = p[Keys.LLM_MODEL] ?: "",
            llmMaxTokens = p[Keys.LLM_MAX_TOKENS] ?: 8192,
            readerFontSize = p[Keys.READER_FONT] ?: 18,
            readerTheme = p[Keys.READER_THEME] ?: "paper",
            libraryFavorites = p[Keys.LIB_FAVORITES] ?: emptySet(),
            libraryRecents = (p[Keys.LIB_RECENTS] ?: "")
                .split('|').filter { it.isNotBlank() },
            agentMode = (p[Keys.AGENT_MODE] ?: "")
                .takeIf { it in AppSettings.AGENT_MODES } ?: "",
            agentDepth = (p[Keys.AGENT_DEPTH] ?: AppSettings.DEFAULT_AGENT_DEPTH)
                .coerceIn(1, 12),
            deepThink = p[Keys.DEEP_THINK] ?: false,
            secureTokenStorage = !secureStore.insecureFallback,
        )
    }

    /** 舊版明文秘密（角色令牌 + BYOK Key）→ Keystore 加密存儲並抹除。 */
    private suspend fun migrateLegacySecrets(p: Preferences) {
        val legacyToken = p[Keys.LEGACY_API_TOKEN]
        val legacyLlmKey = p[Keys.LEGACY_LLM_API_KEY]
        if (legacyToken == null && legacyLlmKey == null) return
        if (!legacyToken.isNullOrBlank() && secureStore.token().isBlank()) {
            secureStore.setToken(legacyToken)
        }
        if (!legacyLlmKey.isNullOrBlank() && secureStore.llmApiKey().isBlank()) {
            secureStore.setLlmApiKey(legacyLlmKey)
        }
        context.dataStore.edit {
            it.remove(Keys.LEGACY_API_TOKEN)
            it.remove(Keys.LEGACY_LLM_API_KEY)
        }
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setServer(baseUrl: String, token: String, role: String) {
        secureStore.setToken(token)
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim()
            p.remove(Keys.LEGACY_API_TOKEN)
            if (role in AppSettings.ROLES) p[Keys.ROLE] = role
        }
    }

    suspend fun setSimplifiedDisplay(on: Boolean) {
        context.dataStore.edit { it[Keys.SIMPLIFIED] = on }
    }

    suspend fun setOfflineOnly(on: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_ONLY] = on }
    }

    suspend fun setLlm(provider: String, apiKey: String, baseUrl: String,
                       model: String, maxTokens: Int = 8192) {
        secureStore.setLlmApiKey(apiKey)
        context.dataStore.edit { p ->
            p[Keys.LLM_PROVIDER] = provider
            p.remove(Keys.LEGACY_LLM_API_KEY)
            p[Keys.LLM_BASE_URL] = baseUrl.trim()
            p[Keys.LLM_MODEL] = model.trim()
            p[Keys.LLM_MAX_TOKENS] = maxTokens.coerceIn(1024, 65536)
        }
    }

    suspend fun setReaderPrefs(fontSize: Int? = null, theme: String? = null) {
        context.dataStore.edit { p ->
            fontSize?.let { p[Keys.READER_FONT] = it.coerceIn(14, 26) }
            theme?.let { p[Keys.READER_THEME] = it }
        }
    }

    suspend fun toggleLibraryFavorite(bookId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.LIB_FAVORITES] ?: emptySet()
            p[Keys.LIB_FAVORITES] =
                if (bookId in cur) cur - bookId else cur + bookId
        }
    }

    suspend fun pushLibraryRecent(bookId: String) {
        context.dataStore.edit { p ->
            val cur = (p[Keys.LIB_RECENTS] ?: "")
                .split('|').filter { it.isNotBlank() && it != bookId }
            p[Keys.LIB_RECENTS] = (listOf(bookId) + cur).take(12)
                .joinToString("|")
        }
    }

    suspend fun setAgentMode(mode: String) {
        if (mode !in AppSettings.AGENT_MODES) return
        context.dataStore.edit { it[Keys.AGENT_MODE] = mode }
    }

    suspend fun setAgentDepth(depth: Int) {
        context.dataStore.edit { it[Keys.AGENT_DEPTH] = depth.coerceIn(1, 12) }
    }

    suspend fun setDeepThink(on: Boolean) {
        context.dataStore.edit { it[Keys.DEEP_THINK] = on }
    }

    suspend fun toggleFavorite(clauseId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.FAVORITES] ?: emptySet()
            p[Keys.FAVORITES] = if (clauseId in cur) cur - clauseId else cur + clauseId
        }
    }
}
