package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
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
      assertEquals(LocationBackendAvailability.Unsupported, provider.backendAvailability)
      assertEquals(
        LocationUnavailableReason.Unsupported,
        (provider.updates(LocationRequest()).first() as LocationEvent.Unavailable).reason,
      )
    }
    val requester = DesktopLocationBackendResolver.resolvePermissionRequester(emptyList())
    assertEquals(LocationBackendAvailability.Unsupported, requester.backendAvailability)
    assertEquals(
      LocationPermission.NotGranted(canRequest = null),
      requester.status.value,
    )
    assertEquals(0, unavailableBackend.createCalls)
  }

  @Test
  fun multipleBackendsAreMisconfigured() = runTest {
    val backends = listOf(FakeBackend("first"), FakeBackend("second"))
    val provider = DesktopLocationBackendResolver.resolve(backends)
    val requester = DesktopLocationBackendResolver.resolvePermissionRequester(backends)

    assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertEquals(
      LocationUnavailableReason.Misconfigured,
      (provider.updates(LocationRequest()).first() as LocationEvent.Unavailable).reason,
    )
    assertIs<LocationBackendAvailability.Misconfigured>(requester.backendAvailability)
    assertEquals(LocationPermission.NotGranted(canRequest = null), requester.status.value)
  }

  @Test
  fun oneAvailableBackendCreatesProvider() {
    val expected = FakeProvider()
    val expectedRequester = FakePermissionRequester()
    val unavailableBackend = FakeBackend("wrong-platform", available = false)
    val availableBackend =
      FakeBackend(
        "current-host",
        provider = expected,
        permissionRequester = expectedRequester,
      )
    val provider =
      DesktopLocationBackendResolver.resolve(listOf(unavailableBackend, availableBackend))
    val requester =
      DesktopLocationBackendResolver.resolvePermissionRequester(
        listOf(unavailableBackend, availableBackend)
      )

    assertSame(expected, provider)
    assertSame(expectedRequester, requester)
    assertEquals(0, unavailableBackend.createCalls)
    assertEquals(0, unavailableBackend.createPermissionRequesterCalls)
    assertEquals(1, availableBackend.createCalls)
    assertEquals(1, availableBackend.createPermissionRequesterCalls)
  }

  @Test
  fun backendConstructionFailureBecomesMisconfiguration() = runTest {
    val failure = IllegalStateException("native dependency is missing")
    val backend = FakeBackend("broken", failure = failure)

    val provider = DesktopLocationBackendResolver.resolve(listOf(backend))
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    val availability =
      assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    assertSame(failure, availability.cause)
    assertSame(failure, event.cause)
    assertEquals(1, backend.createCalls)
  }

  @Test
  fun serviceDiscoveryFailureBecomesMisconfiguration() = runTest {
    val failure = ServiceConfigurationError("provider constructor failed")
    val provider = DesktopLocationBackendResolver.discover(loadBackends = { throw failure })
    val requester =
      DesktopLocationBackendResolver.discoverPermissionRequester(loadBackends = { throw failure })
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    assertSame(failure, event.cause)
    assertSame(
      failure,
      assertIs<LocationBackendAvailability.Misconfigured>(requester.backendAvailability).cause,
    )
    assertEquals(LocationPermission.NotGranted(canRequest = null), requester.status.value)
  }
}

private class FakeBackend(
  override val id: String,
  private val available: Boolean = true,
  private val provider: DesktopLocationProvider = FakeProvider(),
  private val permissionRequester: DesktopLocationPermissionRequester = FakePermissionRequester(),
  private val failure: Throwable? = null,
) : DesktopLocationBackend {
  var createCalls = 0
  var createPermissionRequesterCalls = 0

  override fun isAvailable(): Boolean = available

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider {
    createCalls += 1
    failure?.let { throw it }
    return provider
  }

  override fun createPermissionRequester(
    host: ComposeMapHost?
  ): DesktopLocationPermissionRequester {
    createPermissionRequesterCalls += 1
    failure?.let { throw it }
    return permissionRequester
  }
}

private class FakeProvider : DesktopLocationProvider {
  override fun updates(request: LocationRequest) = emptyFlow<LocationEvent>()

  override fun close() = Unit
}

private class FakePermissionRequester : DesktopLocationPermissionRequester {
  override val status =
    MutableStateFlow<LocationPermission>(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    )

  override fun requestForegroundPermission() = Unit

  override fun close() = Unit
}
