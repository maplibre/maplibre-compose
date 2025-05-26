plugins { `kotlin-dsl` }

repositories {
  mavenCentral()
  gradlePluginPortal()
  google()
}

kotlin { jvmToolchain(21) }

dependencies {
  pluginImplementation(libs.plugins.android.application)
  pluginImplementation(libs.plugins.android.library)
  pluginImplementation(libs.plugins.compose)
  pluginImplementation(libs.plugins.dokka)
  pluginImplementation(libs.plugins.jgitver)
  pluginImplementation(libs.plugins.kotlin.multiplatform)
  pluginImplementation(libs.plugins.kotlin.serialization)
  pluginImplementation(libs.plugins.kotlin.cocoapods)
  pluginImplementation(libs.plugins.kotlin.composeCompiler)
  pluginImplementation(libs.plugins.mkdocs)
  pluginImplementation(libs.plugins.mavenPublish)
  pluginImplementation(libs.plugins.spotless)

  // noinspection GradleDynamicVersion: extra for jgitver imports
  compileOnly("fr.brouillard.oss:jgitver:+")
}

fun DependencyHandlerScope.pluginImplementation(notation: Provider<PluginDependency>) {
  val id = notation.get().pluginId
  val version = notation.get().version
  implementation("$id:$id.gradle.plugin:$version")
}
