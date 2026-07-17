# Shanghan-Hermes Android 工程化实施记录

> 本文档对照《Shanghan-Hermes Android App 工程化建议 Protocol》（下称「原方案」），
> 记录本仓库实际落地的架构、对原方案的采纳与修改、以及验收证据。

## 0. 总体结论

原方案的核心判断 **全部采纳**：

> **Android 是可信交互端和离线知识端，Hermes Python 平台是权威推理端、
> 治理端、证据端和插件运行端。**

即「原生 Android 客户端 + Python 服务端核心 + 内置离线知识库」，
不做 WebView 包装，不内嵌 Python Runtime，不把治理逻辑复制到客户端。

```text
android/                     原生 Android 客户端（Jetpack Compose）
backend/                     Shanghan-Hermes Python 平台（原 zip 全部内容）
  └─ hermes_shanghan/server/v1.py     新增：API v1 契约层
docs/ANDROID.md              本文档
```

## 1. 仓库现状审计（实测，非沿用原方案数字）

| 项目 | 实测结果 |
|---|---|
| Python 测试基线（改造前） | **508 passed, 2 skipped**（54s，完全离线） |
| Python 测试（改造后） | **523 passed, 2 skipped**（新增 15 项 v1 契约测试；docs-sync 守卫同步至 525 项） |
| 条文记录 | 681 条（核心 398 条 original_clause） |
| 方剂规则 | 113 条 formula_pattern_rules |
| HTTP 路由 | 59 条业务路由 + /livez /readyz |
| 服务端框架 | 纯 stdlib ThreadingHTTPServer（零第三方依赖） |
| 角色体系 | patient < student < researcher < doctor，服务端裁定 |

> 原方案引用的「72 tests passed」是子集口径；实际全量测试套件 508 项全部通过，
> 这是比原方案更强的迁移基线。

## 2. 后端改造（Phase 1：API 合同层）——已完成

### 2.1 已实现

新增 `backend/hermes_shanghan/server/v1.py` + `http_server.py` 最小侵入接线：

- **路径版本化**：`/api/v1/<rest>` 直接映射既有 `/api/<rest>` 路由表；
- **统一响应信封**：`{request_id, api_version, data, error, meta}`；
- **固定错误码**：`INVALID_ARGUMENT / UNAUTHENTICATED / POLICY_DENIED /
  NOT_FOUND / RATE_LIMITED / NOT_READY / INTERNAL_ERROR`（+`retryable` 标记）；
- **`GET /api/v1/domains`**：从可执行的 `DomainPlugin` 注册表生成领域清单
  （planned 插件能力显式为空，不伪装）；
- **`GET /api/v1/content/manifest`**：离线内容包清单——逐文件 sha256 聚合出
  `corpus_fingerprint`，`content_version` 为指纹前 12 位；
- **`GET /api/v1/content/package/shanghan-core`**：确定性 zip 内容包下载
  （固定时间戳→同内容同字节→manifest 中的 sha256 可预先校验；
  `min_role=student`，患者主体不可整库拉取）；
- **旧 `/api/*` 响应逐字节不变**（Web 控制台/CLI/MCP 完全不受影响，
  由 `tests/test_v1_api.py::test_legacy_untouched` 守护）。

测试：`backend/tests/test_v1_api.py`（信封单元 + 端到端 HTTP：
旧接口冻结、信封结构、search 新旧一致性、401/403/404/400 错误码映射、
伪造 role 不提权、domains 诚实性、manifest 指纹确定性、内容包 sha256 校验）。

### 2.2 对原方案 5.x 的修改及理由

| 原方案 | 实际落地 | 理由 |
|---|---|---|
| 新增 FastAPI/Starlette 生产 Adapter | **暂缓**，保持 stdlib 纯度，先冻结 v1 合同 | 该项目「零第三方依赖」是刻意架构决策（离线演示/审计友好）。v1 契约层已把「客户端合同」与「传输实现」解耦——未来上 ASGI 时客户端零改动。HTTPS/OIDC/限流本就该由反向代理层承担 |
| 路由重命名（`/api/v1/agent/messages` 等） | **不重命名**，仅版本化现有路径 | 重命名制造双份维护成本，对客户端零收益 |
| 信封携带 `evidence.status: verified` 与 `safety` 块 | **不在信封层伪造**。证据核验结果在各端点 data 内部（`citation_report`、`evidence_clause_ids`、`safety_notice`、`assistive_only`） | 传输层无从「验证」证据；在信封里编造 verified 状态违背本项目 fail-closed 的诚实原则。信封 meta 只回传输层可信事实：生效角色/角色上限/后端/时间戳 |
| SSE Run events | **暂缓**（Run 轮询接口已存在：`GET /api/v1/runs/{id}` + spans/evidence 分页） | stdlib ThreadingHTTPServer 上实现 SSE 长连接收益/复杂度比差；Run Center 属 Phase 5 |
| 静态 API Key 升级 OIDC/JWT | **暂缓**，沿用 `HERMES_API_KEYS` 角色绑定 Key | 部署层问题；客户端已按「短期令牌可替换」设计（DataStore 存储、可随时更换） |

