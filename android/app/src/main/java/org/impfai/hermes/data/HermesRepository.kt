package org.impfai.hermes.data

import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.AgentRequest
import org.impfai.hermes.core.model.ClauseDetail
import org.impfai.hermes.core.model.EnvelopeMeta
import org.impfai.hermes.core.model.HealthData
import org.impfai.hermes.core.model.ManifestData
import org.impfai.hermes.core.model.MatchData
import org.impfai.hermes.core.model.MatchRequest
import org.impfai.hermes.core.model.ResultOrigin
import org.impfai.hermes.core.model.SearchData
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.core.model.SearchRequest
import org.impfai.hermes.core.model.WhoAmI
import org.impfai.hermes.core.network.ApiClientFactory
import org.impfai.hermes.core.network.ApiResult
import org.impfai.hermes.core.network.HermesApi
import org.impfai.hermes.core.network.safeCall
import org.impfai.hermes.core.settings.SettingsRepository
import org.impfai.hermes.engine.LocalClauseStore

/** UI 層統一結果：數據 + 來源標記 + 可選提示。 */
sealed interface RepoResult<out T> {
    data class Data<T>(
        val value: T,
        val origin: ResultOrigin,
        val meta: EnvelopeMeta? = null,
        val notice: String? = null,
    ) : RepoResult<T>

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : RepoResult<Nothing>
}

data class ServerStatus(
    val reachable: Boolean,
    val ready: Boolean = false,
    val backend: String = "",
    val roleCeiling: String = "",
    val effectiveRole: String? = null,
    val principal: String = "",
    val contentVersion: String = "",
    val detail: String = "",
)

/**
 * 離線優先數據入口（對應原方案第七節 DefaultClauseRepository）：
 * - 知識類（search/clause）：在線走服務端（完整證據面），網絡不可達時
 *   回退本地語料並顯式標記 LOCAL_CORPUS；
 * - 推理類（match/agent）：只走服務端——臨床輔助計算不在客戶端復制，
 *   安全裁定（角色/患者投影/引用閘門）必須留在服務端。
 * - 策略類錯誤（403 POLICY_DENIED 等）**不**回退本地：降級繞過授權
 *   等於客戶端自行提權。
 */
