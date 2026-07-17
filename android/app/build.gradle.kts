import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release 簽名（外部評審建議六）：密鑰材料一律不入庫——
// android/keystore.properties（.gitignore 排除）提供
//   storeFile=../impfai-release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// 存在即用正式簽名；缺失回退 debug 簽名並打印警告（保住「release
// 構建可本地安裝驗證」的工作流，但構建日誌明示該 APK 禁止分發）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = keystorePropsFile.takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "org.impfai.hermes"
    compileSdk = 35

    defaultConfig {
        // 研發者：醫哲未來人工智能研究院（IMPF-AI）
        applicationId = "org.impfai.hermes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
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
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: keystore.properties 未找到——release 以 debug 簽名構建，" +
                        "僅限本地安裝驗證，嚴禁對外分發；正式發佈請提供 " +
                        "android/keystore.properties（見 docs/ANDROID.md）")
                signingConfigs.getByName("debug")
            }
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
    // 令牌加密存儲（Android Keystore 主密鑰，見 SecureTokenStore）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
