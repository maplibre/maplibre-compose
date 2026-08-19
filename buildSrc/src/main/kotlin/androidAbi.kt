import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasDeviceTests
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure

internal val ANDROID_JNI_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

/**
 * JNI paths to omit from an APK when `-Pmaplibre.android.abis=` lists the ABIs to keep.
 *
 * Device-test packaging uses this so the APK matches the emulator this host boots. Leave the
 * property unset for published AARs, which still carry every ABI.
 */
fun jniLibExcludePatterns(keepAbis: Collection<String>): Set<String> {
  val keep = keepAbis.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
  require(keep.isNotEmpty()) { "maplibre.android.abis must name at least one ABI" }
  val unknown = keep - ANDROID_JNI_ABIS.toSet()
  require(unknown.isEmpty()) {
    "Unknown ABI in maplibre.android.abis: ${unknown.sorted().joinToString()}. " +
      "Expected a subset of ${ANDROID_JNI_ABIS.joinToString()}."
  }
  return ANDROID_JNI_ABIS.filterNot { it in keep }.map { "lib/$it/**" }.toSet()
}

fun Project.androidNativeAbiExcludes(): Provider<Set<String>> =
  providers
    .gradleProperty("maplibre.android.abis")
    .map { raw -> jniLibExcludePatterns(raw.split(',')) }
    .orElse(emptySet())

fun Project.configureAndroidNativeAbiPackaging() {
  val excludes = androidNativeAbiExcludes()
  pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
      onVariants { variant -> variant.filterDeviceTestNativeAbis(excludes) }
    }
  }
  pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationAndroidComponentsExtension> {
      onVariants { variant ->
        variant.packaging.jniLibs.excludes.addAll(excludes)
        variant.filterDeviceTestNativeAbis(excludes)
      }
    }
  }
}

private fun HasDeviceTests.filterDeviceTestNativeAbis(excludes: Provider<Set<String>>) {
  deviceTests.values.forEach { deviceTest ->
    deviceTest.packaging.jniLibs.excludes.addAll(excludes)
  }
}