class HermesRepository(
    private val settingsRepo: SettingsRepository,
    private val localStore: LocalClauseStore,
    private val apiFactory: ApiClientFactory,
) {

    private suspend fun api(): Pair<HermesApi, String?> {
        val s = settingsRepo.current()
        val role = s.requestedRole.takeIf { it.isNotBlank() }
        return apiFactory.get(s.baseUrl, s.apiToken) to role
    }

    /** Retrofit 構建（非法 baseUrl 拋 IAE）必須在結果類型內失敗，
     *  不能讓崩潰逃逸到 ViewModel（審查發現 #4）。 */
    private suspend fun <T> withApi(
        block: suspend (HermesApi, String?) -> RepoResult<T>,
    ): RepoResult<T> = try {
        val (api, role) = api()
        block(api, role)
    } catch (e: IllegalArgumentException) {
        RepoResult.Error("INVALID_BASE_URL",
            "服务端地址无效，请在“我的”页检查：${e.message ?: ""}")
    }

    private suspend fun offlineOnly(): Boolean = settingsRepo.current().offlineOnly

    suspend fun search(query: String, sixChannel: String? = null, topK: Int = 12): RepoResult<SearchData> {
        if (!offlineOnly()) {
            val remote = withApi<SearchData> { api, role ->
                when (val r = safeCall {
                    api.search(SearchRequest(query = query, topK = topK,
                        sixChannel = sixChannel, role = role))
                }) {
                    is ApiResult.Success ->
                        r.data.errorMessage?.let {
                            RepoResult.Error("SERVER_MESSAGE", it)
                        } ?: RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                    is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                    is ApiResult.Offline -> RepoResult.Error("OFFLINE", r.message)
                }
            }
            if (!(remote is RepoResult.Error && remote.code == "OFFLINE")) return remote
        }
        val hits = localStore.search(query, topK, sixChannel)
        return RepoResult.Data(
            SearchData(query = query, hits = hits, count = hits.size),
            ResultOrigin.LOCAL_CORPUS,
            notice = "离线结果：本地内置语料（完整证据面需连接服务端）",
        )
    }

    suspend fun clause(ref: String): RepoResult<ClauseDetail> {
        var serverError: RepoResult.Error? = null
        if (!offlineOnly()) {
            val remote = withApi<ClauseDetail> { api, role ->
                when (val r = safeCall { api.clause(ref, role) }) {
                    is ApiResult.Success ->
                        // HTTP 200 + {"error": "未找到條文…"}（審查發現 #3）：
                        // 不能當成功渲染空條文
                        if (r.data.errorMessage != null || r.data.clauseId.isBlank()) {
                            RepoResult.Error("SERVER_MESSAGE",
                                r.data.errorMessage ?: "服务端未返回条文")
                        } else {
                            RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                        }
                    is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                    is ApiResult.Offline -> RepoResult.Error("OFFLINE", r.message)
                }
            }
            when {
                remote is RepoResult.Data -> return remote
                remote is RepoResult.Error && remote.code != "OFFLINE" -> serverError = remote
            }
        }
        // 服務端不可達或未找到 → 本地語料兜底（服務端語料版本偏移時收藏仍可讀）
        val local = localStore.clauseDetail(ref)
            ?: return serverError ?: RepoResult.Error("NOT_FOUND", "未找到条文 $ref")
        return RepoResult.Data(
            local, ResultOrigin.LOCAL_CORPUS,
            notice = "离线条文：异文/注家/历代引用等证据面需连接服务端",
        )
    }

    suspend fun match(
        symptoms: List<String>,
        pulse: List<String>,
        sixChannel: String?,
    ): RepoResult<MatchData> {
        if (offlineOnly()) {
            return RepoResult.Error(
                "OFFLINE", "方证匹配需要连接 Hermes 服务端（离线模式已开启）")
        }
        return withApi { api, role ->
            when (val r = safeCall {
                api.match(MatchRequest(symptoms = symptoms, pulse = pulse,
                    sixChannel = sixChannel?.takeIf { it.isNotBlank() }, role = role))
            }) {
                is ApiResult.Success ->
                    r.data.errorMessage?.let {
                        RepoResult.Error("SERVER_MESSAGE", it)
                    } ?: RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                is ApiResult.Offline -> RepoResult.Error("OFFLINE", "无法连接服务端：${r.message}")
            }
        }
    }

    suspend fun agent(question: String): RepoResult<AgentData> {
        if (offlineOnly()) {
            return RepoResult.Error("OFFLINE", "智能体需要连接 Hermes 服务端（离线模式已开启）")
        }
        return withApi { api, role ->
            when (val r = safeCall { api.agent(AgentRequest(question = question, role = role)) }) {
                is ApiResult.Success ->
                    r.data.errorMessage?.let {
                        RepoResult.Error("SERVER_MESSAGE", it)
                    } ?: RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                is ApiResult.Offline -> RepoResult.Error("OFFLINE", "无法连接服务端：${r.message}")
            }
        }
    }

    /** 首頁/設置頁狀態卡：health + whoami + content manifest。 */
    suspend fun serverStatus(): ServerStatus {
        val (api, role) = try {
            api()
        } catch (e: IllegalArgumentException) {
            return ServerStatus(reachable = false,
                detail = "服务端地址无效：${e.message ?: ""}")
        }
        val health: HealthData = when (val r = safeCall { api.health() }) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return ServerStatus(
                reachable = true, detail = "${r.code}: ${r.message}")
            is ApiResult.Offline -> return ServerStatus(
                reachable = false, detail = r.message)
        }
        var status = ServerStatus(
            reachable = true, ready = health.ready, backend = health.backend)
        when (val w = safeCall { api.whoami(role) }) {
            is ApiResult.Success -> {
                val who: WhoAmI = w.data
                status = status.copy(
                    roleCeiling = who.roleCeiling,
                    effectiveRole = who.effectiveRole,
                    principal = who.principalId,
                )
            }
            is ApiResult.Failure -> status = status.copy(detail = "${w.code}: ${w.message}")
            is ApiResult.Offline -> {}
        }
        when (val m = safeCall { api.contentManifest() }) {
            is ApiResult.Success -> {
                val man: ManifestData = m.data
                status = status.copy(contentVersion = man.contentVersion)
            }
            else -> {}
        }
        return status
    }

    suspend fun localStats(): Pair<Int, Int> {
        localStore.ensureLoaded()
        return localStore.stats()
    }

    suspend fun favoriteHits(): List<SearchHit> {
        localStore.ensureLoaded()
        val favs = settingsRepo.current().favorites
        return favs.mapNotNull { id ->
            localStore.byId(id)?.let { c ->
                SearchHit(
                    clauseId = c.clauseId, clauseNumber = c.clauseNumber,
                    chapter = c.chapter, sixChannel = c.sixChannel,
                    text = c.cleanText, textType = c.textType, layer = c.layer,
                    formulas = c.formulaNames,
                )
            }
        }.sortedBy { it.clauseNumber ?: Int.MAX_VALUE }
    }

    suspend fun formulaRules(): List<LocalClauseStore.FormulaRule> {
        localStore.ensureLoaded()
        return localStore.formulaRules()
    }
}
