plugins {
  id("library-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
  }
}
