import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.android.lint")
}

kotlin {
  @Suppress("UnstableApiUsage")
  androidLibrary {
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
          // TODO revisit this with AGP 8.11? https://issuetracker.google.com/issues/379315244
          if (this is KotlinJvmCompilerOptions) jvmTarget = project.getAndroidJvmTarget()
          else error("Unexpected compilation type: ${this::class}")
        }
      }
    }
  }
}
