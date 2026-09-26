import org.jetbrains.kotlin.gradle.ExperimentalJsTestDsl

plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
}

val benchmarkBuildInfo =
  tasks.register<GenerateBenchmarkBuildInfo>("generateBenchmarkBuildInfo") {
    repository.set(rootProject.layout.projectDirectory)
    dependencyVersions.putAll(
      mapOf(
        "classic_android" to libs.versions.maplibre.android.get(),
        "classic_ios" to libs.versions.maplibre.ios.get(),
        "native_ffi" to libs.versions.maplibre.nativeFfi.get(),
        "gl_js" to libs.versions.maplibre.js.get(),
      )
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/benchmarkBuildInfo"))
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
    commonMain { kotlin.srcDir(benchmarkBuildInfo.flatMap { it.outputDirectory }) }
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
