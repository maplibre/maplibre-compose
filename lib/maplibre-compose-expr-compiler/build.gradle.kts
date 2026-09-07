plugins { id(libs.plugins.kotlin.jvm.get().pluginId) }

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { allWarningsAsErrors = false }
}

dependencies {
  compileOnly(libs.kotlin.compilerEmbeddable)
  testImplementation(kotlin("test"))
}
