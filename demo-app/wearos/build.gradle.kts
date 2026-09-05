plugins {
  id("module-conventions")
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
}

android {
  namespace = "org.maplibre.compose.demoapp.wear"

  defaultConfig {
    applicationId = "org.maplibre.compose.demoapp.wear"
    minSdk = libs.versions.android.wearMinSdk.get().toInt()
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    // Other dependencies also ship x86 libraries; FFI does not support that ABI.
    ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    versionCode = 1
    versionName = project.version.toString()
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      // packageRelease needs a signing config. The debug key is enough for
      // the CI artifact; this app is not uploaded to Play.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getAndroidJvmTarget() }
}

// opengl or vulkan: the backend of the runtime artifact the APK packages.
val androidBackend = providers.gradleProperty("maplibre.android.backend").getOrElse("opengl")

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.wear.compose.foundation)
  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.jetbrains.compose.material3)

  runtimeOnly(project(":lib:maplibre-compose-runtime-$androidBackend-android"))
}
