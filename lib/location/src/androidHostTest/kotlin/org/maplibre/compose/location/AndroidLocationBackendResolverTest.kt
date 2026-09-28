package org.maplibre.compose.location

import android.content.Context
import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // discover() takes a Context.
@Config(sdk = [36], manifest = Config.NONE)
class AndroidLocationBackendResolverTest {

  @Test
  fun no_available_backend_resolves_to_the_framework_default() {
    assertIs<AndroidBackendResolution.None>(AndroidLocationBackendResolver.resolve(emptyList()))
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

  @Test
  fun loading_failures_resolve_to_misconfigured() {
    val context = RuntimeEnvironment.getApplication()
    // A backend packaged without its vendor SDK fails to link when loaded.
    listOf(ServiceConfigurationError("bad provider entry"), NoClassDefFoundError("vendor/Sdk"))
      .forEach { failure ->
        val resolution = AndroidLocationBackendResolver.discover(context) { throw failure }
        assertSame(failure, assertIs<AndroidBackendResolution.Misconfigured>(resolution).cause)
      }
  }
}

private class FakeBackend(override val id: String, override val priority: Int = 0) :
  AndroidLocationBackend {
  override fun isAvailable(context: Context) = true

  override fun createLocationProvider(context: Context): LocationProvider =
    error("this test never creates a provider")
}
