plugins {
  id("library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.ksp.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Native Kotlin"
    description = "Kotlin bindings for MapLibre Native."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  jvm("desktop") { compilerOptions { jvmTarget = project.getJvmTarget() } }

  sourceSets {
    val desktopMain by getting

    desktopMain.apply { dependencies { implementation(libs.simplejni.annotations) } }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
    }
  }
}

dependencies { add("kspDesktop", libs.simplejni.kprocessor) }

ksp {
  arg(
    "smjni.jnigen.dest.path",
    project(":lib:maplibre-native-bindings-jni")
      .layout
      .buildDirectory
      .dir("generated/simplejni-headers")
      .get()
      .asFile
      .absolutePath,
  )
  arg("smjni.jnigen.own.dest.path", "true")
  arg("smjni.jnigen.output.list.name", "generated_headers.txt")
  arg(
    "smjni.jnigen.expose.extra",
    listOf(
        "java.lang.Double",
        "java.awt.Canvas",
        "javax.swing.SwingUtilities",
        "java.lang.Runnable",
      )
      .joinToString(";"),
  )
}
