package org.impfai.hermes

import android.app.Application
import java.io.File
import org.impfai.hermes.core.audit.AuditLog
import org.impfai.hermes.core.network.ApiClientFactory
import org.impfai.hermes.core.settings.SettingsRepository
import org.impfai.hermes.data.HermesRepository
import org.impfai.hermes.engine.LocalClauseStore

/**
 * 手動依賴容器（單模塊 Phase 2/3 規模下比 Hilt 更少構建面；
 * 模塊化拆分時再引入 Hilt——見 docs/ANDROID.md 對原方案的修改）。
 */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val localStore = LocalClauseStore(app)
    val apiFactory = ApiClientFactory()
    val auditLog = AuditLog(File(app.filesDir, "audit"))
    val repo = HermesRepository(settings, localStore, apiFactory, auditLog)
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
