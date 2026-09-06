import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

fun Project.stageIosSimulatorTestResources() {
  // Compose registers its copy task after evaluation. Give it a directory that the linker
  // does not own, then assemble both outputs for the existing simulator test runner.
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
        from(resourceDirectory) { into("compose-resources") }
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
