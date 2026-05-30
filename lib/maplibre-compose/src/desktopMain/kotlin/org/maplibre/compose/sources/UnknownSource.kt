package org.maplibre.compose.sources

import org.maplibre.kmp.native.style.sources.Source as MLNSource

public actual class UnknownSource(override val impl: MLNSource) : Source()
