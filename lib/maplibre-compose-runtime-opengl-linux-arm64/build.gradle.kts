plugins {
  id("module-conventions")
  `java-library`
}

// Local development only until the OpenGL Compose bridge ships; not published.

dependencies {
  runtimeOnly(project(":lib:location-runtime-linux"))

  DesktopHostPlatform.LinuxArm64.runtimeDependencies(
      backend = DesktopHostPlatform.RenderBackend.OPENGL,
      ffiVersion = libs.versions.maplibre.nativeFfi.get(),
      lwjglVersion = libs.versions.lwjgl.get(),
    )
    .forEach { runtimeOnly(it) }
}
