package org.maplibre.compose.demoapp.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import kotlin.math.log2
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoShell
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.allDemoStyles
import org.maplibre.compose.demoapp.allDemos
import org.maplibre.compose.demoapp.flyTo
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/**
 * Starts the local HTTP agent driver for this app, bound to `127.0.0.1` (default port 8765,
 * override with the `MAPLIBRE_DEMO_AGENT_PORT` environment variable). It exposes the live
 * [DemoAppState] over a JSON API so AI agents can drive the demo app. A no-op on platforms that
 * cannot host a server (iOS, web). For local development only; there is no authentication.
 */
@Composable internal expect fun StartAgentDriver(state: DemoAppState)

/** An error the driver reports to the HTTP client with [statusCode]. */
internal class AgentException(val statusCode: Int, message: String) : Exception(message)

/** The default for request bodies that wait on the map, in milliseconds. */
private const val DEFAULT_TIMEOUT_MS = 30_000L

/** The default camera animation duration for `POST /camera/animate`, in milliseconds. */
private const val DEFAULT_ANIMATION_MS = 300L

@Serializable internal data class ErrorDto(val error: String)

@Serializable
internal data class RouteDto(
  val method: String,
  val path: String,
  val description: String,
  val body: String? = null,
  val query: String? = null,
)

@Serializable internal data class RouteIndexDto(val routes: List<RouteDto>, val notes: List<String>)

@Serializable
internal data class HealthDto(
  val status: String,
  val platform: String,
  val demos: Int,
  val styles: Int,
)

@Serializable
internal data class DemoDto(
  val name: String,
  val description: String,
  val preferredStyle: String? = null,
)

@Serializable
internal data class SelectDemoRequest(val name: String, val timeoutMs: Long = DEFAULT_TIMEOUT_MS)

@Serializable
internal data class CameraPositionDto(
  val latitude: Double,
  val longitude: Double,
  val zoom: Double,
  val bearing: Double,
  val tilt: Double,
)

@Serializable
internal data class CameraDto(
  val position: CameraPositionDto,
  val isCameraMoving: Boolean,
  val moveReason: String,
)

@Serializable
internal data class CameraUpdateRequest(
  val latitude: Double? = null,
  val longitude: Double? = null,
  val zoom: Double? = null,
  val bearing: Double? = null,
  val tilt: Double? = null,
  /** Only `POST /camera/animate` reads this; `PUT /camera` ignores it. */
  val durationMs: Long? = null,
)

@Serializable
internal data class StyleDto(
  val name: String?,
  val isDark: Boolean?,
  val mode: String,
  val lightStyle: String,
  val darkStyle: String,
)

@Serializable
internal data class SetStyleRequest(val name: String, val timeoutMs: Long = DEFAULT_TIMEOUT_MS)

@Serializable
internal data class SettingsDto(
  val mapStyleMode: String,
  val paletteMode: String,
  val useMaterial3Controls: Boolean,
  val showFpsOverlay: Boolean,
  val showCameraOverlay: Boolean,
)

@Serializable
internal data class AgentStateDto(
  val demo: String?,
  val shell: String,
  val camera: CameraDto,
  val style: StyleDto,
  val styleLoadsCompleted: Int,
  val framesPerSecond: Double,
  val totalFrames: Long,
  val settings: SettingsDto,
)

@Serializable internal data class WaitIdleRequest(val timeoutMs: Long = DEFAULT_TIMEOUT_MS)

@Serializable internal data class WaitIdleResponse(val waitedMs: Long)

@Serializable internal data class PanRequest(val dxPx: Float, val dyPx: Float)

@Serializable
internal data class ZoomRequest(val factor: Double, val x: Float? = null, val y: Float? = null)

/**
 * The platform-agnostic handler logic behind the agent driver endpoints. The server passes every
 * call on the main dispatcher, so these read and mutate Compose snapshot state directly.
 */
