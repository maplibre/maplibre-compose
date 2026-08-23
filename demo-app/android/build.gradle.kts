plugins {
  id("module-conventions")
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
}

android {
  namespace = "org.maplibre.compose.demoapp"

  defaultConfig {
    applicationId = "org.maplibre.compose.demoapp"
    minSdk = libs.versions.android.minSdk.get().toInt()
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = project.version.toString()
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  buildTypes { getByName("release") { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getAndroidJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.jetbrains.compose.ui.tooling)

  // The Android map renders with the backend this runtime carries; swap for
  // maplibre-compose-runtime-vulkan-android to run the Vulkan host.
  implementation(project(":lib:maplibre-compose-runtime-opengl-android"))
}
