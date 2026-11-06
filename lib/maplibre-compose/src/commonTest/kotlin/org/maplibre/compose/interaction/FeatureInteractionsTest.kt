package org.maplibre.compose.interaction

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureInteractionsTest {
  @Test
  fun rows_require_layer_ids_and_finite_nonnegative_padding() {
    for (padding in listOf((-1).dp, Float.NaN.dp, Float.POSITIVE_INFINITY.dp)) {
      val error =
        assertFailsWith<IllegalArgumentException> {
          FeatureInteractionsBuilder().hitPadding = padding
        }
      assertTrue("was $padding" in error.message.orEmpty(), error.message)
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions { callbacks { features { layers { onClick { ClickResult.Pass } } } } }
    }
    val blank =
      assertFailsWith<IllegalArgumentException> {
        MapInteractions {
          callbacks { features { layers("roads", " ") { onClick { ClickResult.Pass } } } }
        }
      }
    assertTrue("[roads,  ]" in blank.message.orEmpty(), blank.message)
  }

  @Test
  fun rows_are_inherited_unless_a_features_block_replaces_them() {
    val original = MapInteractions {
      callbacks { features { layers("front", "front") { onClick { ClickResult.Consume } } } }
    }
    assertEquals(setOf("front"), original.callbacks.features.single().layerIds)
    val inherited =
      MapInteractions(original) { callbacks { click { onEvent { ClickResult.Pass } } } }
    assertEquals(original.callbacks.features, inherited.callbacks.features)
    val cleared = MapInteractions(original) { callbacks { features {} } }
    assertEquals(emptyList(), cleared.callbacks.features)
  }
}