## 3. Android 客户端（Phase 2+3：基础壳 + 知识阅读 MVP + 部分 Phase 4/5 入口）

### 3.1 已实现功能

- **五个一级入口**（采纳原方案 8.1）：首页 / 检索 / 辨证 / 智能体 / 我的；
- **首页**：服务端状态卡（readyz+whoami+语料版本）、快速检索、六经入口、收藏、免责声明；
- **检索**：六经过滤 + BM25 检索；**离线优先回退**——服务端不可达时自动切换
  APK 内置语料，结果显式标记「本地/服务端」来源徽章（采纳原方案第七节：
  不可无差别展示）；
- **条文阅读器**：原文、方剂块（组成/煎法/服法）、证候要素、版本异文（B 层）、
  九注家（C 层）、归纳规则、条文关系跳转、收藏、患者投影提示、安全声明
  （采纳原方案 8.2 分层）；
- **辨证**：四诊要素采集（症状/脉象 chips + 六经）→ 服务端方证匹配
  （匹配分、✓命中、反证、禁忌、证据条文跳转、release 分级徽章、
  assistive_only 安全条）；**方剂库** Tab 离线可浏览 113 方
  （组成/核心证候/支持条文/归纳警示）；
- **智能体**：对话式界面，回答卡片固定分层（采纳原方案 8.3）：
  引用核验徽章（✓已核验 / △部分核验 / ○无引用）、溯源率、未支持引用列表、
  证据条文 chips（点击跳转条文）、工具调用、安全声明；服务端拒答
  （患者模式处方拦截等）以显式警示卡渲染；
- **我的**：服务端地址/访问令牌/请求角色配置（**API 接入设置**）、测试连接、
  简繁显示切换、仅离线模式、关于（研发者：**医哲未来人工智能研究院（IMPF-AI）**、
  免责声明、架构说明）。

### 3.2 安全（采纳原方案第十节全部要点）

- APK **不含任何模型供应商密钥**（设置页明示；仓库可 grep 验证）；
- 角色只是「请求」，上限由服务端令牌绑定（403 POLICY_DENIED 原样呈现，
  客户端**不**在策略错误时回退本地——降级绕过授权等于自行提权）；
- Release 构建 `cleartextTrafficPermitted=false`（Network Security Config），
  仅 debug 构建允许明文连接 10.0.2.2/局域网开发服务端；设置页对 http://
  地址显示红色警示；
- 患者投影字段（`_role_projection`）在条文/匹配页显式提示「已由服务端移除」；
- 免责声明常驻首页与关于页：辅助学习研究工具，非自动诊断/处方。

### 3.3 技术栈与原方案差异

| 原方案 | 实际落地 | 理由 |
|---|---|---|
| 多模块工程（20+ Gradle 模块） | **单 `:app` 模块**，包结构按 `core/ engine/ data/ ui/` 预划分 | Phase 2/3 功能面（6 屏）撑不起 20 模块的构建面；包边界即未来模块边界，拆分是机械性重构。原方案的模块图保留为目标形态 |
| Hilt | **手动 AppContainer** | 单模块 + 单容器规模下 Hilt 只增加 KSP 构建面；模块化拆分时一并引入 |
| Room + FTS5 + 预置 SQLite | **内存索引 + 构建期资产复制** | 实测核心语料 681 条/<1MB：Room+FTS5 是为「古籍全库离线」（803 部）准备的，v1 引入纯属超配。语料唯一真源是 `backend/data/shanghan`，Gradle `copyCorpusAssets` 构建期复制，杜绝双份漂移。Room 在 classics 离线包落地时引入 |
| WorkManager 内容包同步 | **暂缓**；服务端 manifest/package 协议已就绪，首页显示服务端语料版本 | 客户端下载+校验+原子切换属 Phase 4；当前 APK 语料与仓库同源，版本一致性由构建保证 |
| Retrofit/OkHttp/DataStore/Navigation/M3 | ✅ 全部采纳 | — |
| Paging 3 / Coil | 暂缓 | 当前无分页长列表、无图片需求 |

