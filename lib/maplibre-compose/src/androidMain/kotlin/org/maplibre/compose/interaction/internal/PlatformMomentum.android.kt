package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.MomentumPlanner

/** Android's fling, which needs `ViewConfiguration`'s minimum fling velocity of 50 dp/s. */
internal actual val PlatformPanDecay: MomentumDecay =
  MomentumDecay.Ease(MomentumPlanner.AndroidFling, 50.0, Double.POSITIVE_INFINITY)
