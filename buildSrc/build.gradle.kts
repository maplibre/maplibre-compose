plugins { `kotlin-dsl` }

repositories {
  mavenCentral()
  gradlePluginPortal()
  google()
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21 } }

dependencies {
  pluginImplementation(libs.plugins.android.application)
  pluginImplementation(libs.plugins.android.library)
  pluginImplementation(libs.plugins.android.lint)
  pluginImplementation(libs.plugins.compose)
  pluginImplementation(libs.plugins.dokka)
  pluginImplementation(libs.plugins.kotlin.jvm)
  pluginImplementation(libs.plugins.kotlin.multiplatform)
  pluginImplementation(libs.plugins.kotlin.serialization)
  pluginImplementation(libs.plugins.kotlin.composeCompiler)
  pluginImplementation(libs.plugins.mavenPublish)
}

fun DependencyHandlerScope.pluginImplementation(notation: Provider<PluginDependency>) {
  val id = notation.get().pluginId
  val version = notation.get().version
  implementation("$id:$id.gradle.plugin:$version")
}
