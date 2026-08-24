/**
 * The desktop platforms MapLibre Native FFI publishes a native runtime for.
 *
 * The FFI ships one artifact per render backend, each carrying per-platform natives under a
 * classifier; an application picks exactly one. Which backends a platform can run is a property of
 * the platform; which one runs is the application's choice of runtime artifact.
 */
enum class DesktopHostPlatform(
  private val os: String,
  private val arch: String,

  /** Every backend this platform runs, in preference order. */
  val supportedBackends: List<RenderBackend>,
) {
  LinuxX64("linux", "x64", listOf(RenderBackend.VULKAN, RenderBackend.OPENGL)),
  LinuxArm64("linux", "arm64", listOf(RenderBackend.VULKAN, RenderBackend.OPENGL)),
  MacosArm64("macos", "arm64", listOf(RenderBackend.METAL)),
  WindowsX64("windows", "x64", listOf(RenderBackend.VULKAN, RenderBackend.OPENGL)),
  WindowsArm64("windows", "arm64", listOf(RenderBackend.VULKAN, RenderBackend.OPENGL));

  enum class RenderBackend(val artifactInfix: String) {
    METAL("metal"),
    VULKAN("vulkan"),
    OPENGL("opengl"),
  }

  /** The backend packaged when the build does not select one, the first of [supportedBackends]. */
  val defaultRenderBackend: RenderBackend
    get() = supportedBackends.first()

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

  /** Module carrying [backend]'s native runtime, without a version. */
  fun runtimeModule(backend: RenderBackend): String =
    "org.maplibre.nativeffi:maplibre-native-ffi-runtime-${backend.artifactInfix}-jvm"

  /**
   * Whether the Compose side of the handoff presents through OpenGL, and so needs LWJGL's OpenGL
   * natives. Follows the platform rather than the map's backend: Skiko and compose-glfw agree.
   */
  private val presentsThroughOpenGl: Boolean
    get() = os == "linux" || os == "windows"

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
    if (backend == RenderBackend.VULKAN && os == "macos") {
      add("org.lwjgl:lwjgl-vulkan:$lwjglVersion:$lwjglNativesClassifier")
    }
  }

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

/**
 * The backend the `maplibre.desktop.backend` Gradle property selects for this platform, or the
 * default when the property is unset.
 *
 * Pass `providers.gradleProperty("maplibre.desktop.backend").orNull`; the property names a backend
 * by its artifact infix, as in `-Pmaplibre.desktop.backend=opengl`.
 */
fun DesktopHostPlatform.selectedRenderBackend(
  requested: String?
): DesktopHostPlatform.RenderBackend {
  if (requested.isNullOrBlank()) return defaultRenderBackend
  val backend =
    DesktopHostPlatform.RenderBackend.entries.singleOrNull { it.artifactInfix == requested }
  check(backend != null && backend in supportedBackends) {
    "Backend '$requested' does not run on $artifactSuffix. Choose one of " +
      supportedBackends.joinToString { it.artifactInfix } +
      '.'
  }
  return backend
}
