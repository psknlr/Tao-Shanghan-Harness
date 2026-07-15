// Root build file: plugin versions only. Single :app module for Phase 2/3
// (知識閱讀 MVP)；包結構已按未來 core/domain/feature 模塊邊界劃分，
// 模塊化拆分在功能面擴大後進行（見 docs/ANDROID.md「對原方案的修改」）。
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
