// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
  dependencies {
    // AGP 9.0 has a runtime dependency on KGP 2.2.10 by default.
    // The project uses Kotlin 2.4.21 (Compose compiler & Serialization plugins),
    // so explicitly upgrade KGP to match. See:
    // https://developer.android.com/build/releases/agp-9-0-0-release-notes#kgp-dependency
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  }
}
plugins {
  alias(libs.plugins.android.application) apply false
}
