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

/**
 * 客戶端設置。
 *
 * 存儲分層（外部評審建議五落地）：
 * - **令牌** → [SecureTokenStore]（Android Keystore 加密），本類只做轉發；
 *   歷史版本存在 DataStore 里的明文令牌在首次讀取時一次性遷移並抹除。
 * - **非機密設置**（地址/角色/顯示偏好/收藏）→ DataStore(Preferences)。
 *
 * 安全邊界不變：這裡只保存 Hermes 服務端訪問令牌（角色綁定 API Key），
 * 絕不保存任何模型供應商密鑰；角色選擇只是「請求」，上限由服務端裁定。
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiToken: String = "",
    val requestedRole: String = "student",
    val simplifiedDisplay: Boolean = true,
    val offlineOnly: Boolean = false,
    val favorites: Set<String> = emptySet(),
    /** 智能體會話模式（空 = 跟隨「我的」頁角色）。 */
    val agentMode: String = "",
    /** 智能體推理深度（max_steps 請求值，服務端裁剪至 1..12）。 */
    val agentDepth: Int = DEFAULT_AGENT_DEPTH,
    /** false = 本機 Keystore 不可用，令牌降級明文存儲（設置頁警示）。 */
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
         *  工具面與安全策略，客戶端不虛構「深度思考」等後端不存在的檔位）。 */
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

        /** 僅遺留遷移用：新版本令牌不再寫入 DataStore。 */
        val LEGACY_API_TOKEN = stringPreferencesKey("api_token")
        val ROLE = stringPreferencesKey("requested_role")
        val SIMPLIFIED = booleanPreferencesKey("simplified_display")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")
        val FAVORITES = stringSetPreferencesKey("favorite_clauses")
        val AGENT_MODE = stringPreferencesKey("agent_mode")
        val AGENT_DEPTH = intPreferencesKey("agent_depth")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        migrateLegacyToken(p)
        AppSettings(
            baseUrl = p[Keys.BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
            apiToken = secureStore.token(),
            requestedRole = p[Keys.ROLE] ?: "student",
            simplifiedDisplay = p[Keys.SIMPLIFIED] ?: true,
            offlineOnly = p[Keys.OFFLINE_ONLY] ?: false,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            agentMode = (p[Keys.AGENT_MODE] ?: "")
                .takeIf { it in AppSettings.AGENT_MODES } ?: "",
            agentDepth = (p[Keys.AGENT_DEPTH] ?: AppSettings.DEFAULT_AGENT_DEPTH)
                .coerceIn(1, 12),
            secureTokenStorage = !secureStore.insecureFallback,
        )
    }

    /** 舊版明文令牌 → Keystore 加密存儲，隨後從 DataStore 抹除。 */
    private suspend fun migrateLegacyToken(p: Preferences) {
        val legacy = p[Keys.LEGACY_API_TOKEN] ?: return
        if (legacy.isNotBlank() && secureStore.token().isBlank()) {
            secureStore.setToken(legacy)
        }
        context.dataStore.edit { it.remove(Keys.LEGACY_API_TOKEN) }
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

    suspend fun setAgentMode(mode: String) {
        if (mode !in AppSettings.AGENT_MODES) return
        context.dataStore.edit { it[Keys.AGENT_MODE] = mode }
    }

    suspend fun setAgentDepth(depth: Int) {
        context.dataStore.edit { it[Keys.AGENT_DEPTH] = depth.coerceIn(1, 12) }
    }

    suspend fun toggleFavorite(clauseId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.FAVORITES] ?: emptySet()
            p[Keys.FAVORITES] = if (clauseId in cur) cur - clauseId else cur + clauseId
        }
    }
}
