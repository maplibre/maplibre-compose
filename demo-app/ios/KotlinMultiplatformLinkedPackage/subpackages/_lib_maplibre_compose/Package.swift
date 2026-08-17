// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "_lib_maplibre_compose",
  platforms: [
    .iOS("15.0"),
  ],
  products: [
    .library(
      name: "_lib_maplibre_compose",
      type: .none,
      targets: ["_lib_maplibre_compose"]
    ),
  ],
  dependencies: [
    .package(
      url: "https://github.com/maplibre/maplibre-gl-native-distribution.git",
      exact: "6.28.0"
    ),
  ],
  targets: [
    .target(
      name: "_lib_maplibre_compose",
      dependencies: [
        .product(
          name: "MapLibre",
          package: "maplibre-gl-native-distribution"
        ),
      ]
    ),
  ]
)
