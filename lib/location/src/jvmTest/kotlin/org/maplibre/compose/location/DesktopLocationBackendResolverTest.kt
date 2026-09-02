package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DesktopLocationBackendResolverTest {
  @Test
  fun missingOrUnavailableBackendIsUnsupported() = runTest {
    val unavailableBackend = FakeBackend("wrong-platform", available = false)
    val providers =
      listOf(
        DesktopLocationBackendResolver.resolve(emptyList()),
        DesktopLocationBackendResolver.resolve(listOf(unavailableBackend)),
      )

    providers.forEach { provider ->
      assertEquals(LocationBackendAvailability.Unsupported, provider.backendAvailability)
      assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
    }
    assertEquals(0, unavailableBackend.createCalls)
  }

  @Test
  fun multipleBackendsAreMisconfigured() = runTest {
    val backends = listOf(FakeBackend("first"), FakeBackend("second"))
    val provider = DesktopLocationBackendResolver.resolve(backends)

    assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
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
  fun backendConstructionFailureBecomesMisconfiguration() = runTest {
    val failure = IllegalStateException("native dependency is missing")
    val backend = FakeBackend("broken", failure = failure)

    val provider = DesktopLocationBackendResolver.resolve(listOf(backend))
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }

    val availability =
      assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertSame(failure, availability.cause)
    assertEquals(1, backend.createCalls)
  }

  @Test
  fun serviceDiscoveryFailureBecomesMisconfiguration() = runTest {
    val failure = ServiceConfigurationError("provider constructor failed")
    val provider = DesktopLocationBackendResolver.discover(loadBackends = { throw failure })
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }

    assertSame(
      failure,
      assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability).cause,
    )
  }
}

private class FakeBackend(
  override val id: String,
  private val available: Boolean = true,
  private val provider: DesktopLocationProvider = FakeProvider(),
  private val failure: Throwable? = null,
) : DesktopLocationBackend {
  var createCalls = 0

  override fun isAvailable(): Boolean = available

  override fun createProvider(window: XdgPortalWindow?): DesktopLocationProvider {
    createCalls += 1
    failure?.let { throw it }
    return provider
  }
}

private class FakeProvider : DesktopLocationProvider {
  override fun updates(request: LocationRequest) = emptyFlow<LocationEvent>()

  override fun close() = Unit
}
