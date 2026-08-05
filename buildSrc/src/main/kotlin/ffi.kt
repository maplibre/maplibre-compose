/**
 * The desktop platforms MapLibre Native FFI publishes a native runtime for.
 *
 * The FFI ships one artifact per render backend, each carrying per-platform natives under a
 * classifier; an application picks exactly one.
 */
enum class DesktopHostPlatform(
  private val os: String,
  private val arch: String,
  val renderBackend: RenderBackend,
) {
  LinuxX64("linux", "x64", RenderBackend.VULKAN),
  LinuxArm64("linux", "arm64", RenderBackend.VULKAN),
  MacosArm64("macos", "arm64", RenderBackend.METAL),
  WindowsX64("windows", "x64", RenderBackend.VULKAN),
  WindowsArm64("windows", "arm64", RenderBackend.VULKAN);

  enum class RenderBackend(val artifactInfix: String) {
    METAL("metal"),
    VULKAN("vulkan"),
  }

  /** Classifier of the natives jar for this platform, e.g. `natives-linux-x64`. */
  val nativesClassifier: String
    get() = "natives-$os-$arch"

  /**
   * Classifier of the LWJGL natives jar for this platform, e.g. `natives-linux`. LWJGL omits the
   * architecture entirely for x64 rather than spelling it `-x64`.
   */
  val lwjglNativesClassifier: String
    get() = if (arch == "x64") "natives-$os" else "natives-$os-$arch"

  /** Module carrying this platform's render backend, without a version. */
  val runtimeModule: String
    get() = "org.maplibre.nativeffi:maplibre-native-ffi-runtime-${renderBackend.artifactInfix}-jvm"

  /**
   * Dependency notation for the compose-glfw runtime this platform needs. Its backend names the
   * Compose consumer, a different axis from [renderBackend]; the two only coincide on macOS.
   */
  fun composeGlfwRuntimeDependency(version: String): String =
    "dev.sargunv:compose-glfw-$composeGlfwBackend-$os-$arch:$version"

  private val composeGlfwBackend: String
    get() =
      when (os) {
        "linux" -> "opengl"
        "macos" -> "metal"
        "windows" -> "direct3d"
        else -> error("compose-glfw publishes no runtime for operating system '$os'")
      }

  /** Full dependency notation for this platform's native runtime. */
  fun runtimeDependency(version: String): String = "$runtimeModule:$version:$nativesClassifier"

  /**
   * Dependency notation for the runtime the headless GPU tests need. Always Vulkan, even on macOS:
   * `HeadlessVulkanMapHost` has no Metal equivalent, and there it runs on MoltenVK, which needs
   * `lwjgl-vulkan`'s natives on the test classpath.
   */
  fun testRuntimeDependency(version: String): String =
    "org.maplibre.nativeffi:maplibre-native-ffi-runtime-" +
      "${RenderBackend.VULKAN.artifactInfix}-jvm:$version:$nativesClassifier"

  companion object {
    /** The platform this build is running on; throws rather than guessing at an unknown host. */
    fun current(): DesktopHostPlatform {
      val osName = System.getProperty("os.name").lowercase()
      val archName = System.getProperty("os.arch").lowercase()

      val os =
        when {
          osName.startsWith("mac") -> "macos"
          osName.startsWith("windows") -> "windows"
          osName.startsWith("linux") -> "linux"
          else -> error("No MapLibre Native FFI runtime for operating system '$osName'")
        }

      val arch =
        when (archName) {
          "x86_64",
          "amd64" -> "x64"
          "aarch64",
          "arm64" -> "arm64"
          else -> error("No MapLibre Native FFI runtime for architecture '$archName'")
        }

      return entries.firstOrNull { it.os == os && it.arch == arch }
        ?: error(
          "No MapLibre Native FFI runtime for $os-$arch. Published platforms: " +
            entries.joinToString { "${it.os}-${it.arch}" }
        )
    }
  }
}
