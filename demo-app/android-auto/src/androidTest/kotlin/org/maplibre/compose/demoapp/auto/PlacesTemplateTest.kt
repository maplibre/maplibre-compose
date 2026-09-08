package org.maplibre.compose.demoapp.auto

import androidx.car.app.OnDoneCallback
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.serialization.Bundleable
import androidx.car.app.testing.TestCarContext
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real Car App model builders enforce constraints even without an Android Auto connection. */
@RequiresCarApi(7)
class PlacesTemplateTest {
  @Test
  fun loading_places_and_error_templates_satisfy_the_car_host_model_constraints() = onMain {
    val templates = templates()
    val loading = templates.build(PLACES.first(), loading = true, error = false)
    assertTrue(assertIs<MessageTemplate>(loading.contentTemplate).isLoading)
    val error = templates.build(PLACES.first(), loading = true, error = true)
    assertFalse(assertIs<MessageTemplate>(error.contentTemplate).isLoading)
    val ready = templates.build(PLACES.first(), loading = false, error = false)
    val list = assertIs<ListTemplate>(ready.contentTemplate)
    assertEquals(PLACES.size, assertNotNull(list.singleList).items.size)
    for (template in listOf(loading, ready, error)) {
      val actions = assertNotNull(assertNotNull(template.mapController).mapActionStrip).actions
      assertEquals(Action.PAN, actions.first())
      assertEquals(4, actions.size)
      assertTrue(actions.drop(1).all { it.icon != null && it.title == null })
      assertNull(template.actionStrip)
      val header =
        when (val content = template.contentTemplate) {
          is ListTemplate -> content.header
          is MessageTemplate -> content.header
          else -> error("Unexpected content template")
        }
      val credits = assertNotNull(header).endHeaderActions.single()
      assertNotNull(credits.icon)
      assertTrue(assertNotNull(credits.onClickDelegate).isParkedOnly)
    }
  }

  @Test
  fun host_delegates_select_places_and_forward_map_controls() = onMain {
    val events = mutableListOf<String>()
    val template = templates(events).build(PLACES.first(), loading = false, error = false)
    val rows = assertNotNull(assertIs<ListTemplate>(template.contentTemplate).singleList).items
    val row = assertIs<Row>(rows[1])
    dispatch { assertNotNull(row.onClickDelegate).sendClick(it) }
    val controller = assertNotNull(template.mapController)
    val actions = assertNotNull(controller.mapActionStrip).actions
    actions.drop(1).forEach { action ->
      dispatch { assertNotNull(action.onClickDelegate).sendClick(it) }
    }
    val credits =
      assertNotNull(assertIs<ListTemplate>(template.contentTemplate).header)
        .endHeaderActions
        .single()
    dispatch { assertNotNull(credits.onClickDelegate).sendClick(it) }
    assertEquals(
      listOf("place:${PLACES[1].name}", "zoom:1", "zoom:-1", "recenter", "credits"),
      events,
    )
  }

  private fun templates(events: MutableList<String> = mutableListOf()): PlacesTemplate {
    val context =
      TestCarContext.createCarContext(InstrumentationRegistry.getInstrumentation().targetContext)
    return PlacesTemplate(
      context,
      onSelect = { events += "place:${it.name}" },
      onCredits = { events += "credits" },
      onZoom = { events += "zoom:$it" },
      onRecenter = { events += "recenter" },
    )
  }

  private fun dispatch(action: (OnDoneCallback) -> Unit) {
    var completed = false
    action(
      object : OnDoneCallback {
        override fun onSuccess(response: Bundleable?) {
          completed = true
        }

        override fun onFailure(response: Bundleable) {
          throw AssertionError("Car action failed: ${response.get()}")
        }
      }
    )
    assertTrue(completed, "The car action did not acknowledge its callback")
  }

  private fun onMain(action: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
  }
}
