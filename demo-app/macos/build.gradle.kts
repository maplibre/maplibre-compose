plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

tasks.register<Sync>("packageDebugApp") {
  val link =
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>("linkDebugExecutableMacosArm64")
  val resources = tasks.named("macosArm64AggregateResources")
  dependsOn(link, resources)
  from(link.flatMap { it.outputFile }) {
    into("Contents/MacOS")
    rename { "MapLibreCompose" }
  }
  from(resources.map { it.outputs.files }) { into("Contents/Resources/compose-resources") }
  from("Info.plist") { into("Contents") }
  into(layout.buildDirectory.dir("MapLibreCompose.app"))
}

kotlin {
  macosArm64 {
    binaries.executable {
      baseName = "MapLibreCompose"
      entryPoint = "org.maplibre.compose.macosdemo.main"
    }
  }
  sourceSets.commonMain.dependencies {
    implementation(project(":demo-app:common"))
    implementation(project(":lib:maplibre-compose"))
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.ui)
  }
}
