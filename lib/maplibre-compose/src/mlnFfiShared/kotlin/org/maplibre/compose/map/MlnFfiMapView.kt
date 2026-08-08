package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun MlnFfiMapView(
  hostFactory: MlnFfiMapHostFactory,
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val applicationOptions = MlnFfiApplication.options
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current

  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val scaleFactor = density.density.toDouble()
  val hostResult =
    remember(hostFactory, runtimeBackends, scaleFactor) { createHost(runtimeBackends, hostFactory) }

  val session =
    remember(hostFactory.backends, scaleFactor, applicationOptions) {
      MlnFfiMapSession(
        callbacks = callbacks,
        logger = logger,
        renderBackend = hostFactory.backends.producer,
        scaleFactor = scaleFactor,
        layoutDirection = layoutDirection,
        cacheFile = applicationOptions.cacheFile,
      )
    }

  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentOnReset = rememberUpdatedState(onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch crashes on anchor validation (see #269).
  SideEffect { session.setBaseStyle(style) }

  LaunchedEffect(session, options, update) { update(session) }

  DisposableEffect(session) {
    session.start()
    onDispose {
      session.close()
      currentOnReset.value()
    }
  }

  // Held here rather than inside the modifier so it survives recomposition.
  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }

  MlnFfiMapSurface(
    renderer = session,
    hostResult = hostResult,
    modifier =
      modifier.mlnFfiMapInput(
        session,
        options.gestureOptions,
        density,
        focusRequester,
        continuation,
      ),
    logger = logger,
  )
}

private fun createHost(
  runtimeBackends: Set<MapRenderBackend>,
  factory: MlnFfiMapHostFactory,
): MlnFfiMapHostResult {
  val diagnostic =
    backendDiagnostic(
      runtimeBackends = runtimeBackends,
      hostBackends = factory.backends,
      hostDescription = factory.description,
      operatingSystem = System.getProperty("os.name") ?: "unknown",
      architecture = System.getProperty("os.arch") ?: "unknown",
    )
  if (diagnostic != null) return MlnFfiMapHostResult.Failed(diagnostic)

  return try {
    factory.create()
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    MlnFfiMapHostResult.Failed("${factory.description} threw while creating a map host", error)
  }
}

/**
 * Reports which backends the packaged MapLibre Native FFI runtime was built with. Empty rather than
 * throwing when no runtime is on the classpath; negotiation reports that as a diagnostic.
 */
private fun loadRuntimeBackends(logger: Logger?): Set<MapRenderBackend> =
  try {
    Maplibre.loadNativeLibrary()
    Maplibre.supportedRenderBackends().mapNotNullTo(mutableSetOf()) { it.toComposeBackend() }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    logger?.e(error) { "Could not load the MapLibre Native FFI runtime" }
    emptySet()
  }

/**
 * The MapLibre Compose producer backend this FFI backend corresponds to, or null when no host
 * bridge exists for it — WebGPU today, which the FFI builds only for the browser.
 */
private fun RenderBackend.toComposeBackend(): MapRenderBackend? =
  when (this) {
    RenderBackend.METAL -> MapRenderBackend.METAL
    RenderBackend.VULKAN -> MapRenderBackend.VULKAN
    RenderBackend.OPENGL -> MapRenderBackend.OPENGL
    RenderBackend.WEBGPU -> null
  }
