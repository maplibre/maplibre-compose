// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "KotlinMultiplatformLinkedPackage",
  platforms: [
    .iOS("15.0"),
  ],
  products: [
    .library(
      name: "KotlinMultiplatformLinkedPackage",
      type: .none,
      targets: ["KotlinMultiplatformLinkedPackage"]
    ),
  ],
  dependencies: [
    .package(path: "subpackages/_lib_maplibre_compose"),
  ],
  targets: [
    .target(
      name: "KotlinMultiplatformLinkedPackage",
      dependencies: [
        .product(
          name: "_lib_maplibre_compose",
          package: "_lib_maplibre_compose"
        ),
      ]
    ),
  ]
)
