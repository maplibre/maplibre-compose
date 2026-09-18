plugins {
  id("module-conventions")
  `java-library`
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (OpenGL, macOS arm64)"
    description =
      "MapLibre Native and LWJGL native libraries for running MapLibre Compose " +
        "on macOS arm64 with the OpenGL backend."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

dependencies {
  runtimeOnly(project(":lib:location-runtime-macos"))

  DesktopHostPlatform.MacosArm64.runtimeDependencies(
      backend = DesktopHostPlatform.RenderBackend.OPENGL,
      ffiVersion = libs.versions.maplibre.nativeFfi.get(),
      lwjglVersion = libs.versions.lwjgl.get(),
    )
    .forEach { runtimeOnly(it) }
}

val packageAngle =
  tasks.register<PackageAngle>("packageAngle") {
    angleVersion.set(libs.versions.angle.asProvider())
    sha256.set(libs.versions.angle.macosArm64Sha256)
    outputDirectory.set(layout.buildDirectory.dir("generated/angle-resources"))
  }

sourceSets.main { resources.srcDir(packageAngle) }
