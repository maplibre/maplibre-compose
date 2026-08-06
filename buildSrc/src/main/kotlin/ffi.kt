/**
 * The desktop platforms MapLibre Native FFI publishes a native runtime for.
 *
 * The FFI ships one artifact per render backend, each carrying per-platform natives under a
 * classifier; an application picks exactly one.
 */
enum class DesktopHostPlatform(
  private val os: String,
  private val arch: String,

  /**
   * The one backend `ComposeGpuMapHostFactory` can bridge here today, not a property of the
   * platform: the FFI offers OpenGL and Vulkan everywhere, and an application chooses by choosing a
   * runtime.
   */
  val defaultRenderBackend: RenderBackend,
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

  /** Names this platform in an artifact id, e.g. `linux-x64`. */
  val artifactSuffix: String
    get() = "$os-$arch"

  /** Our runtime artifact for [backend] on this platform. */
  fun runtimeArtifactId(backend: RenderBackend): String =
    "maplibre-compose-runtime-${backend.artifactInfix}-$artifactSuffix"

  /** Our runtime artifact for [defaultRenderBackend]. */
  val defaultRuntimeArtifactId: String
    get() = runtimeArtifactId(defaultRenderBackend)

  /** Module carrying [backend]'s native runtime, without a version. */
  fun runtimeModule(backend: RenderBackend): String =
    "org.maplibre.nativeffi:maplibre-native-ffi-runtime-${backend.artifactInfix}-jvm"

  /**
   * Whether the Compose side of the handoff presents through OpenGL, and so needs LWJGL's OpenGL
   * natives. Follows the platform rather than the map's backend: Skiko and compose-glfw agree.
   */
  private val presentsThroughOpenGl: Boolean
    get() = os == "linux"

  /**
   * Dependency notation for the compose-glfw runtime this platform needs. Its backend names the
   * Compose consumer, a different axis from the map's own; the two only coincide on macOS.
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

  /** Full dependency notation for [backend]'s native runtime on this platform. */
  fun runtimeDependency(backend: RenderBackend, version: String): String =
    "${runtimeModule(backend)}:$version:$nativesClassifier"

  /**
   * Every native artifact an application needs to run a map with [backend] here: the FFI's runtime
   * for that backend, and the LWJGL natives whichever Compose host loads.
   */
  fun runtimeDependencies(
    backend: RenderBackend,
    ffiVersion: String,
    lwjglVersion: String,
  ): List<String> = buildList {
    add(runtimeDependency(backend, ffiVersion))
    add("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNativesClassifier")
    if (presentsThroughOpenGl) add("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNativesClassifier")
  }

  /**
   * Dependency notation for the runtime the headless GPU tests need. Always Vulkan, even on macOS:
   * `HeadlessVulkanMapHost` has no Metal equivalent, and there it runs on MoltenVK, which needs
   * `lwjgl-vulkan`'s natives on the test classpath.
   */
  fun testRuntimeDependency(version: String): String =
    runtimeDependency(RenderBackend.VULKAN, version)

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