### 3.4 离线引擎（engine-local）

按原方案第三节「只重写确定性核心」：

- `TextNorm.kt`：`textutil.py` 逐行移植（异体字折叠、领域 S2T/T2S 映射、
  CJK unigram+bigram 分词，码段 U+3400..U+9FFF 与 Python 正则一致）；
- `Bm25.kt`：`rag/bm25.py` 精确移植（k1=1.5, b=0.75，公式/常数逐项一致）;
- `LocalClauseStore.kt`：`clause_rag.py` 检索的确定性子集
  （条文号直查 99.0、BM25 归一基准 10.0、辅助篇 ×0.7、方名 +3.0、
  方证条文 +0.5、`(-score, clause_id)` 排序）。
- **诚实差异**：症状/脉象覆盖加分依赖服务端 `EntityExtractor`（未移植），
  离线排序与服务端可能有差异——因此离线结果强制带「本地」徽章，
  Python↔Kotlin 金标准对照在 Phase 4 连同 FormulaMatcher 一起做；
- 单元测试：`TextNormTest` / `Bm25Test`（期望值取自 Python 实际输出）。

### 3.5 未做（原方案中明确留给后续 Phase 的）

- Run Center / 审批 / Replay / Artifact 下载（Phase 5；后端接口已在 v1 下可用）；
- 古籍全库（classics）检索界面（Phase 6 域清单驱动；`/api/v1/domains` 已就绪）；
- 深度研究 / 论文 / 学术计量界面（researcher 桌面场景优先级低于移动端阅读）；
- 平板/折叠屏三栏自适应（当前响应式单栏可用，M3 Adaptive 属 Phase 6 打磨）；
- 离线方证匹配（**有意不做**：临床辅助计算留在服务端，其安全裁定不可绕过）。

## 4. 构建与运行

### 后端

```bash
cd backend
python3 -m hermes_shanghan serve --host 0.0.0.0 --port 8765
# 角色绑定 Key（生产必须）：
HERMES_API_KEYS="tokS:student:alice,tokD:doctor:dr-wang" \
  python3 -m hermes_shanghan serve --host 0.0.0.0 --port 8765
# 测试
python3 -m pytest tests/ -q
```

### Android

```bash
cd android
# 需要 Android SDK（local.properties: sdk.dir=...）
./gradlew :app:assembleDebug        # debug APK（允许明文，连 10.0.2.2:8765）
./gradlew :app:assembleRelease      # release（禁明文，需签名配置）
./gradlew :app:testDebugUnitTest    # 跨语言一致性单元测试
```

模拟器调试：设置页服务端地址填 `http://10.0.2.2:8765/`（宿主机回环）。

## 5. 验收对照（原方案第十三节）

| 指标 | 状态 |
|---|---|
| LLM 密钥泄漏 = 0 | ✅ APK 无任何供应商密钥字段；令牌仅为服务端签发的角色 Key |
| 患者禁用字段泄漏 = 0 | ✅ 投影在服务端序列化出口执行；客户端另有 `_role_projection` 提示 |
| 伪造 role 不提权 | ✅ 服务端 403 + 客户端测试 `test_v1_unauthenticated_and_policy_denied` |
| 旧接口回归 = 0 | ✅ 全量 Python 测试套件通过 + legacy 冻结测试 |
| 内容更新可校验 | ✅ manifest sha256 + 确定性 zip |
| Python/Kotlin 金标准一致率 | 部分（TextNorm/BM25 已对照；FormulaMatcher 留 Phase 4） |
| Crash-free / ANR / P95 | 需真机与灰度环境，非本仓库可验收 |

## 6. 对抗性代码审查记录

合入前对 v1 契约层 + Android 全部代码跑了 5 维度并行审查（后端安全回归、
网络/DTO 合同、Kotlin↔Python 移植一致性、UI/状态、构建配置），每个发现由
独立怀疑者代理验证：**20 项发现 → 16 项确认 → 全部修复**。要点：

- 后端：409（run cancel 撞终态）补 `CONFLICT` 错误码；裸 `/api/v1` 修正为
  留在 API 分支（信封化 404，不再绕过鉴权落入静态处理器）；
- 客户端：HTTP 200 软错误（`{"error":…}`）不再被当成功渲染空条文；
  非法服务端地址不再使 Retrofit 构建崩溃逃逸到 UI；收藏按持久化真值回写；
  检索取消在途请求防乱序覆盖；BM25 舍入与 Python `round(x,4)`（银行家舍入）
  对齐；`allowBackup=false`（令牌不随云备份外流）；缺省补全 scheme 改为
  https；release 暂用 debug 签名保证可安装（正式发布须换 IMPF-AI 签名）；
