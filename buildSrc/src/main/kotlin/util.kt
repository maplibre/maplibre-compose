import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

fun Project.getJvmTarget(): JvmTarget {
  val target = properties["jvmTarget"]!!.toString().toInt()
  return JvmTarget.valueOf("JVM_$target")
}

fun KotlinNativeTarget.configureSpmMaplibre(project: Project) {
  // ideally the SPM gradle plugin should handle this for us
  val variant =
    when (targetName) {
      "iosArm64" -> "arm64-apple-ios"
      "iosSimulatorArm64" -> "arm64-apple-ios-simulator"
      "iosX64" -> "x86_64-apple-ios-simulator"
      else -> error("Unrecognized target: $targetName")
    }
  val rpath =
    "${project.layout.buildDirectory.get()}/spmKmpPlugin/spmMaplibre/scratch/$variant/release/"
  binaries.all { linkerOpts("-F$rpath", "-rpath", rpath) }
  compilations.getByName("main") { cinterops { create("spmMaplibre") } }
}

class Configuration(private val project: Project) {
  val hostOs =
    when (val os = System.getProperty("os.name").lowercase()) {
      "mac os x" -> "macos"
      else -> os.split(" ").first()
    }

  val hostArch =
    when (val arch = System.getProperty("os.arch").lowercase()) {
      "x86_64" -> "amd64" // jdk returns x86_64 on macos but amd64 elsewhere
      else -> arch
    }

  val desktopRenderer: String
    get() =
      project.findProperty("desktopRenderer")?.toString()
        ?: when (hostOs) {
          "macos" -> "metal"
          else -> "opengl"
        }

  val hostOsArchRendererTriplet: String
    get() = "${hostOs}-${hostArch}-${desktopRenderer}"

  val shouldConfigureForPublishing
    get() = project.properties["configureForPublishing"]?.toString()?.toBoolean() ?: false
}

enum class DesktopVariant(
  val os: String,
  val arch: String,
  val renderer: String,
  val publish: Boolean = false,
) {
  MacosAmd64Metal("macos", "amd64", "metal"),
  MacosAarch64Metal("macos", "aarch64", "metal", true),
  MacosAmd64Vulkan("macos", "amd64", "vulkan"),
  MacosAarch64Vulkan("macos", "aarch64", "vulkan"),
  LinuxAmd64Opengl("linux", "amd64", "opengl", true),
  LinuxAarch64Opengl("linux", "aarch64", "opengl"),
  LinuxAmd64Vulkan("linux", "amd64", "vulkan"),
  LinuxAarch64Vulkan("linux", "aarch64", "vulkan"),
  WindowsAmd64Opengl("windows", "amd64", "opengl", true),
  WindowsAarch64Opengl("windows", "aarch64", "opengl"),
  WindowsAmd64Vulkan("windows", "amd64", "vulkan"),
  WindowsAarch64Vulkan("windows", "aarch64", "vulkan");

  companion object {

    private fun valueForHost(project: Project): DesktopVariant {
      val config = Configuration(project)
      return values().firstOrNull {
        it.os == config.hostOs &&
          it.arch == config.hostArch &&
          it.renderer == config.desktopRenderer
      }
        ?: error(
          "Unsupported combination: ${config.hostOs}/${config.hostArch}/${config.desktopRenderer}"
        )
    }

    private fun valuesForPublishing(): List<DesktopVariant> {
      return values().filter { it.publish }
    }

    fun currentValues(project: Project): List<DesktopVariant> {
      return if (Configuration(project).shouldConfigureForPublishing) valuesForPublishing()
      else listOf(valueForHost(project))
    }
  }
}
