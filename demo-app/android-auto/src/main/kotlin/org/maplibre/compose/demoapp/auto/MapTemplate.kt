package org.maplibre.compose.demoapp.auto

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat

/** A full-screen map with the host's pan, zoom, recenter, and attribution controls. */
internal class MapTemplate(
  private val carContext: CarContext,
  private val onCredits: () -> Unit,
  private val onZoom: (Int) -> Unit,
  private val onRecenter: () -> Unit,
) {
  fun build(error: Boolean): Template {
    if (error) {
      return MessageTemplate.Builder(carContext.getString(R.string.map_unavailable))
        .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).build())
        .build()
    }
    return NavigationTemplate.Builder()
      .setActionStrip(
        ActionStrip.Builder()
          .addAction(Action.APP_ICON)
          .addAction(
            Action.Builder()
              .setTitle(carContext.getString(R.string.map_credits))
              .setIcon(icon(R.drawable.info_24px))
              .setOnClickListener(ParkedOnlyOnClickListener.create { onCredits() })
              .build()
          )
          .build()
      )
      .setMapActionStrip(
        ActionStrip.Builder()
          .addAction(Action.PAN)
          .addAction(mapAction(R.drawable.add_24px) { onZoom(1) })
          .addAction(mapAction(R.drawable.remove_24px) { onZoom(-1) })
          .addAction(mapAction(R.drawable.filter_center_focus_24px, onRecenter))
          .build()
      )
      // Required with Action.PAN; the host forwards gestures to the SurfaceCallback.
      .setPanModeListener {}
      .build()
  }

  private fun icon(@DrawableRes resource: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(carContext, resource)).build()

  private fun mapAction(@DrawableRes resource: Int, action: () -> Unit): Action =
    Action.Builder().setIcon(icon(resource)).setOnClickListener(action).build()
}
