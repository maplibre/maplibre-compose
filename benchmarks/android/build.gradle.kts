plugins {
  id("module-conventions")
  id(libs.plugins.android.application.get().pluginId)
}

android {
  namespace = "org.maplibre.compose.benchmark.classic"

  defaultConfig {
    applicationId = "org.maplibre.compose.benchmark.classic"
    minSdk = libs.versions.android.minSdk.get().toInt()
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    versionCode = 1
    versionName = project.version.toString()
  }
  sourceSets["main"].assets.srcDir("../build/fixtures")
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
  implementation(project(":benchmarks:core"))
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.activity)
  implementation(
    if (androidBackend == "vulkan") libs.maplibre.androidVulkan else libs.maplibre.androidOpenGl
  )
}
