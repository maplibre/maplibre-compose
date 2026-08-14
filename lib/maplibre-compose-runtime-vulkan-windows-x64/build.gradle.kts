plugins {
  id("module-conventions")
  `java-library`
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (Vulkan, Windows x64)"
    description =
      "MapLibre Native and LWJGL native libraries for running MapLibre Compose " +
        "on Windows x64 with the Vulkan backend."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

dependencies {
  runtimeOnly(project(":lib:maplibre-compose-windows"))

  DesktopHostPlatform.WindowsX64.runtimeDependencies(
      backend = DesktopHostPlatform.RenderBackend.VULKAN,
      ffiVersion = libs.versions.maplibre.nativeFfi.get(),
      lwjglVersion = libs.versions.lwjgl.get(),
    )
    .forEach { runtimeOnly(it) }
}
