package org.maplibre.compose.core.source

import org.maplibre.android.style.sources.Source as MLNSource

public actual class UnknownSource(override val impl: MLNSource) : Source()
