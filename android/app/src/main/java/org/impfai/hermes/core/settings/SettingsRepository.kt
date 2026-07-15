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
    val offlineOnly: Boolean = false,
    val favorites: Set<String> = emptySet(),
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
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            baseUrl = p[Keys.BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
            apiToken = p[Keys.API_TOKEN] ?: "",
            requestedRole = p[Keys.ROLE] ?: "student",
            simplifiedDisplay = p[Keys.SIMPLIFIED] ?: true,
            offlineOnly = p[Keys.OFFLINE_ONLY] ?: false,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
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

    suspend fun toggleFavorite(clauseId: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.FAVORITES] ?: emptySet()
            p[Keys.FAVORITES] = if (clauseId in cur) cur - clauseId else cur + clauseId
        }
    }
}
