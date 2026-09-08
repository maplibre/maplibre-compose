package org.maplibre.compose.demoapp.auto

import android.content.res.Configuration
import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.maplibre.compose.map.AndroidMapPresentation
import org.maplibre.compose.map.MapState

/** Adapts the car host's Surface ownership, physical coordinates, and occlusion rectangles. */
internal class CarMapSurface(
  private val carContext: CarContext,
  lifecycle: Lifecycle,
  private val state: MapState,
) : SurfaceCallback, DefaultLifecycleObserver {
  val presentation =
    AndroidMapPresentation(
      context = carContext,
      state = state,
      lifecycle = lifecycle,
      configuration = carConfiguration(carContext.resources.configuration),
    )
  private val appManager = carContext.getCarService(AppManager::class.java)
  private var surface: Surface? = null
  private var binding: AndroidMapPresentation.SurfaceBinding? = null
  private var width = 0
  private var height = 0
  private var density = 1f
  private var visibleArea = Rect()
  private var stableArea = Rect()
  private var closed = false

  init {
    lifecycle.addObserver(this)
    appManager.setSurfaceCallback(this)
  }

  override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
    val incoming = surfaceContainer.surface
    if (closed || presentation.failure != null) {
      if (incoming !== surface) incoming?.release()
      return
    }
    if (
      incoming == null ||
        !incoming.isValid ||
        surfaceContainer.width <= 0 ||
        surfaceContainer.height <= 0 ||
        surfaceContainer.dpi <= 0
    ) {
      val previous = surface
      try {
        releaseSurface()
      } finally {
        if (incoming !== previous) incoming?.release()
      }
      return
    }

    width = surfaceContainer.width
    height = surfaceContainer.height
    density = surfaceContainer.dpi / 160f
    if (incoming === surface) {
      binding?.update(width, height, density)
    } else {
      // Binder may deliver a new Java Surface for the same underlying buffer queue. Disconnect
      // the previous producer before attaching, and release every wrapper the car gives us.
      try {
        releaseSurface()
        binding = presentation.attachSurface(incoming, width, height, density)
        surface = incoming
      } catch (error: Throwable) {
        incoming.release()
        throw error
      }
    }
    updatePadding()
  }

  override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
    // SurfaceContainer has no stable target id. Follow this registration's ordered host callbacks;
    // do not compare the separately parceled destruction wrapper with the retained wrapper.
    val previous = surface
    val destroyed = surfaceContainer.surface
    try {
      releaseSurface()
    } finally {
      if (destroyed !== previous) destroyed?.release()
    }
    visibleArea.setEmpty()
    stableArea.setEmpty()
  }

  override fun onVisibleAreaChanged(visibleArea: Rect) {
    this.visibleArea = Rect(visibleArea)
    updatePadding()
  }

  override fun onStableAreaChanged(stableArea: Rect) {
    this.stableArea = Rect(stableArea)
    updatePadding()
  }

  override fun onScroll(distanceX: Float, distanceY: Float) {
    // Car scroll distances follow GestureDetector: positive distances move content left/up.
    if (binding != null) state.panBy(logical(-distanceX, -distanceY))
  }

  override fun onFling(velocityX: Float, velocityY: Float) {
    if (binding != null) state.fling(logical(velocityX, velocityY))
  }

  override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
    if (binding == null) return
    val anchor = if (focusX < 0f || focusY < 0f) null else logical(focusX, focusY)
    state.scaleBy(scaleFactor.toDouble(), anchor)
  }

  override fun onClick(x: Float, y: Float) {
    if (binding != null) state.click(logical(x, y))
  }

  private fun logical(x: Float, y: Float) = DpOffset((x / density).dp, (y / density).dp)

  fun updateConfiguration(configuration: Configuration) {
    if (!closed && presentation.failure == null) {
      presentation.updateConfiguration(carConfiguration(configuration))
    }
  }

  override fun onDestroy(owner: LifecycleOwner) {
    closed = true
    try {
      appManager.setSurfaceCallback(null)
    } finally {
      try {
        // Close even if the car disconnected before unregistration. If detachment fails, keep
        // the borrowed Surface: releasing it would violate the presentation's ownership contract.
        presentation.close()
        releaseSurface()
      } finally {
        owner.lifecycle.removeObserver(this)
      }
    }
  }

  private fun releaseSurface() {
    binding?.close()
    binding = null
    surface?.release()
    surface = null
  }

  private fun updatePadding() {
    if (closed || surface == null || presentation.failure != null) return
    // Use the stable area to keep camera framing steady when transient car controls appear.
    val area = Rect(if (!stableArea.isEmpty) stableArea else visibleArea)
    presentation.cameraPadding =
      if (area.isEmpty || !area.intersect(0, 0, width, height)) PaddingValues(0.dp)
      else
        PaddingValues.Absolute(
          left = (area.left / density).dp,
          top = (area.top / density).dp,
          right = ((width - area.right) / density).dp,
          bottom = ((height - area.bottom) / density).dp,
        )
  }

  private fun carConfiguration(configuration: Configuration): Configuration =
    Configuration(configuration).apply {
      uiMode =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
          if (carContext.isDarkMode) Configuration.UI_MODE_NIGHT_YES
          else Configuration.UI_MODE_NIGHT_NO
    }
}
