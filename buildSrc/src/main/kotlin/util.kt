import io.github.frankois944.spmForKmp.swiftPackageConfig
import java.net.URI
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

fun Project.getJvmTarget(): JvmTarget {
  val target = properties["jvmTarget"]!!.toString().toInt()
  return JvmTarget.valueOf("JVM_$target")
}

fun KotlinNativeTarget.configureSpmMaplibre(project: Project) {
  swiftPackageConfig {
    dependency {
      remotePackageVersion(
        url = URI("https://github.com/maplibre/maplibre-gl-native-distribution.git"),
        products = { add("MapLibre", exportToKotlin = true) },
        packageName = "maplibre-gl-native-distribution",
        version = project.properties["maplibreIosVersion"]!!.toString(),
      )
    }
  }

  val variant =
    when (targetName) {
      "iosArm64" -> "arm64-apple-ios"
      "iosSimulatorArm64" -> "arm64-apple-ios-simulator"
      "iosX64" -> "x86_64-apple-ios-simulator"
      else -> error("Unrecognized target: $targetName")
    }
  val rpath =
    "${project.layout.buildDirectory.get()}/spmKmpPlugin/$targetName/scratch/$variant/release/"
  binaries.all { linkerOpts("-F$rpath", "-rpath", rpath) }
}

/**
 * Host detection shared by build logic that needs to pick a platform-specific artifact. Step 2 of
 * DESKTOP_FFI_REWRITE.md builds the MapLibre Native FFI runtime classifier on top of this.
 */
class Configuration {
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
}
