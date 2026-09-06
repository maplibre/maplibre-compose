import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

fun Project.stageIosSimulatorTestResources() {
  // Work around an output overlap observed with Compose 1.12.0 and Kotlin 2.4.10:
  // copyTestComposeResourcesForIosSimulatorArm64 writes compose-resources beneath
  // linkDebugTestIosSimulatorArm64's destinationDirectory. Gradle 9.5.0 --info reports
  // "Task output caching requires exclusive access to output paths", so the library and
  // demo test links run again even when compilation is FROM-CACHE. Move the copy output
  // out of the linker directory, then assemble the binary and resources for the test runner.
  // Remove this workaround once upstream gives the copy and link tasks disjoint outputs.
  // Compose registers its copy task after evaluation.
  afterEvaluate {
    val resourceDirectory = layout.buildDirectory.dir("compose/iosSimulatorArm64TestResources")
    val resources =
      tasks.named<Copy>("copyTestComposeResourcesForIosSimulatorArm64") {
        into(resourceDirectory)
      }
    val link = tasks.named<KotlinNativeLink>("linkDebugTestIosSimulatorArm64")
    val bundle =
      tasks.register<Sync>("stageIosSimulatorArm64Test") {
        dependsOn(link, resources)
        from(link.flatMap { it.destinationDirectory })
        // Copy retains deleted files in its output; stage only its current source tree.
        from(resources.map { it.source }) { into("compose-resources") }
        into(layout.buildDirectory.dir("test-bundles/iosSimulatorArm64"))
      }
    tasks.named<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
      dependsOn(bundle)
      executableProperty.set(
        files(
          bundle.zip(link.flatMap { it.outputFile }) { staged, binary ->
            staged.destinationDir.resolve(binary.name)
          }
        )
      )
    }
  }
}
