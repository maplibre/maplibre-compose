package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.flow.emptyFlow

class DesktopLocationBackendResolverTest {
  @Test
  fun missingOrUnavailableBackendIsUnsupported() {
    val unavailableBackend = FakeBackend("wrong-platform", available = false)
    val providers =
      listOf(
        DesktopLocationBackendResolver.resolve(emptyList()),
        DesktopLocationBackendResolver.resolve(listOf(unavailableBackend)),
      )

    providers.forEach {
      assertEquals(LocationBackendAvailability.Unsupported, it.availability)
    }
    assertEquals(0, unavailableBackend.createCalls)
  }

  @Test
  fun multipleBackendsAreMisconfigured() {
    val backends = listOf(FakeBackend("first"), FakeBackend("second"))
    val provider = DesktopLocationBackendResolver.resolve(backends)

    assertIs<LocationBackendAvailability.Misconfigured>(provider.availability)
  }

  @Test
  fun oneAvailableBackendCreatesProvider() {
    val expected = FakeProvider()
    val unavailableBackend = FakeBackend("wrong-platform", available = false)
    val availableBackend = FakeBackend("current-host", provider = expected)
    val provider =
      DesktopLocationBackendResolver.resolve(listOf(unavailableBackend, availableBackend))

    assertSame(expected, provider)
    assertEquals(0, unavailableBackend.createCalls)
    assertEquals(1, availableBackend.createCalls)
  }

  @Test
  fun backendConstructionFailureBecomesMisconfiguration() {
    val failure = IllegalStateException("native dependency is missing")
    val backend = FakeBackend("broken", failure = failure)

    val provider = DesktopLocationBackendResolver.resolve(listOf(backend))

    val availability = assertIs<LocationBackendAvailability.Misconfigured>(provider.availability)
    assertSame(failure, availability.cause)
    assertEquals(1, backend.createCalls)
  }

  @Test
  fun serviceDiscoveryFailureBecomesMisconfiguration() {
    val failure = ServiceConfigurationError("provider constructor failed")
    val provider = DesktopLocationBackendResolver.discover(loadBackends = { throw failure })

    assertSame(
      failure,
      assertIs<LocationBackendAvailability.Misconfigured>(provider.availability).cause,
    )
  }
}

private class FakeBackend(
  override val id: String,
  private val available: Boolean = true,
  private val provider: LocationBackend = FakeProvider(),
  private val failure: Throwable? = null,
) : DesktopLocationBackendFactory {
  var createCalls = 0

  override fun isAvailable(): Boolean = available

  override fun createBackend(window: XdgPortalWindow?): LocationBackend {
    createCalls += 1
    failure?.let { throw it }
    return provider
  }
}

private class FakeProvider : LocationBackend {
  override fun updates(request: LocationRequest) = emptyFlow<LocationEvent>()

  override fun close() = Unit
}
