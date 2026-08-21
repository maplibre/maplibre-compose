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
  fun the_highest_priority_backend_wins() {
    val winner = FakeBackend("low-id", priority = 1)

    val resolution =
      AndroidLocationBackendResolver.resolve(listOf(FakeBackend("a-first-id"), winner))

    assertSame(winner, assertIs<AndroidBackendResolution.Discovered>(resolution).backend)
  }

  @Test
  fun equal_priorities_resolve_to_the_first_id() {
    val winner = FakeBackend("first")

    val resolution = AndroidLocationBackendResolver.resolve(listOf(FakeBackend("second"), winner))

    assertSame(winner, assertIs<AndroidBackendResolution.Discovered>(resolution).backend)
  }
}

private class FakeBackend(override val id: String, override val priority: Int = 0) :
  AndroidLocationBackend {
  override fun isAvailable(context: Context) = true

  @Composable
  override fun rememberLocationProvider(): LocationProvider =
    error("this test never composes a provider")
}
