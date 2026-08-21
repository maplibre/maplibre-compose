package org.maplibre.compose.location

import android.content.Context
import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class AndroidLocationBackendResolverTest {

  @Test
  fun no_available_backend_resolves_to_the_framework_default() {
    assertIs<AndroidBackendResolution.None>(AndroidLocationBackendResolver.resolve(emptyList()))
  }

  @Test
  fun one_available_backend_is_discovered() {
    val backend = FakeBackend("fake")

    val resolution = AndroidLocationBackendResolver.resolve(listOf(backend))

    assertSame(backend, assertIs<AndroidBackendResolution.Discovered>(resolution).backend)
  }

  @Test
  fun highest_priority_backend_wins_regardless_of_id_order() {
    val preferred = FakeBackend("zzz-fused", priority = 100)
    val other = FakeBackend("aaa-fused", priority = 50)

    val resolution = AndroidLocationBackendResolver.resolve(listOf(other, preferred))

    assertSame(preferred, assertIs<AndroidBackendResolution.Discovered>(resolution).backend)
  }

  @Test
  fun equal_priority_breaks_ties_with_the_lexicographically_first_id() {
    val later = FakeBackend("hms-fused")
    val earlier = FakeBackend("gms-fused")

    val resolution = AndroidLocationBackendResolver.resolve(listOf(later, earlier))

    assertSame(earlier, assertIs<AndroidBackendResolution.Discovered>(resolution).backend)
  }
}

private class FakeBackend(override val id: String, override val priority: Int = 0) :
  AndroidLocationBackend {
  override fun isAvailable(context: Context) = true

  @Composable
  override fun rememberLocationProvider(): LocationProvider =
    error("this test never composes a provider")
}
