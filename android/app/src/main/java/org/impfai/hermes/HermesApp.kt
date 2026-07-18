package org.impfai.hermes

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.impfai.hermes.core.audit.AuditLog
import org.impfai.hermes.core.chat.ChatHistoryStore
import org.impfai.hermes.core.network.ApiClientFactory
import org.impfai.hermes.core.settings.SettingsRepository
import org.impfai.hermes.data.HermesRepository
import org.impfai.hermes.engine.AnnotationStore
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.engine.ReadingProgressStore
import org.impfai.hermes.engine.SkillStore

/**
 * 手動依賴容器（單模塊 Phase 2/3 規模下比 Hilt 更少構建面；
 * 模塊化拆分時再引入 Hilt——見 docs/ANDROID.md 對原方案的修改）。
 */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val localStore = LocalClauseStore(app)
    val skillStore = SkillStore(app)
    val libraryStore = LibraryStore(app)
    val annotationStore = AnnotationStore(app)
    val readingProgress = ReadingProgressStore(app)
    val apiFactory = ApiClientFactory()
    val auditLog = AuditLog(File(app.filesDir, "audit"))
    val chatHistory = ChatHistoryStore(File(app.filesDir, "chats"))
    val repo = HermesRepository(settings, localStore, apiFactory, auditLog,
        libraryStore, skillStore)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 啟動即預熱全部索引（後台 IO）：條文 BM25 + VIP 規則庫 + 古籍
        // 編目——首次檢索不再付出解析成本，穩定毫秒級響應
        container.appScope.launch {
            runCatching {
                container.localStore.ensureLoaded()
                container.localStore.formulaCatalog()
                container.localStore.sixChannelRules()
                container.libraryStore.prewarmSearch()
            }
        }
    }
}
