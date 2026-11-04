import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.tasks.Copy
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.room)
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.about.libraries)
  alias(libs.plugins.kotlinx.serialization)
}

if (gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("detekt") }) {
  apply(plugin = "io.gitlab.arturbosch.detekt")
}

extensions.configure<ApplicationExtension> {
  namespace = "live.mehiz.mpvkt"
  compileSdk = 37
  compileSdkMinor = 2

  // 1. 读取本地 local.properties 文件
  val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
      load(file.inputStream())
    }
  }

  // 2. 分别获取 Debug 和 Release 的配置项（带 Fallback 官方测试 ID）
  val debugAppId = localProperties.getProperty("debug_admob_app_id")
    ?: System.getenv("DEBUG_ADMOB_APP_ID")
    ?: "ca-app-pub-3940256099942544~3347511713"

  val releaseAppId = localProperties.getProperty("admob_app_id")
    ?: System.getenv("ADMOB_APP_ID")
    ?: "ca-app-pub-3940256099942544~3347511713"

  val debugAdUnitId = localProperties.getProperty("debug_admob_smb_file_browser_ad_unit_id")
    ?: System.getenv("DEBUG_ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID")
    ?: "ca-app-pub-3940256099942544/2247696110"

  val releaseAdUnitId = localProperties.getProperty("admob_smb_file_browser_ad_unit_id")
    ?: System.getenv("ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID")
    ?: "ca-app-pub-3940256099942544/2247696110"

  defaultConfig {
    applicationId = "com.gzlock.mpvkt"
    minSdk = 24
    targetSdk = 37
    versionCode = 17
    versionName = "0.1.11"

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())
  }
  splits {
    abi {
      isEnable = true
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
      isUniversalApk = true
    }
  }

  buildTypes {
    named("release") {
      isMinifyEnabled = true
      //noinspection NotShrinkingResources
      isShrinkResources = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )

      // 注入 Release 正式 App ID 与 广告单元 ID
      manifestPlaceholders["ADMOB_APP_ID"] = releaseAppId
      buildConfigField("String", "ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID", "\"$releaseAdUnitId\"")
    }
    create("preview") {
      initWith(getByName("release"))

      signingConfig = signingConfigs["debug"]
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"

      // Preview 建议使用测试 ID 或专属的 Preview ID，避免正式包 ID 校验失败
      manifestPlaceholders["ADMOB_APP_ID"] = debugAppId
      buildConfigField("String", "ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID", "\"$debugAdUnitId\"")
    }
    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"

      // 注入 Debug 专用 App ID 与 广告单元 ID
      manifestPlaceholders["ADMOB_APP_ID"] = debugAppId
      buildConfigField("String", "ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID", "\"$debugAdUnitId\"")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  androidResources {
    generateLocaleConfig = true
  }
  buildToolsVersion = "37.0.0"
}

composeCompiler {
  includeSourceInformation = true
}

val abiCodes = mapOf(
  "armeabi-v7a" to 1,
  "arm64-v8a" to 2,
  "x86" to 3,
  "x86_64" to 4,
)
androidComponents {
  onVariants { variant ->
    variant.outputs.forEach { output ->
      val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
      output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + (abiCodes[abi] ?: 0))
    }
  }
}

// Package the project attribution and Apache 2.0 license in every APK.
val licenseAssetsDirectory = layout.buildDirectory.dir("generated/licenseAssets").get().asFile
val copyLicenseAssets = tasks.register<Copy>("copyLicenseAssets") {
  from(rootProject.file("LICENSE"))
  from(rootProject.file("NOTICE"))
  into(licenseAssetsDirectory)
}
android.sourceSets["main"].assets.directories.add(licenseAssetsDirectory.absolutePath)
tasks.named("preBuild").configure { dependsOn(copyLicenseAssets) }

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  testImplementation(libs.junit)

  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.constraintlayout)
  implementation(libs.androidx.material3.icons.extended)
  implementation(libs.androidx.compose.animation.graphics)
  implementation(libs.material)
  implementation(libs.androidx.preferences.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.mediasession)
  implementation(libs.saveable)

  implementation(libs.mpv.lib)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)
  implementation(libs.bundles.about.libs)
  implementation(libs.simple.icons)
  implementation(libs.coil.compose)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
  implementation(libs.jcifs)
  implementation(libs.smbj)
  implementation(libs.gms.play.services.ads)
}

pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
  extensions.configure<DetektExtension> {
    parallel = true
    allRules = false
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
  }

  dependencies {
    add("detektPlugins", libs.detekt.rules.compose)
    add("detektPlugins", libs.detekt.formatter)
  }

  tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    setSource(files(project.projectDir))
    exclude("**/build/**")
    reports {
      html.required.set(true)
      md.required.set(true)
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

fun getCommitCount(): String = runCommand("git rev-list --count HEAD")
fun getCommitSha(): String = runCommand("git rev-parse --short HEAD")
fun runCommand(command: String): String = providers.exec {
  commandLine(command.split(' '))
}.standardOutput.asText.get().trim()

aboutLibraries {
  export {
    excludeFields.assign(listOf("generated"))
  }
}
