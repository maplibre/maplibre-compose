plugins {
  id("module-conventions")
  `java-library`
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (OpenGL, Windows arm64)"
    description =
      "MapLibre Native and LWJGL native libraries for running MapLibre Compose " +
        "on Windows arm64 with the OpenGL backend."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

dependencies {
  runtimeOnly(project(":lib:location-runtime-windows"))

  DesktopHostPlatform.WindowsArm64.runtimeDependencies(
      backend = DesktopHostPlatform.RenderBackend.OPENGL,
      ffiVersion = libs.versions.maplibre.nativeFfi.get(),
      lwjglVersion = libs.versions.lwjgl.get(),
    )
    .forEach { runtimeOnly(it) }
}
