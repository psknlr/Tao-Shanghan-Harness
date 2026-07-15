plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.impfai.hermes"
    compileSdk = 35

    defaultConfig {
        // 研發者：醫哲未來人工智能研究院（IMPF-AI）
        applicationId = "org.impfai.hermes"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    // standard：知識閱讀 + 服務端接入
    // vip     ：全量傷寒論知識庫/Skill 內置 + BYOK 直連大模型（密鑰僅存本機）
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "VIP", "false")
        }
        create("vip") {
            dimension = "edition"
            applicationIdSuffix = ".vip"
            versionNameSuffix = "-vip"
            buildConfigField("boolean", "VIP", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 暫用 debug 簽名使 release 可安裝驗證（審查發現 #14）；
            // 正式發佈前必須替換為 IMPF-AI 的發佈簽名（keystore 不入庫）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// 離線語料唯一真源是 backend/data/shanghan —— 構建期複製進 assets，
// 不在倉庫裡提交第二份拷貝（防止 Python/Android 兩份語料漂移）。
val corpusAssets = layout.buildDirectory.dir("generated/corpusAssets")
val copyCorpusAssets = tasks.register<Copy>("copyCorpusAssets") {
    from(rootProject.file("../backend/data/shanghan/clauses/clauses.jsonl"))
    from(rootProject.file("../backend/data/shanghan/rules_formula/formula_pattern_rules.jsonl"))
    into(corpusAssets.map { it.dir("shanghan") })
}
android.sourceSets.getByName("main").assets.srcDir(corpusAssets)
tasks.named("preBuild") { dependsOn(copyCorpusAssets) }

// VIP 資產包：全量規則庫（注家/異文/關係/初始/六經/鑒別/誤治/治法）
// + 139 個 Skill + 語料 manifest —— 僅 vip flavor 打入 APK
val vipAssets = layout.buildDirectory.dir("generated/vipAssets")
val copyVipAssets = tasks.register<Copy>("copyVipAssets") {
    val base = rootProject.file("../backend/data/shanghan")
    into(vipAssets)
    into("shanghan") {
        from(base.resolve("relations/clause_relations.jsonl"))
        from(base.resolve("rules_commentary/commentary_rules.jsonl"))
        from(base.resolve("rules_variant/variant_rules.jsonl"))
        from(base.resolve("rules_initial/initial_rules.jsonl"))
        from(base.resolve("rules_six_channel/six_channel_rules.jsonl"))
        from(base.resolve("rules_differential/differential_rules.jsonl"))
        from(base.resolve("rules_mistreatment/mistreatment_rules.jsonl"))
        from(base.resolve("rules_therapy/therapy_rules.jsonl"))
        from(base.resolve("manifest/corpus_manifest.json"))
    }
    into("skills") {
        from(rootProject.file("../backend/data/skills/shanghanlun"))
    }
}
android.sourceSets.getByName("vip").assets.srcDir(vipAssets)
tasks.named("preBuild") { dependsOn(copyVipAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