- 两项「有意偏离」保留并在代码内注明：离线纯数字直查条文（Python 端
  CJK 分词对纯数字返回空）；方名 +3.0 加分暂不做 lexicon 别名归一（Phase 4）。

## 7. 外部产品/安全评审（2026-07）逐条采纳与改进记录

> 评审共十三节。处理原则：**合理的直接落地；方向对但方案不适配本仓库
> 刻意架构决策的，给出改进式落地；确实不适用的暂缓并说明理由。**
> 所有「暂缓」不是否定，而是排入路线图并写明触发条件。

### 7.1 逐条决定

| # | 评审建议 | 决定 | 落地/理由 |
|---|---|---|---|
| 二 | Agent 产品架构：任务状态可视化，而非聊天气泡 | **采纳（改进式）** | 后端本就返回真实执行轨迹 `agent_trace`（tool_scope/tool_call/reflection/citation_check/hypotheses/claim_binding…），此前客户端**丢弃**了它。现在回答卡渲染「执行过程」清单（`AgentPresentation.humanizeTrace`，✓/⚠ 逐步呈现），等待期显示服务端流水线说明+已用秒数。**改进点**：不做假的分步打勾动画——stdlib 后端无 SSE，中途进度不可观测，伪造「正在检索古籍…✓」是在证据型产品里制造不诚实 UI。SSE/Run 轮询接入后升级为真实时进度（Phase 5，后端 `GET /api/v1/runs/{id}` 已就绪） |
| 三 | Evidence UX：证据卡（原文/出处/等级/现代解释） | **采纳** | 智能体回答的 `evidence_clause_ids` 升级为结构化 Evidence Card：原文摘录（本地语料回查，离线可核对）、出处章节、A–E 分层徽章、星级证据等级、点击回源条文页。**改进点**：星级=语料证据分层（A 原文直述=★★★★★ … E 模型推理=★）的确定性映射，不是模型自评分；「现代解释/争议」对应 C 层注家与 B 层异文，在条文详情页已有，不在回答卡里重复塞入 |
| 四 | 检索升级 Room+FTS5+HNSW 向量 | **暂缓（维持既有决策）** | 实测语料 681 条/<1MB，内存 BM25 检索毫秒级返回；Room+FTS5+向量索引在此规模是纯超配，且引入 Python/Kotlin 排序漂移风险。**触发条件**：classics 古籍全库离线包（803 部）落地时引入 Room+FTS5；向量/Hybrid 检索先在服务端做（embedding 属权威推理端），端侧只做候选缓存。方向认同，时机不到 |
| 五 | Token 不应存 DataStore | **采纳** | 新增 `SecureTokenStore`：Android Keystore 主密钥 + EncryptedSharedPreferences（AES256-SIV/GCM）；DataStore 里的历史明文令牌首次读取时一次性迁移并抹除；个别 ROM Keystore 损坏时降级明文并在设置页红字明示（不静默假装安全）。**改进点**：评审推荐的 OAuth2 PKCE + 短期 token 是正确终态，但依赖服务端 OIDC 签发能力——后端 stdlib 零依赖是刻意决策，OIDC 属部署层（反向代理/网关），排入路线图 |
| 六 | Release 用 debug 签名是发布级问题 | **采纳** | release 签名改由 `android/keystore.properties`（.gitignore 排除，含 `*.jks`）驱动；文件缺失时回退 debug 签名并打印「禁止分发」构建警告——保留「本地可安装验证」工作流的同时，使正式发布路径就绪。Google Play App Signing / CI 注入密钥即接此配置 |
| 七 | 医疗 AI 缺审计系统 | **采纳（分层）** | 新增本机 `AuditLog`（JSONL）：每次智能体问答/方证匹配记录 caseId（UTC 派生 `20260717-093012-483`）、时间戳、输入、请求/生效角色、作答后端、证据条文、引用核验结果、拒答标记、错误码；容量 500 条自动裁剪；设置页可查看/清除。**分层说明**：权威审计在服务端（鉴权、角色裁定、run 记录），客户端审计解决「移动端自查与教学复盘」——两端各记各的事实，不互相冒充 |
| 八 | 首页像工具集合，缺产品感 | **采纳** | 首页重设计：产品定位首屏（名称+「古籍医学智能体 · 证据可溯源」）→ 四个行动卡（开始咨询/古籍探索/方证辨证/今日条文）→ 今日条文卡（按天确定性轮换核心条文）→ 服务端状态降为次要信息行 → 六经/收藏/免责声明。「今日知识」采纳为「今日条文」：确定性选取（`dailyClauseIndex`），非随机 |
| 九 | 缺账号体系（登录+身份） | **改进式落地** | 完整账号体系依赖服务端 OIDC（暂缓，见 #五）。已做：设置页重排，「身份与角色」前置为第一小节（患者/学生/研究者/医师，人话文案），服务端地址/令牌降级为「服务端接入」小节——普通用户先回答「我是谁」，工程配置不再是第一印象。RBAC 本就在服务端（角色绑定 Key + policy.py 裁定），客户端不复制 |
| 十 | 多模型/推理模式选择 | **采纳（诚实映射）** | 智能体页新增会话模式与深度：模式=**服务端真实存在的角色面**（学习/临床辅助/科研→role 请求，服务端裁定上限，越级 403）；深度=**真实 API 参数** `max_steps`（快速 3/标准 5/深研 8，服务端钳制 1..12）。**改进点**：不虚构后端不存在的「模型选择」档位——多模型路由是服务端 `llm/providers` 的职责，客户端伪装可选模型=对用户撒谎 |
| 十一 | WorkManager/Room/Crashlytics/Analytics | **部分暂缓** | WorkManager 内容包同步：协议已就绪（manifest sha256+确定性 zip），下载+校验+原子切换排 Phase 4，本轮不做半成品。Room 见 #四。**Crashlytics/Firebase Analytics 改进式否决**：医疗敏感场景默认接 Google 遥测在合规（大陆无 GMS/数据出境）与信任上都不成立——路线图为自托管崩溃收集（如 Sentry self-hosted）+ **匿名、可选、明示**的使用统计；本机审计日志（#七）已覆盖「无法迭代」担忧的核心（哪些问答/检索在发生、核验通过率如何） |
| 十二 | ViewModel Factory / Hilt；网络层现代化 | **勘误+维持** | 评审所虑「`viewModel()` 无 Factory 致注入困难」不成立：全部调用点都是 `viewModel { XxxViewModel(container) }`（CreationExtras Factory lambda，官方推荐）。Hilt 维持「模块化拆分时引入」（§3.3）。网络层评审开的四味药里 Retrofit/OkHttp/**Kotlin Serialization**/**Flow** 本仓库已全在用；NetworkBoundResource 是 offline-first 缓存模式，与本项目「推理类请求绝不本地回退」的安全语义冲突，不采纳 |
| 十三 | 国际化 | **采纳（起步）** | 底部导航与本轮重写的首页/智能体/设置页字符串全部迁入 `values/strings.xml`，并新增 `values-en/` 全量英文资源；检索/辨证/条文页的存量硬编码属机械性迁移，列入路线图逐屏清理。语料文本（条文/方剂）**不**翻译——它们是证据本体，只做简繁显示层转换 |

