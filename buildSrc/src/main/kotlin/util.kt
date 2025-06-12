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
