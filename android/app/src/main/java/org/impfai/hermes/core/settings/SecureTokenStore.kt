package org.impfai.hermes.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 訪問令牌安全存儲（外部評審建議五）。
 *
 * DataStore(Preferences) 是明文文件，root/惡意備份場景可讀；令牌改由
 * Android Keystore 主密鑰加密的 EncryptedSharedPreferences 保存：
 * 密鑰材料在 TEE/StrongBox 內，應用數據被拖走也解不開。
 *
 * 個別設備的 Keystore 實現損壞時（極少數廠商 ROM），退回明文
 * SharedPreferences 並置 [insecureFallback]=true，由設置頁向用戶明示
 * ——靜默降級等於假裝安全。
 *
 * 評審建議的「OAuth2 PKCE + 短期 access token」是正確的終態，但它
 * 依賴服務端具備 OIDC 簽發能力；當前後端有意保持 stdlib 零依賴
 * （HERMES_API_KEYS 角色綁定 Key），故本層先把「靜態令牌的存放」
 * 做對，簽發機制升級見 docs/ANDROID.md 路線圖。
 */
class SecureTokenStore(context: Context) {

    val insecureFallback: Boolean
    private val prefs: SharedPreferences

    init {
        var fallback = false
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "hermes_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            fallback = true
            context.getSharedPreferences("hermes_secure_plain", Context.MODE_PRIVATE)
        }
        insecureFallback = fallback
    }

    fun token(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /** VIP BYOK 直連大模型的供應商 API Key——密級最高的本機秘密。 */
    fun llmApiKey(): String = prefs.getString(KEY_LLM_API_KEY, "") ?: ""

    fun setLlmApiKey(key: String) {
        prefs.edit().putString(KEY_LLM_API_KEY, key.trim()).apply()
    }

    private companion object {
        const val KEY_TOKEN = "api_token"
        const val KEY_LLM_API_KEY = "llm_api_key"
    }
}