### 7.2 本轮新增代码地图

```text
core/model/AgentPresentation.kt   trace 人类可读化 / 证据等级映射 / 今日条文选取（纯函数，JVM 测试）
core/audit/AuditLog.kt            本机审计日志（JSONL，纯 JVM，容量裁剪，故障静默）
core/settings/SecureTokenStore.kt Keystore 加密令牌存储（含明文降级明示）
core/settings/SettingsRepository  令牌迁移 + agentMode/agentDepth 偏好
data/HermesRepository             agent(roleOverride, maxSteps) + 审计埋点
ui/agent/AgentScreen              模式/深度选择 · 等待卡 · Evidence Card · 执行过程折叠区
ui/home/HomeScreen                产品化首屏 + 今日条文（并修复 refresh 守卫缺陷：旧 if(loading)return 配合初始 loading=true 使首次刷新永不执行）
ui/settings/SettingsScreen        身份前置 · 审计查看/清除 · 令牌安全状态
test/…/AgentPresentationTest.kt   trace/等级/今日条文单测
test/…/AuditLogTest.kt            审计日志单测（含裁剪与坏行容错）
```

### 7.3 验收注记

- 后端零改动：v1 契约与 523 项 Python 测试不受影响；
- 新增 JVM 单测均为纯 Kotlin（无 Android 依赖），`./gradlew :app:testDebugUnitTest` 运行；
- 本轮改动在无 Android SDK 环境下编写，未经本地编译验证——合入后请以
  `./gradlew :app:assembleDebug :app:testDebugUnitTest` 复核（依赖新增仅
  `androidx.security:security-crypto:1.1.0-alpha06`）。
