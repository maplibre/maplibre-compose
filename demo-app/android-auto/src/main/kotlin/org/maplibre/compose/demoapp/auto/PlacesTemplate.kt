package org.maplibre.compose.demoapp.auto

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Place
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat

/** Builds host-validated car models independently of native map and Surface lifetimes. */
@RequiresCarApi(7)
internal class PlacesTemplate(
  private val carContext: CarContext,
  private val onSelect: (DemoPlace) -> Unit,
  private val onCredits: () -> Unit,
  private val onZoom: (Int) -> Unit,
  private val onRecenter: () -> Unit,
) {
  fun build(selectedPlace: DemoPlace, loading: Boolean, error: Boolean): MapWithContentTemplate {
    val header =
      Header.Builder()
        .setTitle(carContext.getString(R.string.places_title))
        .setStartHeaderAction(Action.APP_ICON)
        .addEndHeaderAction(
          Action.Builder()
            .setTitle(carContext.getString(R.string.map_credits))
            .setIcon(
              CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.info_24px))
                .build()
            )
            .setOnClickListener(ParkedOnlyOnClickListener.create { onCredits() })
            .build()
        )
        .build()
    val content =
      if (error) {
        MessageTemplate.Builder(carContext.getString(R.string.map_unavailable))
          .setHeader(header)
          .build()
      } else if (loading) {
        MessageTemplate.Builder(carContext.getString(R.string.map_loading))
          .setLoading(true)
          .setHeader(header)
          .build()
      } else {
        val places = ItemList.Builder()
        PLACES.forEach { place ->
          places.addItem(
            Row.Builder()
              .setTitle(place.name)
              .addText(
                if (place == selectedPlace) carContext.getString(R.string.shown_on_map)
                else place.description
              )
              .setMetadata(
                Metadata.Builder()
                  .setPlace(
                    Place.Builder(CarLocation.create(place.latitude, place.longitude)).build()
                  )
                  .build()
              )
              .setOnClickListener { onSelect(place) }
              .build()
          )
        }
        ListTemplate.Builder().setHeader(header).setSingleList(places.build()).build()
      }
    return MapWithContentTemplate.Builder()
      .setContentTemplate(content)
      .setMapController(
        MapController.Builder()
          .setMapActionStrip(
            ActionStrip.Builder()
              .addAction(Action.PAN)
              .addAction(mapAction(R.drawable.add_24px) { onZoom(1) })
              .addAction(mapAction(R.drawable.remove_24px) { onZoom(-1) })
              .addAction(
                mapAction(R.drawable.filter_center_focus_24px) {
                  onRecenter()
                }
              )
              .build()
          )
          // Required with Action.PAN; the host forwards pan gestures to the SurfaceCallback.
          .setPanModeListener {}
          .build()
      )
      .build()
  }

  private fun mapAction(@DrawableRes icon: Int, action: () -> Unit): Action =
    Action.Builder()
      .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
      .setOnClickListener(action)
      .build()
}
