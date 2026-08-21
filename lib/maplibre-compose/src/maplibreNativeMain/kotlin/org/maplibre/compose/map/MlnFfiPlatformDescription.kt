package org.maplibre.compose.map

/** The name of the operating system this process runs on, or `unknown` where none is reported. */
internal expect val mlnFfiOperatingSystem: String

/** The CPU architecture this process runs on, or `unknown` where none is reported. */
internal expect val mlnFfiArchitecture: String