internal class AgentDriver(
  private val state: DemoAppState,
  private val density: Density,
  private val screenshotCapture: (suspend () -> ByteArray)?,
  private val platform: String,
) {
  fun routeIndex() = ROUTE_INDEX

  fun health() =
    HealthDto(
      status = "ok",
      platform = platform,
      demos = allDemos.size,
      styles = allDemoStyles.size,
    )

  fun demos() = allDemos.map { it.toDto() }

  suspend fun selectDemo(request: SelectDemoRequest): DemoDto {
    requireValidTimeout(request.timeoutMs)
    val demo =
      allDemos.find { it.name.equals(request.name, ignoreCase = true) }
        ?: throw AgentException(
          400,
          "unknown demo '${request.name}'. Valid demos: ${allDemos.joinToString { it.name }}",
        )
    val wasBenchmarks = state.shell == DemoShell.Benchmarks
    val preferred = demo.preferredStyle
    val newBase = preferred?.base?.takeIf { it != state.appliedStyleSnapshot?.base }
    val styleLoadsSeen = state.lastStyleLoad.count
    markDemoMapReload()
    state.selectDemo(demo)
    // The demo map reloads from scratch when leaving the Benchmarks shell, so await its base even
    // when this demo has no preferred style of its own.
    val baseToAwait =
      if (preferred != null && newBase != null) preferred.base
      else if (wasBenchmarks) preferred?.base ?: state.appliedStyleSnapshot?.base else null
    val styleTimedOut =
      if (baseToAwait != null) {
        try {
          awaitStyleLoad(
            styleLoadsSeen,
            baseToAwait,
            preferred?.displayName ?: "the applied style",
            request.timeoutMs,
          )
          false
        } catch (e: AgentException) {
          true
        }
      } else {
        false
      }
    // Fly even when the style wait failed, so a timeout never leaves the demo half-applied.
    state.cameraState.flyTo(demo.destination)
    if (styleTimedOut) {
      throw AgentException(
        408,
        "demo '${demo.name}' selected, but its style did not load within ${request.timeoutMs} ms",
      )
    }
    return demo.toDto()
  }

  fun camera() = state.cameraState.toDto()

  fun jumpCamera(request: CameraUpdateRequest): CameraDto {
    request.validateRanges()
    markDemoMapReload()
    state.cameraState.position = state.cameraState.position.merged(request)
    return camera()
  }

  suspend fun animateCamera(request: CameraUpdateRequest): CameraDto {
    request.validateRanges()
    val durationMs = request.durationMs ?: DEFAULT_ANIMATION_MS
    if (durationMs < 0) throw AgentException(400, "'durationMs' must be >= 0, got $durationMs")
    markDemoMapReload()
    state.cameraState.animateTo(
      finalPosition = state.cameraState.position.merged(request),
      duration = durationMs.milliseconds,
    )
    return camera()
  }

  /**
   * Switches back to the Demos shell. The demo map loads its style from scratch when it recomposes,
   * so mark that load in flight eagerly; a wait/idle that lands before recomposition must not
   * report idle.
   */
  private fun markDemoMapReload() {
    if (state.shell == DemoShell.Benchmarks) {
      state.appliedStyleSnapshot?.let { state.pendingStyleLoad = it.base }
    }
    state.shell = DemoShell.Demos
  }

  fun style(): StyleDto {
    val applied = state.appliedStyleSnapshot
    return StyleDto(
      name = applied?.displayName,
      isDark = applied?.isDark,
      mode = state.settings.mapStyleMode.name,
      lightStyle = state.chosenLightStyle.displayName,
      darkStyle = state.chosenDarkStyle.displayName,
    )
  }

  suspend fun setStyle(request: SetStyleRequest): StyleDto {
    requireValidTimeout(request.timeoutMs)
    val style = findStyle(request.name)
    state.selectedDemo?.let {
      if (it.preferredStyle != null) {
        throw AgentException(
          409,
          "the selected demo '${it.name}' pins its own base style; select another demo first",
        )
      }
    }
    val wasBenchmarks = state.shell == DemoShell.Benchmarks
    markDemoMapReload()
    val styleLoadsSeen = state.lastStyleLoad.count
    // Set both so the style applies regardless of how MapStyleMode resolves.
    state.chosenLightStyle = style
    state.chosenDarkStyle = style
    // appliedStyleSnapshot goes stale while the Benchmarks shell is showing, and the demo map
    // loads from scratch on recomposition, so a snapshot match does not mean the load finished.
    if (wasBenchmarks || state.appliedStyleSnapshot != style) {
      awaitStyleLoad(styleLoadsSeen, style.base, style.displayName, request.timeoutMs)
    }
    return style()
  }

  fun fullState() =
    AgentStateDto(
      demo = state.selectedDemo?.name,
      shell = state.shell.name,
      camera = camera(),
      style = style(),
      styleLoadsCompleted = state.lastStyleLoad.count,
      framesPerSecond = state.frameRateState.framesPerSecond,
      totalFrames = state.frameRateState.totalFrames,
      settings =
        SettingsDto(
          mapStyleMode = state.settings.mapStyleMode.name,
          paletteMode = state.settings.paletteMode.name,
          useMaterial3Controls = state.settings.useMaterial3Controls,
          showFpsOverlay = state.settings.showFpsOverlay,
          showCameraOverlay = state.settings.showCameraOverlay,
        ),
    )

  /**
   * Waits for any in-flight style load to finish, at least one completed load, and a still camera.
   */
  suspend fun waitIdle(request: WaitIdleRequest): WaitIdleResponse {
    requireValidTimeout(request.timeoutMs)
    val start = TimeSource.Monotonic.markNow()
    try {
      withTimeout(request.timeoutMs) {
        snapshotFlow {
          state.lastStyleLoad.count > 0 &&
            state.pendingStyleLoad == null &&
            !state.cameraState.isCameraMoving
        }
          .first { it }
      }
    } catch (e: TimeoutCancellationException) {
      throw AgentException(408, "the map did not go idle within ${request.timeoutMs} ms")
    }
    return WaitIdleResponse(waitedMs = start.elapsedNow().inWholeMilliseconds)
  }

  suspend fun screenshot(): ByteArray =
    screenshotCapture?.invoke()
      ?: throw AgentException(501, "screenshots are not supported on this host")

  suspend fun featuresJson(xPx: Float, yPx: Float, layerIds: Set<String>?): String {
    val offset = with(density) { DpOffset(xPx.toDp(), yPx.toDp()) }
    val features = state.cameraState.queryRenderedFeatures(offset = offset, layerIds = layerIds)
    return FeatureCollection(features).toJson()
  }

  /** Pans the map as if dragged by ([request]'s dx, dy) pixels, via the camera. */
  fun pan(request: PanRequest): CameraDto {
    requireFinite("dxPx", request.dxPx)
    requireFinite("dyPx", request.dyPx)
    val size =
      state.cameraState.viewport?.size ?: throw AgentException(503, "the map has no viewport yet")
    val center = DpOffset(size.width / 2, size.height / 2)
    val moved =
      with(density) { DpOffset(center.x + request.dxPx.toDp(), center.y + request.dyPx.toDp()) }
    // Best effort: a screen point off the map has no position, so report the camera unchanged.
    val atCenter = state.cameraState.positionFromScreenLocation(center) ?: return camera()
    val atMoved = state.cameraState.positionFromScreenLocation(moved) ?: return camera()
    val current = state.cameraState.position
    // The projection wraps longitude to ±180; take the short way across the antimeridian.
    val deltaLng = shortLongitudeDelta(from = atMoved.longitude, to = atCenter.longitude)
    state.cameraState.position =
      current.copy(
        target =
          Position(
            longitude = current.target.longitude + deltaLng,
            latitude = current.target.latitude + (atCenter.latitude - atMoved.latitude),
          )
      )
    return camera()
  }

  /**
   * Zooms the map by [ZoomRequest.factor] via the camera. When an anchor point is given, the target
   * shifts toward it so the anchor approximately stays put.
   */
  fun zoom(request: ZoomRequest): CameraDto {
    if (!request.factor.isFinite() || request.factor <= 0.0) {
      throw AgentException(400, "'factor' must be a positive finite number, got ${request.factor}")
    }
    if ((request.x == null) != (request.y == null)) {
      throw AgentException(400, "'x' and 'y' must be given together")
    }
    request.x?.let { requireFinite("x", it) }
    request.y?.let { requireFinite("y", it) }
    val current = state.cameraState.position
    val anchor =
      if (request.x != null && request.y != null) {
        with(density) { DpOffset(request.x.toDp(), request.y.toDp()) }
          .let(state.cameraState::positionFromScreenLocation)
      } else {
        null
      }
    val newZoom = (current.zoom + log2(request.factor)).coerceIn(0.0, 25.5)
    // Base the anchor shift on the zoom that survives clamping, not the requested factor.
    val fraction = 1.0 - 2.0.pow(current.zoom - newZoom)
    val target =
      anchor?.let {
        // The projection wraps longitude to ±180; take the short way across the antimeridian.
        val deltaLng = shortLongitudeDelta(from = current.target.longitude, to = it.longitude)
        Position(
          longitude = current.target.longitude + deltaLng * fraction,
          latitude = current.target.latitude + (it.latitude - current.target.latitude) * fraction,
        )
      } ?: current.target
    state.cameraState.position = current.copy(target = target, zoom = newZoom)
    return camera()
  }

  private fun findStyle(name: String): DemoStyle =
    allDemoStyles.find { it.displayName.equals(name, ignoreCase = true) }
      ?: allDemoStyles
        .filter { it.displayName.substringBefore(" (").equals(name, ignoreCase = true) }
        .singleOrNull()
      ?: throw AgentException(
        400,
        "unknown style '$name'. Valid styles: ${allDemoStyles.joinToString { it.displayName }}",
      )

  private suspend fun awaitStyleLoad(seen: Int, base: BaseStyle, name: String, timeoutMs: Long) {
    try {
      withTimeout(timeoutMs) { state.awaitStyleLoad(seen = seen, base = base) }
    } catch (e: TimeoutCancellationException) {
      throw AgentException(408, "timed out waiting for style '$name' to load")
    }
  }

  private fun CameraUpdateRequest.validateRanges() {
    latitude?.let { requireInRange("latitude", it, -90.0..90.0) }
    longitude?.let { requireInRange("longitude", it, -180.0..180.0) }
    zoom?.let { requireInRange("zoom", it, 0.0..25.5) }
    bearing?.let { requireFinite("bearing", it) }
    tilt?.let { requireInRange("tilt", it, 0.0..60.0) }
  }

  private fun requireInRange(name: String, value: Double, range: ClosedFloatingPointRange<Double>) {
    if (!value.isFinite() || value !in range) {
      throw AgentException(
        400,
        "'$name' must be a finite number in ${range.start}..${range.endInclusive}, got $value",
      )
    }
  }

  private fun requireFinite(name: String, value: Double) {
    if (!value.isFinite()) throw AgentException(400, "'$name' must be a finite number, got $value")
  }

  private fun requireFinite(name: String, value: Float) {
    if (!value.isFinite()) throw AgentException(400, "'$name' must be a finite number, got $value")
  }

  private fun requireValidTimeout(timeoutMs: Long) {
    if (timeoutMs <= 0) throw AgentException(400, "'timeoutMs' must be > 0, got $timeoutMs")
  }

  /**
   * The shortest signed longitude delta from [from] to [to], across the antimeridian if shorter.
   */
  private fun shortLongitudeDelta(from: Double, to: Double) = (to - from + 540.0) % 360.0 - 180.0

  private fun CameraPosition.merged(request: CameraUpdateRequest) =
    copy(
      target =
        Position(
          longitude = request.longitude ?: target.longitude,
          latitude = request.latitude ?: target.latitude,
        ),
      zoom = request.zoom ?: zoom,
      bearing = request.bearing ?: bearing,
      tilt = request.tilt ?: tilt,
    )

  private fun CameraState.toDto(): CameraDto {
    val position = position
    return CameraDto(
      position =
        CameraPositionDto(
          latitude = position.target.latitude,
          longitude = position.target.longitude,
          zoom = position.zoom,
          bearing = position.bearing,
          tilt = position.tilt,
        ),
      isCameraMoving = isCameraMoving,
      moveReason = moveReason.name,
    )
  }

  private fun Demo.toDto() =
    DemoDto(name = name, description = description, preferredStyle = preferredStyle?.displayName)

  private companion object {
    val ROUTE_INDEX =
      RouteIndexDto(
        routes =
          listOf(
            RouteDto("GET", "/", "this route index"),
            RouteDto("GET", "/health", "liveness plus app and platform info"),
            RouteDto("GET", "/demos", "list demos: name, description, preferred style"),
            RouteDto(
              "POST",
              "/demos/select",
              "select a demo, await its style load, fly to its destination",
              body = "{name, timeoutMs?}",
            ),
            RouteDto("GET", "/camera", "current camera position and movement state"),
            RouteDto(
              "PUT",
              "/camera",
              "jump the camera (durationMs is ignored)",
              body = "{latitude?, longitude?, zoom?, bearing?, tilt?}",
            ),
            RouteDto(
              "POST",
              "/camera/animate",
              "animate the camera; responds when the animation ends",
              body = "{latitude?, longitude?, zoom?, bearing?, tilt?, durationMs?}",
            ),
            RouteDto("GET", "/style", "current base style"),
            RouteDto(
              "PUT",
              "/style",
              "switch the base style; awaits the style load",
              body = "{name, timeoutMs?}",
            ),
            RouteDto(
              "GET",
              "/state",
              "aggregate: demo, shell, camera, fps, style status, settings",
            ),
            RouteDto(
              "POST",
              "/wait/idle",
              "wait for style loads to finish and the camera to be still",
              body = "{timeoutMs?}",
            ),
            RouteDto("GET", "/screenshot", "PNG bytes of the app window"),
            RouteDto(
              "GET",
              "/features",
              "rendered features at a screen point, as GeoJSON",
              query = "x, y (pixels); layerIds? (comma-separated)",
            ),
            RouteDto(
              "POST",
              "/gestures/pan",
              "pan by screen pixels (best effort)",
              body = "{dxPx, dyPx}",
            ),
            RouteDto(
              "POST",
              "/gestures/zoom",
              "zoom by a factor around a screen point (best effort)",
              body = "{factor, x?, y?}",
            ),
          ),
        notes =
          listOf(
            "All responses are JSON except /screenshot (PNG). Errors are JSON {error: message}.",
            "/camera/animate returns when the animation ends or is superseded by another camera " +
              "command; await the animate response before calling /wait/idle.",
            "A 408 from /demos/select still applies the demo and flies the camera; only the " +
              "style wait timed out.",
            "Camera reads report the Demos-shell camera while the Benchmarks shell is showing; " +
              "camera, style, and demo mutations switch back to the Demos shell first.",
          ),
      )
  }
}
