import org.jetbrains.kotlin.gradle.ExperimentalJsTestDsl

plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
}

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  android { namespace = "org.maplibre.compose.benchmark" }
  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }
  iosArm64()
  iosSimulatorArm64()
  macosArm64()
  js {
    useEsModules()
    browser {
      @OptIn(ExperimentalJsTestDsl::class)
      test {
        chromium()
        firefox()
        webkit()
      }
    }
  }
  sourceSets {
    jsTest.dependencies { implementation(npm("mocha", libs.versions.mocha.get())) }
    androidDeviceTest.dependencies { implementation(libs.androidx.test.runner) }
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

stageBrowserTestRunnerResources()
