import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

/**
 * 交付签名（自签名）。
 *
 * 密码与别名放在 `keystore/keystore.properties`，与 keystore 一起进仓库：
 * 分发包必须能被同一把钥匙重新签名，否则「更新覆盖安装」会因签名不一致失败。
 * 这不是 Google Play 上架密钥 —— 上架前必须换成正式密钥并移出仓库。
 */
val releaseKeystoreProps = Properties().apply {
  val file = rootProject.file("keystore/keystore.properties")
  if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
}
val hasReleaseKeystore = releaseKeystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
  namespace = "studio.morrow.android"
  compileSdk = 36

  signingConfigs {
    // 主目录 .android 在本机不可写，debug keystore 固定放在工程内，构建可复现
    getByName("debug") {
      storeFile = file("../keystore/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    if (hasReleaseKeystore) {
      create("release") {
        storeFile = rootProject.file(releaseKeystoreProps.getProperty("storeFile"))
        storePassword = releaseKeystoreProps.getProperty("storePassword")
        keyAlias = releaseKeystoreProps.getProperty("keyAlias")
        keyPassword = releaseKeystoreProps.getProperty("keyPassword")
      }
    }
  }

  defaultConfig {
    applicationId = "studio.morrow.android"
    // Android 8.0 起：满足计划文档「Android 8–16」的下限；edge-to-edge 与 gesture insets 在 26+ 行为可控
    minSdk = 26
    targetSdk = 35
    // 与桌面版对齐：0.2.0。版本号只在这里与 CHANGELOG 里出现。
    versionCode = 2
    versionName = "0.2.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables.useSupportLibrary = true
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      isMinifyEnabled = false
      // 用户主目录 .android 不可写（本机），改用工程内 keystore，保证可复现构建
      signingConfig = signingConfigs.getByName("debug")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // 有交付签名配置才产出 release；缺失时保持旧行为（不产出），
      // 并让 Gradle 的 assembleRelease 明确失败，而不是生成一个未签名包。
      if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources.excludes += setOf(
      "/META-INF/{AL2.0,LGPL2.1}",
      "/META-INF/DEPENDENCIES",
      "META-INF/LICENSE*",
      "META-INF/NOTICE*"
    )
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }

  lint {
    warningsAsErrors = false
    abortOnError = true
    checkDependencies = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.compose.ui.test.junit4)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
