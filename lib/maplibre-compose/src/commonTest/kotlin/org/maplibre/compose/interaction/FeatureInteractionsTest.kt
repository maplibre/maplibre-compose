package org.maplibre.compose.interaction

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureInteractionsTest {
  @Test
  fun rows_require_layer_ids_and_nonnegative_finite_padding() {
    for (padding in listOf((-1).dp, Float.NaN.dp, Float.POSITIVE_INFINITY.dp)) {
      assertFailsWith<IllegalArgumentException> {
        FeatureInteractionsBuilder().apply { hitPadding = padding }.build()
      }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions { callbacks { features { on { click { ClickResult.Pass } } } } }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions { callbacks { features { on(" ") { click { ClickResult.Pass } } } } }
    }
  }

  @Test
  fun inherited_rows_are_kept_unless_the_features_block_replaces_them() {
    val original = MapInteractions {
      callbacks { features { on("front", "front") { click { ClickResult.Consume } } } }
    }
    assertEquals(setOf("front"), original.callbacks.features.single().layerIds)
    val inherited =
      MapInteractions(original) { callbacks { click { onEvent { ClickResult.Pass } } } }
    assertEquals(original.callbacks.features, inherited.callbacks.features)
    val cleared = MapInteractions(original) { callbacks { features {} } }
    assertEquals(emptyList(), cleared.callbacks.features)
  }
}
