# Tao-Shanghan-Harness

《伤寒论》证据分层智能体平台 **Shanghan-Hermes** 的 Android 工程化仓库。

研发者：**医哲未来人工智能研究院（IMPF-AI）**

```text
backend/    Shanghan-Hermes Python 平台（权威推理端 · 治理端 · 证据端）
            ├─ 59 路由 HTTP API + 新增 /api/v1 契约层（统一信封/错误码/
            │  领域清单/离线内容包协议）
            └─ 508 项测试全绿（stdlib-only，零第三方依赖）
android/    原生 Android 客户端（可信交互端 · 离线知识端）
            ├─ Kotlin + Jetpack Compose + Material 3
            ├─ 离线内置语料（681 条条文 + 113 方剂规则，构建期从
            │  backend/data 同源复制）与 BM25/文本规范化 Kotlin 移植
            └─ 首页 / 检索 / 条文阅读器 / 辨证 / 智能体 / 设置（API 接入）
docs/       ANDROID.md —— 架构决策、对原工程化方案的采纳与修改记录
```

## 快速开始

```bash
# 1. 启动后端
cd backend && python3 -m hermes_shanghan serve --host 0.0.0.0 --port 8765

# 2. 构建 Android（需 Android SDK）
cd android && ./gradlew :app:assembleDebug
# 模拟器内设置服务端地址：http://10.0.2.2:8765/
```

安全边界：模型供应商密钥只存在于服务端；Android 仅保存 Hermes 角色绑定
访问令牌；角色上限由服务端裁定；Release 构建禁止明文流量。

详见 [docs/ANDROID.md](docs/ANDROID.md)。
