# Styling the map

## Acquiring a style

Every MapLibre map requires a style to be displayed. The style is a JSON object
that describes what data to display and how to display it. Typically, vector
tile providers create styles designed to work with their data, and provide them
as a URI. You can also create your own styles using [Maputnik][maputnik], a
visual style editor for MapLibre styles.

There are a variety of free and commercial map tile providers available. See the
[awesome-maplibre][awesome-maplibre] repository for a list of tile providers.
This documentation primarily uses [OpenFreeMap][openfreemap] and
[Protomaps][protomaps]. Both of these are free tile providers with their own
styles.

## Using a style

To use a style, you can pass the `BaseStyle` of your chosen style to the
`MaplibreMap` composable:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Styling.kt:simple"
```

## Dark mode

You can select a style at runtime based on regular Compose logic. MapLibre will
automatically animate the transition between styles. This is handy if you have a
light and a dark mode style:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Styling.kt:dynamic"
```

## Local styles

While styles are typically hosted on the internet, you can also load a local
style with [Compose Multiplatform resources][resources]:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Styling.kt:local"
```

[maputnik]: https://maputnik.github.io/
[awesome-maplibre]:
  https://github.com/maplibre/awesome-maplibre#maptile-providers
[openfreemap]: https://openfreemap.org/
[protomaps]: https://protomaps.com/
[resources]:
  https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources.html
