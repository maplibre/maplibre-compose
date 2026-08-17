plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.android.lint")
}

kotlin {
  @Suppress("UnstableApiUsage")
  android {
    minSdk = catalogVersionInt("android-minSdk")
    // Compose 1.12 requires API 37; that platform ships as android-37.0.
    compileSdk {
      version = release(catalogVersionInt("android-compileSdk")) { minorApiLevel = 0 }
    }

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
          // required as of AGP 9.1.0. https://issuetracker.google.com/issues/379315244
          jvmTarget = project.getAndroidJvmTarget()
        }
      }
    }
  }
}
