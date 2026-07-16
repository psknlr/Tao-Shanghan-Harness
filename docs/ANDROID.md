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

## 5.5 v1.1：UI 美化 + VIP 版本（standard / vip 双 flavor）

**UI**：M3 形状体系（卡片 16dp 圆角）、古籍正文衬线字族、墨绿渐变 Hero
首页头部、注家紫 tertiary 色、暗色方案完善。

**VIP flavor**（`applicationId org.impfai.hermes.vip`，可与 standard 并存安装）：

1. **全量知识库进包**（构建期从 `backend/data` 同源复制，约 9.4MB 原始 /
   APK 内压缩）：注家规则 1.9MB（九注家逐条对齐）、异文规则、条文关系
   1.2MB、初始归纳规则 1.5MB、六经/鉴别/误治/治法规则、语料 manifest、
   **139 个 Skill**（SKILL.md + rules + examples，418 文件）。
   离线条文详情升级为全息：异文/注家/关系/归纳规则不再依赖服务端。
2. **Skill 库浏览器**：分类筛选 + SKILL.md 阅读（首页 VIP 卡片进入）。
3. **直连大模型（BYOK）**：设置页配置服务商（Anthropic / OpenAI 兼容
   端点）+ API Key + 可选 Base URL/模型名；智能体页双通道切换。
   直连流水线：**本地 BM25 取证 → 大模型限定在证据内作答 →
   本地 CitationGuard 核验引用**（verified / outside_evidence /
   unsupported 三级，√/△/× 徽章照旧）。

**VIP 安全边界（对原方案第十节的修改声明）**：原方案"不建议 Android
直接调用外部大模型"在 VIP 版按需求方要求放宽为 BYOK 模式，边界如实声明：
- 用户自带 Key 仅存本机 DataStore（`allowBackup=false`，不入云备份），
  只发送至用户配置的模型端点，绝不发送到 Hermes 服务端或第三方；
- 直连回答的引用核验为**本地弱核验**（正则抽取 + 本地语料比对），弱于
  服务端全链路证据闸门——回答卡片和设置页均如实标注；
- 系统提示词强制"仅基于给定证据、不得给剂量建议/下诊断"，安全声明常驻；
- standard 版完全不含直连入口，两版可并存对照。

## 5.6 v1.2：真机问题 debug + VIP 纯端侧化 + Robolectric 冒烟防线

用户真机反馈（检索无响应、疑似闪退、首页数据为 0）经 Robolectric
（JVM 真实 Compose + 真实 APK 资产）复现定位，全部修复：

| 根因 | 修复 |
|---|---|
| **首页加载死锁**：初始 `loading=true` 撞上 v1.1 加的在途去重守卫，`refresh()` 永远被自己挡住 → 首页永远"加载中/本地语料 0 条" | 初始 `loading=false`（SmokeUiTest 抓获） |
| **键盘搜索键未接线**：输入后按输入法"搜索"无反应，只有点小图标才检索 | 全部输入框接 `ImeAction.Search/Send` + KeyboardActions |
| **首页→检索参数过期**：launchSingleTop 复用 ViewModel，init 时 SavedStateHandle 是旧值 | UI 层将 NavBackStackEntry 实参喂给 `applyExternalArgs` |
| **协程取消被吞**：`safeCall/withApi/DirectLlm` 的 catch(Exception) 捕获 CancellationException，被取消的旧请求继续写 UI 状态 | 三处 rethrow |
| **默认连 10.0.2.2 + 10s 连接超时**：真机上每次检索先等 10 秒失败才回退本地，感知为"检索没实现" | VIP 默认 `offlineOnly=true`（纯端侧，不发任何远端请求）；连接超时降至 4s |
| 检索列表 key 重复可崩 | itemsIndexed 带序号 key |
| 语料单行损坏会拖垮整库加载 | 逐行容错跳过 |

**VIP v1.2 纯端侧形态**（用户要求）：默认不连接任何 Hermes 服务端；
方证匹配改为端侧确定性计算（`doctor.py` FormulaMatcher 精确移植：
核心证 ×2.0 / 兼证 ×1.0 / 近似 Jaccard≥0.6 +1.5 / 提纲证 +1.0 /
反证 −2.5 / 证据厚度 +min(0.3,0.05n)）；直连大模型 OpenAI 兼容端点
默认 **Poe（https://api.poe.com）+ Claude-Sonnet-4.6**；服务端接入降级为
可选项。Skill 索引改为构建期生成清单（`AssetManager.list` 对子目录行为
跨环境不一致，且下划线开头文件会被 AAPT 资产打包忽略）。

**测试防线**：Robolectric 冒烟（启动/五页导航/离线检索开条文/方剂库/
Skill 库）+ 端侧引擎金标准（681 条加载、检索首位与服务端一致
[SHL_SONGBEN_0136]、简体输入等价、条文号直查、麻黄汤匹配金标准、
VIP 全息断言、139 Skill 断言）——两 flavor 各 21 项全绿。

## 5.7 v1.3：全量古籍内置 + 平台功能端侧化 + 论文 DOCX 导出

针对用户实测反馈的六项逐一落地：

1. **毫秒级检索**：App 启动即后台预热全部索引（条文 BM25 + VIP 规则库 +
   古籍编目），检索路径零冷启动；Robolectric 断言 20 次均值 <50ms。
2. **全量古籍预导入**：官方源 `book-20180111.7z`（sha256 与
   `config.LIBRARY_SHA256` 一致）经 backend `library.fetch()` 原始流水线
   解压/审查/编目/字符索引，803 部 / 843 单元 / 317MB 打入 VIP-full APK
   （`assets/library/`）。端侧 `LibraryStore` 移植 `corpus/library.py`：
   编目检索、稀字倒排剪枝全文检索、章节阅读（同算法同口径）。
   库包不入 git（`android/tools/prepare_library.md` 记录再生成流程）。
3. **方剂库排序修复**：目录按支持条文数降序（桂枝汤 33 条居首），并以
   语料方剂块补全规则外方名；金标准测试
   `formula_catalog_guizhitang_first_and_filterable` 守护。
4. **直连大模型（Poe）**：MockWebServer 合同测试证明客户端请求/解析
   正确（`/v1/chat/completions`、Bearer 头、模型名、choices 解析）；
   设置页新增「测试模型连接」一键诊断；错误信息带 HTTP 码与响应摘要，
   网络不可达时明确提示"需可达的 OpenAI 兼容中转端点"（大陆网络直连
   api.poe.com 不可达属网络层问题，非客户端缺陷）。
5. **平台功能端侧化**（数据=VIP 规则库，算法=原模块确定性子集）：
   六经教学（SCR 规则：纲领/总说/亚型/主方/误治/禁忌/核心条文）、
   方证鉴别（DR 规则：对比表/鉴别眼目/组成差异/支持条文）、
   误治传变（MTR 规则：路径/表现/救逆方）、科研挖掘（症状/药物频次、
   药对共现、六经分布，端侧计数毫秒级）、溯源工作台简版（逐字引文核验 +
   Dice 相似定位 + 术语谱系）、条文页「AI 解读」一键带上下文进入智能体。
6. **论文**：图表（症状/药物频次）App 内预览 + 随 DOCX 导出；DOCX 为
   纯手写 OOXML（零第三方依赖，含表格与内嵌 PNG 图表），经 SAF 保存到
   用户选择的位置；可选直连大模型润色摘要（仅用给定统计数据）。

诚实边界：深度研究循环、学术计量网络、注家谱系溯源、论文全模板族仍属
服务端能力；端侧版均在界面上标注"简版/需服务端"。

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
