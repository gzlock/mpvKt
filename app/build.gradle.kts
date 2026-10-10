import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.FilterConfiguration
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
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

  defaultConfig {
    applicationId = "com.gzlock.mpvkt"
    minSdk = 24
    targetSdk = 37
    versionCode = 13
    versionName = "0.1.7"

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
      isShrinkResources = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    create("preview") {
      initWith(getByName("release"))

      signingConfig = signingConfigs["debug"]
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"
    }
    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
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
  composeCompiler {
    includeSourceInformation = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
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
  androidResources {
    generateLocaleConfig = true
  }
  buildToolsVersion = "37.0.0"
}

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

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
  implementation(libs.jcifs)
  implementation(libs.smbj)
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
    freeCompilerArgs.addAll("-Xwhen-guards", "-Xcontext-parameters")
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
