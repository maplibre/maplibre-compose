plugins {
  id("module-conventions")
  `java-library`
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (Metal, macOS arm64)"
    description =
      "MapLibre Native and LWJGL native libraries for running MapLibre Compose " +
        "on macOS arm64 with the Metal backend."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

dependencies {
  runtimeOnly(project(":lib:maplibre-compose-macos"))

  DesktopHostPlatform.MacosArm64.runtimeDependencies(
      backend = DesktopHostPlatform.RenderBackend.METAL,
      ffiVersion = libs.versions.maplibre.nativeFfi.get(),
      lwjglVersion = libs.versions.lwjgl.get(),
    )
    .forEach { runtimeOnly(it) }
}
