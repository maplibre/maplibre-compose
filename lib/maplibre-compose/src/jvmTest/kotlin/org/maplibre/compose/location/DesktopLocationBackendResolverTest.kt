package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.desktop.ComposeMapHost

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
      assertFalse(provider.isSupported)
      assertEquals(
        LocationUnavailableReason.Unsupported,
        (provider.updates(LocationRequest()).first() as LocationEvent.Unavailable).reason,
      )
    }
    assertEquals(
      LocationPermission.NotGranted(canRequest = null),
      DesktopLocationBackendResolver.resolvePermissionRequester(emptyList()).status.value,
    )
    assertEquals(0, unavailableBackend.createCalls)
  }

  @Test
  fun multipleBackendsAreMisconfigured() = runTest {
    val provider =
      DesktopLocationBackendResolver.resolve(listOf(FakeBackend("first"), FakeBackend("second")))

    assertEquals(
      LocationUnavailableReason.Misconfigured,
      (provider.updates(LocationRequest()).first() as LocationEvent.Unavailable).reason,
    )
  }

  @Test
  fun oneAvailableBackendCreatesProvider() {
    val expected = FakeProvider()
    val provider =
      DesktopLocationBackendResolver.resolve(
        listOf(FakeBackend("current-host", provider = expected))
      )

    assertSame(expected, provider)
  }

  @Test
  fun backendConstructionFailureBecomesMisconfiguration() = runTest {
    val failure = IllegalStateException("native dependency is missing")
    val backend = FakeBackend("broken", failure = failure)

    val provider = DesktopLocationBackendResolver.resolve(listOf(backend))
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    assertSame(failure, event.cause)
    assertEquals(1, backend.createCalls)
  }

  @Test
  fun serviceDiscoveryFailureBecomesMisconfiguration() = runTest {
    val failure = ServiceConfigurationError("provider constructor failed")
    val provider = DesktopLocationBackendResolver.discover(loadBackends = { throw failure })
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    assertSame(failure, event.cause)
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

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider {
    createCalls += 1
    failure?.let { throw it }
    return provider
  }

  override fun createPermissionRequester(
    host: ComposeMapHost?
  ): DesktopLocationPermissionRequester = FakePermissionRequester
}

private class FakeProvider : DesktopLocationProvider {
  override fun updates(request: LocationRequest) = emptyFlow<LocationEvent>()

  override fun close() = Unit
}

private object FakePermissionRequester : DesktopLocationPermissionRequester {
  override val status =
    MutableStateFlow<LocationPermission>(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    )

  override fun requestForegroundPermission() = Unit

  override fun close() = Unit
}
