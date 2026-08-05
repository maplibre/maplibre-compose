import io.github.frankois944.spmForKmp.swiftPackageConfig
import java.net.URI
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

private fun Project.getJvmTarget(property: String): JvmTarget {
  val target = properties[property]!!.toString().toInt()
  return JvmTarget.valueOf("JVM_$target")
}

fun Project.getAndroidJvmTarget(): JvmTarget = getJvmTarget("androidJvmTarget")

fun Project.getDesktopJvmTarget(): JvmTarget = getJvmTarget("desktopJvmTarget")

/**
 * Required by any JVM that loads the MapLibre Native FFI runtime; without them its FFM downcalls
 * are refused.
 */
val NATIVE_ACCESS_JVM_ARGS = listOf("--enable-native-access=ALL-UNNAMED")

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
