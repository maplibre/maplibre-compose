import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private fun Project.getJvmTarget(catalogEntry: String): JvmTarget =
  JvmTarget.valueOf("JVM_${catalogVersionInt(catalogEntry)}")

fun Project.getAndroidJvmTarget(): JvmTarget = getJvmTarget("java-androidTarget")

fun Project.getDesktopJvmTarget(): JvmTarget = getJvmTarget("java-desktopTarget")

/**
 * Required by any JVM that loads the MapLibre Native FFI runtime; without them its FFM downcalls
 * are refused.
 */
val NATIVE_ACCESS_JVM_ARGS = listOf("--enable-native-access=ALL-UNNAMED")
