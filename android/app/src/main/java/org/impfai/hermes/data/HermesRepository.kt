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

    private suspend fun offlineOnly(): Boolean = settingsRepo.current().offlineOnly

    suspend fun search(query: String, sixChannel: String? = null, topK: Int = 12): RepoResult<SearchData> {
        if (!offlineOnly()) {
            val (api, role) = api()
            when (val r = safeCall {
                api.search(SearchRequest(query = query, topK = topK,
                    sixChannel = sixChannel, role = role))
            }) {
                is ApiResult.Success -> return RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                is ApiResult.Failure -> return RepoResult.Error(r.code, r.message, r.retryable)
                is ApiResult.Offline -> { /* fall through to local */ }
            }
        }
        val hits = localStore.search(query, topK, sixChannel)
        return RepoResult.Data(
            SearchData(query = query, hits = hits, count = hits.size),
            ResultOrigin.LOCAL_CORPUS,
            notice = "离线结果：本地内置语料（完整证据面需连接服务端）",
        )
    }

    suspend fun clause(ref: String): RepoResult<ClauseDetail> {
        if (!offlineOnly()) {
            val (api, role) = api()
            when (val r = safeCall { api.clause(ref, role) }) {
                is ApiResult.Success -> return RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                is ApiResult.Failure -> return RepoResult.Error(r.code, r.message, r.retryable)
                is ApiResult.Offline -> { /* fall through to local */ }
            }
        }
        val local = localStore.clauseDetail(ref)
            ?: return RepoResult.Error("NOT_FOUND", "未找到条文 $ref")
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
        val (api, role) = api()
        return when (val r = safeCall {
            api.match(MatchRequest(symptoms = symptoms, pulse = pulse,
                sixChannel = sixChannel?.takeIf { it.isNotBlank() }, role = role))
        }) {
            is ApiResult.Success -> RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
            is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
            is ApiResult.Offline -> RepoResult.Error("OFFLINE", "无法连接服务端：${r.message}")
        }
    }

    suspend fun agent(question: String): RepoResult<AgentData> {
        if (offlineOnly()) {
            return RepoResult.Error("OFFLINE", "智能体需要连接 Hermes 服务端（离线模式已开启）")
        }
        val (api, role) = api()
        return when (val r = safeCall { api.agent(AgentRequest(question = question, role = role)) }) {
            is ApiResult.Success -> RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
            is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
            is ApiResult.Offline -> RepoResult.Error("OFFLINE", "无法连接服务端：${r.message}")
        }
    }

    /** 首頁/設置頁狀態卡：health + whoami + content manifest。 */
    suspend fun serverStatus(): ServerStatus {
        val (api, role) = api()
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
