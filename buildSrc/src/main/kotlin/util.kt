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

/**
 * Required by Robolectric 4.17+ on JDK 17+. SDK 36+ creates ApplicationSharedMemory, which reflects
 * into `jdk.internal.access`. https://robolectric.org/getting-started/
 */
val ROBOLECTRIC_JVM_ARGS =
  listOf(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.security=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
  )
