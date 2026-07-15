package org.impfai.hermes

import android.app.Application
import org.impfai.hermes.core.network.ApiClientFactory
import org.impfai.hermes.core.settings.SettingsRepository
import org.impfai.hermes.data.HermesRepository
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.engine.SkillStore

/**
 * 手動依賴容器（單模塊 Phase 2/3 規模下比 Hilt 更少構建面；
 * 模塊化拆分時再引入 Hilt——見 docs/ANDROID.md 對原方案的修改）。
 */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val localStore = LocalClauseStore(app)
    val skillStore = SkillStore(app)
    val apiFactory = ApiClientFactory()
    val repo = HermesRepository(settings, localStore, apiFactory)
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
