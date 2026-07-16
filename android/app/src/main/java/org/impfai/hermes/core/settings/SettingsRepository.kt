package org.impfai.hermes.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.impfai.hermes.BuildConfig

/**
 * 客戶端設置（DataStore）。
 *
 * 安全邊界：這裡只保存 **Hermes 服務端訪問令牌**（角色綁定 API Key），
 * 絕不保存任何模型供應商密鑰（OpenAI/Anthropic 等只存在於服務端）。
 * 角色選擇只是「請求」，真正的角色上限由服務端身份裁定（policy.py）。
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiToken: String = "",
    val requestedRole: String = "student",
    val simplifiedDisplay: Boolean = true,
    // VIP 默認純端側：全量數據隨包，未顯式配置服務端前不發任何遠端請求
    val offlineOnly: Boolean = BuildConfig.VIP,
    val favorites: Set<String> = emptySet(),
    // —— VIP 直連大模型（BYOK；Key 僅存本機，見 DirectLlm 註釋）——
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
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8765/"
        val ROLES = listOf("patient", "student", "researcher", "doctor")
        val ROLE_LABELS = mapOf(
            "patient" to "患者", "student" to "学生",
            "researcher" to "研究者", "doctor" to "医师",
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("hermes_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val ROLE = stringPreferencesKey("requested_role")
        val SIMPLIFIED = booleanPreferencesKey("simplified_display")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")
        val FAVORITES = stringSetPreferencesKey("favorite_clauses")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LLM_MAX_TOKENS = androidx.datastore.preferences.core
            .intPreferencesKey("llm_max_tokens")
        val READER_FONT = androidx.datastore.preferences.core
            .intPreferencesKey("reader_font_size")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val LIB_FAVORITES = stringSetPreferencesKey("library_favorites")
        val LIB_RECENTS = stringPreferencesKey("library_recents")   // "id|id|…"
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            baseUrl = p[Keys.BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
            apiToken = p[Keys.API_TOKEN] ?: "",
            requestedRole = p[Keys.ROLE] ?: "student",
            simplifiedDisplay = p[Keys.SIMPLIFIED] ?: true,
            offlineOnly = p[Keys.OFFLINE_ONLY] ?: BuildConfig.VIP,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            llmProvider = p[Keys.LLM_PROVIDER] ?: "openai",
            llmApiKey = p[Keys.LLM_API_KEY] ?: "",
            llmBaseUrl = p[Keys.LLM_BASE_URL] ?: "",
            llmModel = p[Keys.LLM_MODEL] ?: "",
            llmMaxTokens = p[Keys.LLM_MAX_TOKENS] ?: 8192,
            readerFontSize = p[Keys.READER_FONT] ?: 18,
            readerTheme = p[Keys.READER_THEME] ?: "paper",
            libraryFavorites = p[Keys.LIB_FAVORITES] ?: emptySet(),
            libraryRecents = (p[Keys.LIB_RECENTS] ?: "")
                .split('|').filter { it.isNotBlank() },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setServer(baseUrl: String, token: String, role: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim()
            p[Keys.API_TOKEN] = token.trim()
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
        context.dataStore.edit { p ->
            p[Keys.LLM_PROVIDER] = provider
            p[Keys.LLM_API_KEY] = apiKey.trim()
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

    suspend fun toggleFavorite(clauseId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.FAVORITES] ?: emptySet()
            p[Keys.FAVORITES] = if (clauseId in cur) cur - clauseId else cur + clauseId
        }
    }
}
