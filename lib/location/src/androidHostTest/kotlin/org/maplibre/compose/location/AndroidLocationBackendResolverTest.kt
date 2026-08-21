package org.maplibre.compose.location

import android.content.Context
import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertContains
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
  fun multiple_available_backends_are_a_misconfiguration() {
    val resolution =
      AndroidLocationBackendResolver.resolve(listOf(FakeBackend("first"), FakeBackend("second")))

    val cause = assertIs<AndroidBackendResolution.Misconfigured>(resolution).cause
    assertContains(cause.message.orEmpty(), "first")
    assertContains(cause.message.orEmpty(), "second")
  }
}

private class FakeBackend(override val id: String) : AndroidLocationBackend {
  override fun isAvailable(context: Context) = true

  @Composable
  override fun rememberLocationProvider(): LocationProvider =
    error("this test never composes a provider")
}
