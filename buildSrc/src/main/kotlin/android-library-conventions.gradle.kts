import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.android.lint")
}

kotlin {
  @Suppress("UnstableApiUsage")
  android {
    minSdk = catalogVersionInt("android-minSdk")
    compileSdk = catalogVersionInt("android-compileSdk")

    // https://youtrack.jetbrains.com/issue/CMP-8232
    experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true

    withHostTestBuilder {}.configure {}
    withDeviceTestBuilder { sourceSetTreeName = "test" }
      .configure {
        animationsDisabled = true
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions {
          // Set the JVM target on each compilation, because the Android library DSL exposes no
          // property for it and a compilation otherwise inherits the toolchain's Java 25. Still
          // required as of AGP 9.1.1. https://issuetracker.google.com/issues/379315244
          jvmTarget = project.getAndroidJvmTarget()
        }
      }
    }
  }
}

// `-Pmaplibre.android.abis=` keeps the other JNI ABIs out of device-test APKs. Unset, every ABI
// still ships: this is packaging for the test APK, not the published AAR.
val deviceTestJniExcludes =
  providers
    .gradleProperty("maplibre.android.abis")
    .map { keep ->
      val abis = keep.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
      listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        .filterNot { it in abis }
        .map { "lib/$it/**" }
    }
    .orElse(emptyList())

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
  onVariants { variant ->
    variant.deviceTests.values.forEach { deviceTest ->
      deviceTest.packaging.jniLibs.excludes.addAll(deviceTestJniExcludes)
    }
  }
}
