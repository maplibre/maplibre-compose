plugins {
  id("module-conventions")
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
}

android {
  namespace = "org.maplibre.compose.demoapp.auto"
  defaultConfig {
    applicationId = "org.maplibre.compose.demoapp.auto"
    minSdk = libs.versions.android.minSdk.get().toInt()
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    versionCode = 1
    versionName = project.version.toString()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
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

val androidBackend = providers.gradleProperty("maplibre.android.backend").getOrElse("opengl")

dependencies {
  implementation(project(":demo-app:common"))
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.appProjected)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.lifecycle.runtime)
  androidTestImplementation(kotlin("test-junit"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.car.appTesting)
  runtimeOnly(project(":lib:maplibre-compose-runtime-$androidBackend-android"))
}
