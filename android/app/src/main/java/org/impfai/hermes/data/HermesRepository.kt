package org.impfai.hermes.data

import org.impfai.hermes.core.audit.AuditLog
import org.impfai.hermes.core.llm.DirectLlm
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.AgentRequest
import org.impfai.hermes.core.model.CitationReport
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
import org.impfai.hermes.core.model.evidenceGradeForLayer
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.engine.LocalFormulaMatcher
import org.impfai.hermes.engine.SkillStore
import org.impfai.hermes.engine.TextNorm
import org.impfai.hermes.engine.UnifiedRetriever

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
    /** 本機審計軌跡（評審建議七）：null = 不記錄（測試用）。
     *  服務端通道與 VIP 直連通道都記——直連模式沒有服務端審計，
     *  本機軌跡是唯一的證據記錄。 */
    private val auditLog: AuditLog? = null,
    /** 全庫古籍檢索（v1.10 統一取證）：null = 僅傷寒論條文層（測試用）。 */
    private val libraryStore: LibraryStore? = null,
    /** Skill 庫（v1.10 深度思考方法指引）：null = 跳過。 */
    private val skillStore: SkillStore? = null,
) {

    private val retriever: UnifiedRetriever? by lazy {
        libraryStore?.let { UnifiedRetriever(localStore, it) }
    }

    /** 統一取證：全庫可用時兩路並行，否則退傷寒論條文層。 */
    private suspend fun unifiedSearch(
        query: String, topK: Int,
    ): List<UnifiedRetriever.UnifiedHit> =
        retriever?.search(query, topK)
            ?: localStore.search(query, topK = topK).map {
                UnifiedRetriever.UnifiedHit(
                    sourceType = "clause", ref = it.clauseId, text = it.text,
                    grade = evidenceGradeForLayer(it.layer),
                    clauseId = it.clauseId, book = "傷寒論",
                    section = it.chapter)
            }

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
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
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
            notice = if (localStore.vipContentAvailable())
                "离线条文（VIP 全量知识库：注家/异文/关系已内置；历代引用溯源需服务端）"
            else
                "离线条文：异文/注家/历代引用等证据面需连接服务端",
        )
    }

    suspend fun match(
        symptoms: List<String>,
        pulse: List<String>,
        sixChannel: String?,
    ): RepoResult<MatchData> {
        var requestedRole = ""
        var result: RepoResult<MatchData>? = null
        if (!offlineOnly()) {
            val remote = withApi<MatchData> { api, role ->
                requestedRole = role ?: ""
                when (val r = safeCall {
                    api.match(MatchRequest(symptoms = symptoms, pulse = pulse,
                        sixChannel = sixChannel?.takeIf { it.isNotBlank() }, role = role))
                }) {
                    is ApiResult.Success ->
                        r.data.errorMessage?.let {
                            RepoResult.Error("SERVER_MESSAGE", it)
                        } ?: RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                    is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                    is ApiResult.Offline -> RepoResult.Error("OFFLINE", r.message)
                }
            }
            // 策略類錯誤（403 等）不回退：降級繞過授權等於客戶端自行提權
            if (!(remote is RepoResult.Error && remote.code == "OFFLINE")) result = remote
        }
        // 端側確定性匹配（doctor.py 移植；VIP 純端側模式的默認路徑）
        val final = result ?: RepoResult.Data(
            LocalFormulaMatcher.match(localStore, symptoms, pulse, sixChannel),
            ResultOrigin.LOCAL_CORPUS,
            notice = "端侧匹配：本地规则库确定性计算（未连接服务端）",
        )
        auditMatch(symptoms, pulse, sixChannel, requestedRole, final)
        return final
    }

    /**
     * 服務端智能體。
     * @param roleOverride 會話模式的角色請求（評審建議十：模式 = 服務端
     *   真實存在的角色面）；null 沿用「我的」頁角色。提權由服務端裁定拒絕。
     * @param maxSteps 推理深度（服務端裁剪至 1..12）。
     */
    suspend fun agent(
        question: String,
        roleOverride: String? = null,
        maxSteps: Int = 5,
    ): RepoResult<AgentData> {
        if (offlineOnly()) {
            return RepoResult.Error("OFFLINE", "智能体需要连接 Hermes 服务端（离线模式已开启）")
        }
        var requestedRole = ""
        val result = withApi<AgentData> { api, role ->
            val effRole = roleOverride?.takeIf { it.isNotBlank() } ?: role
            requestedRole = effRole ?: ""
            when (val r = safeCall {
                api.agent(AgentRequest(question = question,
                    maxSteps = maxSteps.coerceIn(1, 12), role = effRole))
            }) {
                is ApiResult.Success ->
                    r.data.errorMessage?.let {
                        RepoResult.Error("SERVER_MESSAGE", it)
                    } ?: RepoResult.Data(r.data, ResultOrigin.SERVER, r.meta)
                is ApiResult.Failure -> RepoResult.Error(r.code, r.message, r.retryable)
                is ApiResult.Offline -> RepoResult.Error("OFFLINE", "无法连接服务端：${r.message}")
            }
        }
        auditAgent("agent", question, requestedRole, result)
        return result
    }

    // ------------------------------------------------------------------
    // VIP 直連大模型：本地 BM25 取證 → 模型作答 → 本地 CitationGuard 核驗
    // ------------------------------------------------------------------
    private val reClauseId = Regex("SHL_SONGBEN_(?:AUX_)?\\d{4}")

    private val directSystemPrompt = """
        你是中医古籍文献研究助手（研发者：医哲未来人工智能研究院 IMPF-AI）。
        规则：
        1. 只能依据下方【证据】作答：伤寒论条文引用其方括号内条文 ID
           （如 [SHL_SONGBEN_0012]）；其他古籍引用其方括号内书名章节
           （如 [《千金要方·卷九》]），不得编造出处或凭记忆引用。
        2. 证据按等级标注（经典原文 > 历代要籍 > 论说文献 > 医案纪实），
           结论优先依据高等级证据；等级冲突时说明分歧。
        3. 证据不足以回答时，明确说明"现有证据不足"，不要臆测。
        4. 输出结构：主要结论 → 证据依据（逐条出处+要点）→ 局限与不确定性。
        5. 这是古籍文献研究，不是诊疗：不得给出用药剂量建议、不得下诊断，
           涉及现实病情时提醒用户咨询执业中医师。
        6. 使用与提问相同的语言（简体/繁体）回答，保持简洁。
    """.trimIndent()

    /** 直連流式事件：步驟時間線 + 增量文本（智能體頁可視化過程）。 */
    sealed interface StreamEvent {
        data class Step(val label: String) : StreamEvent
        data class Delta(val text: String) : StreamEvent
    }

    /**
     * VIP 直連流式管線（v1.10 全庫取證 + 深度思考）：
     * 標準：全庫並行取證（條文 BM25 + 803 部古籍剪枝並行）→ 流式生成
     * → 本地 CitationGuard 核驗（條文 ID + 書名出處雙軌）。
     * 深度思考（[deepThink]）另加：檢索規劃（模型擬定補充檢索詞）→
     * Skill 方法指引 → 證據評估補檢一輪 → 再成稿——多次檢索/調用
     * 工具與 Skill 後作答。
     */
    suspend fun directAgentStream(
        question: String,
        deepThink: Boolean = false,
        onEvent: (StreamEvent) -> Unit,
    ): RepoResult<AgentData> {
        val s = settingsRepo.current()
        if (s.llmApiKey.isBlank()) {
            return RepoResult.Error("NO_KEY",
                "未配置模型 API Key，请在“我的 → 直连大模型”中设置")
        }
        localStore.ensureLoaded()
        val model = s.llmModel.ifBlank { DirectLlm.defaultModel(s.llmProvider) }
        var stepNo = 0
        fun step(label: String) {
            stepNo += 1
            onEvent(StreamEvent.Step("$stepNo. $label"))
        }
        fun note(label: String) = onEvent(StreamEvent.Step("　$label"))

        // —— 檢索規劃（深度思考）——
        val queries = LinkedHashSet<String>().apply { add(question.take(60)) }
        if (deepThink) {
            step("深度思考 · 检索规划（模型拟定补充检索词）…")
            DirectLlm.complete(
                provider = s.llmProvider, apiKey = s.llmApiKey,
                baseUrl = s.llmBaseUrl, model = s.llmModel,
                system = "你是中医古籍检索规划助手。只输出检索词本身。",
                user = "问题：$question\n给出至多 3 个适合古籍全文检索的" +
                    "关键词（每行一个，2~8 个汉字，不要编号不要解释）。",
                maxTokens = 200,
            ).getOrNull()?.lines()
                ?.map { it.trim().trim('、', '，', '。', '.', ',', '-', '*') }
                ?.filter { it.length in 2..12 && it.any { c ->
                    c.code in 0x3400..0x9FFF } }
                ?.take(3)?.forEach { queries.add(it) }
            note("检索词：" + queries.joinToString("、"))
        }

        // —— 全庫並行取證（條文層 + 古籍層，按證據等級 top-k）——
        step("全库取证：伤寒论条文 BM25 + 803 部古籍（稀字剪枝·并行·早停）…")
        val t0 = System.currentTimeMillis()
        val pool = LinkedHashMap<String, UnifiedRetriever.UnifiedHit>()
        for (q in queries) {
            unifiedSearch(q, topK = 8).forEach { pool.putIfAbsent(it.ref, it) }
        }
        var evidence = pool.values
            .sortedByDescending { it.grade.stars }.take(10)
        note("检得 ${evidence.size} 条（条文 " +
            "${evidence.count { it.sourceType == "clause" }} + 古籍 " +
            "${evidence.count { it.sourceType == "library" }}）· " +
            "用时 ${System.currentTimeMillis() - t0}ms · 按证据等级排序")

        // —— Skill 方法指引（深度思考）——
        var skillBlock = ""
        if (deepThink && skillStore != null) {
            step("匹配领域 Skill（139 个内置技能）…")
            val skills = skillStore.search(question, topK = 2)
            if (skills.isEmpty()) {
                note("无强相关 Skill，跳过")
            } else {
                val names = ArrayList<String>()
                skillBlock = buildString {
                    append("【方法指引（领域 Skill，仅指导分析框架）】\n")
                    for (sk in skills) {
                        val title = skillStore.titleOf(sk)
                        names.add(title)
                        append("— ").append(title).append("：\n")
                        append(skillStore.read(sk).markdown.take(600))
                        append("\n")
                    }
                }
                note("命中 Skill：" + names.joinToString("、"))
            }
        }

        // —— 證據評估 · 補充檢索一輪（深度思考）——
        if (deepThink) {
            step("证据评估：判断是否需要补充检索…")
            val summary = evidence.joinToString("\n") {
                "[${it.ref}] " + it.text.take(40)
            }
            val verdict = DirectLlm.complete(
                provider = s.llmProvider, apiKey = s.llmApiKey,
                baseUrl = s.llmBaseUrl, model = s.llmModel,
                system = "你是证据评估助手。只输出结论行。",
                user = "问题：$question\n已检得证据：\n$summary\n" +
                    "若证据不足以专业作答，输出一行「补充检索: 关键词」" +
                    "（至多 2 个，顿号分隔）；若已充分，输出「证据充分」。",
                maxTokens = 100,
            ).getOrNull() ?: ""
            val extra = Regex("补充检索[:：]\\s*(.+)").find(verdict)
                ?.groupValues?.get(1)
                ?.split('、', '，', ',')?.map { it.trim() }
                ?.filter { it.length in 2..12 }?.take(2).orEmpty()
            if (extra.isEmpty()) {
                note("证据充分，进入成稿")
            } else {
                note("补充检索：" + extra.joinToString("、"))
                for (q in extra) {
                    unifiedSearch(q, topK = 6).forEach {
                        pool.putIfAbsent(it.ref, it)
                    }
                }
                evidence = pool.values
                    .sortedByDescending { it.grade.stars }.take(12)
                note("证据扩充至 ${evidence.size} 条")
            }
        }

        // —— 流式終答 ——
        val evidenceBlock = if (evidence.isEmpty()) "（全库检索无命中）"
        else evidence.joinToString("\n") {
            "[${it.ref}]（${it.grade.label}） ${it.text}"
        }
        step("调用 $model 流式生成（仅限所给证据作答）…")
        val answer = DirectLlm.completeStream(
            provider = s.llmProvider, apiKey = s.llmApiKey,
            baseUrl = s.llmBaseUrl, model = s.llmModel,
            system = directSystemPrompt,
            user = (if (skillBlock.isBlank()) "" else skillBlock + "\n") +
                "【证据】\n$evidenceBlock\n\n【问题】\n$question",
            maxTokens = s.llmMaxTokens,
            onDelta = { onEvent(StreamEvent.Delta(it)) },
        ).getOrElse { e ->
            val err = RepoResult.Error("LLM_ERROR", e.message ?: "模型调用失败")
            auditAgent("direct", question, "byok", err)
            return err
        }
        step("本地 CitationGuard 核验引用（条文 ID + 书名出处双轨）…")
        val out = RepoResult.Data(
            buildUnifiedAgentData(question, answer, evidence, model, deepThink),
            ResultOrigin.SERVER)
        auditAgent("direct", question, "byok", out)
        return out
    }

    /** 統一取證版回答構建：條文 ID 與《書名》出處雙軌核驗。 */
    private fun buildUnifiedAgentData(
        question: String,
        answer: String,
        evidence: List<UnifiedRetriever.UnifiedHit>,
        model: String,
        deepThink: Boolean,
    ): AgentData {
        val clauseIds = evidence.filter { it.sourceType == "clause" }
            .map { it.clauseId }.toSet()
        fun fold(t: String) = TextNorm.foldVariants(TextNorm.s2t(t))
        val evidenceBooks = evidence.filter { it.sourceType == "library" }
            .map { fold(it.book) }.toSet()
        val citedIds = reClauseId.findAll(answer)
            .map { it.value }.distinct().toList()
        val citedBooks = Regex("《([^》·]{1,25})(?:·[^》]{1,30})?》")
            .findAll(answer).map { it.groupValues[1] }.distinct().toList()
        val verified = ArrayList<String>()
        val outside = ArrayList<String>()
        val unsupported = ArrayList<String>()
        for (id in citedIds) {
            when {
                id in clauseIds -> verified.add(id)
                localStore.byId(id) != null -> outside.add(id)
                else -> unsupported.add(id)
            }
        }
        for (book in citedBooks) {
            when {
                fold(book) in evidenceBooks || fold(book) == fold("傷寒論") ->
                    verified.add("《$book》")
                else -> outside.add("《$book》")
            }
        }
        val cited = citedIds + citedBooks.map { "《$it》" }
        val report = CitationReport(
            cited = cited, verified = verified, unsupported = unsupported,
            outsideEvidence = outside,
            hasAnyCitation = cited.isNotEmpty(),
            ok = cited.isNotEmpty() && unsupported.isEmpty() &&
                outside.isEmpty(),
        )
        val tools = ArrayList<String>()
        tools.add("unified_evidence_search")
        tools.add("local_bm25_rag")
        if (libraryStore != null) tools.add("library_grep_parallel")
        if (deepThink) {
            tools.add("retrieval_planning")
            tools.add("evidence_review")
            if (skillStore != null) tools.add("skill_rag")
        }
        tools.add("local_citation_guard")
        return AgentData(
            question = question, answer = answer,
            backend = (if (deepThink) "直连·深研·" else "直连·") + model,
            toolsUsed = tools,
            evidenceClauseIds = evidence
                .filter { it.sourceType == "clause" }.map { it.clauseId },
            citationReport = report,
            safetyNotice = "直连模式：回答由第三方大模型生成，引用仅经本地核验" +
                "（弱于服务端全链路证据闸门）；内容供文献学习参考，" +
                "不构成诊断或治疗建议。",
        )
    }

    private fun buildDirectAgentData(
        question: String, answer: String,
        hits: List<SearchHit>, evidenceIds: Set<String>, model: String,
    ): AgentData {
        val cited = reClauseId.findAll(answer).map { it.value }
            .distinct().toList()
        val verified = ArrayList<String>()
        val outside = ArrayList<String>()
        val unsupported = ArrayList<String>()
        for (id in cited) {
            when {
                id in evidenceIds -> verified.add(id)
                localStore.byId(id) != null -> outside.add(id)
                else -> unsupported.add(id)
            }
        }
        val report = CitationReport(
            cited = cited, verified = verified, unsupported = unsupported,
            outsideEvidence = outside,
            hasAnyCitation = cited.isNotEmpty(),
            ok = cited.isNotEmpty() && unsupported.isEmpty() &&
                outside.isEmpty(),
        )
        return AgentData(
            question = question, answer = answer, backend = "直连·$model",
            toolsUsed = listOf("local_bm25_rag", "local_citation_guard"),
            evidenceClauseIds = hits.map { it.clauseId },
            citationReport = report,
            safetyNotice = "直连模式：回答由第三方大模型生成，引用仅经本地核验" +
                "（弱于服务端全链路证据闸门）；内容供文献学习参考，" +
                "不构成诊断或治疗建议。",
        )
    }

    /**
     * VIP 直連模式（非流式，論文潤色等內部使用）。密鑰僅存本機。
     */
    suspend fun directAgent(question: String): RepoResult<AgentData> {
        val s = settingsRepo.current()
        if (s.llmApiKey.isBlank()) {
            return RepoResult.Error("NO_KEY",
                "未配置模型 API Key，请在“我的 → 直连大模型”中设置")
        }
        localStore.ensureLoaded()
        val hits = localStore.search(question, topK = 6)
        val evidenceIds = hits.map { it.clauseId }.toSet()
        val evidenceBlock = if (hits.isEmpty()) "（本地检索无命中）"
        else hits.joinToString("\n") { "[${it.clauseId}] ${it.text}" }
        val userPrompt = "【证据条文】\n$evidenceBlock\n\n【问题】\n$question"

        val answer = DirectLlm.complete(
            provider = s.llmProvider, apiKey = s.llmApiKey,
            baseUrl = s.llmBaseUrl, model = s.llmModel,
            system = directSystemPrompt, user = userPrompt,
            maxTokens = s.llmMaxTokens,
        ).getOrElse { e ->
            val err = RepoResult.Error("LLM_ERROR", e.message ?: "模型调用失败")
            auditAgent("direct", question, "byok", err)
            return err
        }

        val model = s.llmModel.ifBlank { DirectLlm.defaultModel(s.llmProvider) }
        val out = RepoResult.Data(
            buildDirectAgentData(question, answer, hits, evidenceIds, model),
            ResultOrigin.SERVER)
        auditAgent("direct", question, "byok", out)
        return out
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

    suspend fun formulaRules(): List<LocalClauseStore.FormulaRule> =
        localStore.formulaCatalog()

    // ------------------------------------------------------------ audit

    /** kind: agent（服務端）| direct（VIP 直連）。審計失敗不影響主流程。 */
    private suspend fun auditAgent(
        kind: String,
        question: String,
        requestedRole: String,
        result: RepoResult<AgentData>,
    ) {
        val log = auditLog ?: return
        val entry = when (result) {
            is RepoResult.Data -> {
                val d = result.value
                val report = d.citationReport
                AuditLog.Entry(
                    caseId = AuditLog.newCaseId(),
                    ts = AuditLog.timestamp(),
                    kind = kind,
                    input = question.take(500),
                    requestedRole = requestedRole,
                    effectiveRole = result.meta?.effectiveRole,
                    backend = d.backend ?: result.meta?.backend ?: "",
                    evidence = d.evidenceClauseIds,
                    verdict = when {
                        d.refused -> "安全闸门拒答"
                        report != null && report.ok ->
                            "引用已核验 ${report.verified.size} 条"
                        report != null && report.hasAnyCitation -> "引用部分核验"
                        else -> "无引用"
                    },
                    refused = d.refused,
                )
            }
            is RepoResult.Error -> AuditLog.Entry(
                caseId = AuditLog.newCaseId(),
                ts = AuditLog.timestamp(),
                kind = kind,
                input = question.take(500),
                requestedRole = requestedRole,
                verdict = "请求失败",
                resultCode = result.code,
            )
        }
        log.record(entry)
    }

    private suspend fun auditMatch(
        symptoms: List<String>,
        pulse: List<String>,
        sixChannel: String?,
        requestedRole: String,
        result: RepoResult<MatchData>,
    ) {
        val log = auditLog ?: return
        val input = buildString {
            append(symptoms.joinToString("、"))
            if (pulse.isNotEmpty()) append(" · 脉：${pulse.joinToString("、")}")
            sixChannel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }.take(500)
        val entry = when (result) {
            is RepoResult.Data -> AuditLog.Entry(
                caseId = AuditLog.newCaseId(),
                ts = AuditLog.timestamp(),
                kind = "match",
                input = input,
                requestedRole = requestedRole,
                effectiveRole = result.meta?.effectiveRole,
                backend = if (result.origin == ResultOrigin.SERVER)
                    (result.meta?.backend ?: "") else "端侧规则",
                evidence = result.value.matchedFormulaPatterns.map { it.formula },
                verdict = "匹配 ${result.value.matchCount} 方",
            )
            is RepoResult.Error -> AuditLog.Entry(
                caseId = AuditLog.newCaseId(),
                ts = AuditLog.timestamp(),
                kind = "match",
                input = input,
                requestedRole = requestedRole,
                verdict = "请求失败",
                resultCode = result.code,
            )
        }
        log.record(entry)
    }
}
