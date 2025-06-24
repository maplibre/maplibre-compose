import io.github.frankois944.spmForKmp.utils.ExperimentalSpmForKmpFeature
import java.net.URI

plugins { id("io.github.frankois944.spmForKmp") }

swiftPackageConfig {
  create("spmMaplibre") {
    @OptIn(ExperimentalSpmForKmpFeature::class)
    copyDependenciesToApp = true
    dependency {
      remotePackageVersion(
        url = URI("https://github.com/maplibre/maplibre-gl-native-distribution.git"),
        products = { add("MapLibre", exportToKotlin = true) },
        packageName = "maplibre-gl-native-distribution",
        version = project.properties["maplibreIosVersion"]!!.toString(),
      )
    }
  }
}
