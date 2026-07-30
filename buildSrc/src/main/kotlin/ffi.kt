/**
 * The desktop platforms MapLibre Native FFI publishes a native runtime for.
 *
 * The FFI ships one artifact per render backend, each carrying per-platform natives under a
 * classifier. An application picks exactly one. Keeping the detection here means the demo, tests,
 * documentation, and downstream examples do not grow separate detectors that drift apart.
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
   * Classifier of the LWJGL natives jar for this platform, e.g. `natives-linux`.
   *
   * LWJGL names x64 differently from MapLibre Native FFI: it omits the architecture entirely rather
   * than spelling it `-x64`, so the two classifiers cannot be shared.
   */
  val lwjglNativesClassifier: String
    get() = if (arch == "x64") "natives-$os" else "natives-$os-$arch"

  /** Module carrying this platform's render backend, without a version. */
  val runtimeModule: String
    get() = "org.maplibre.nativeffi:maplibre-native-ffi-runtime-${renderBackend.artifactInfix}-jvm"

  /** Full dependency notation for this platform's native runtime. */
  fun runtimeDependency(version: String): String = "$runtimeModule:$version:$nativesClassifier"

  /**
   * Dependency notation for the runtime the headless GPU tests need.
   *
   * Always Vulkan, including on macOS where an application would ship Metal:
   * `HeadlessVulkanMapHost` creates a real Vulkan device with no window, and there is no Metal
   * equivalent of it. MapLibre has to render with the backend the host can bridge or backend
   * negotiation declines and every GPU-backed test quietly asserts against a map that never
   * rendered. What the tests cover — sessions, styles, layers, queries — is backend-independent, so
   * this costs nothing but the Metal-specific host bridge, which no test exercises anyway.
   *
   * On macOS the Vulkan runtime runs on MoltenVK, which needs `lwjgl-vulkan`'s natives on the test
   * classpath.
   */
  fun testRuntimeDependency(version: String): String =
    "org.maplibre.nativeffi:maplibre-native-ffi-runtime-" +
      "${RenderBackend.VULKAN.artifactInfix}-jvm:$version:$nativesClassifier"

  companion object {
    /**
     * The platform this build is running on.
     *
     * Throws rather than guessing: a wrong native runtime fails at map creation with a confusing
     * error, long after the build that chose it.
     */
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
