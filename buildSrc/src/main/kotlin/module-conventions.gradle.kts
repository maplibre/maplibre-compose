import fr.brouillard.oss.jgitver.Strategies

plugins { id("fr.brouillard.oss.gradle.jgitver") }

group = "org.maplibre.compose"

jgitver {
  strategy(Strategies.MAVEN)
  nonQualifierBranches("main")
}

tasks.withType<AbstractTestTask>().configureEach { failOnNoDiscoveredTests = false }

// Desktop tests may load the MapLibre Native FFI runtime, which needs native access.
tasks.withType<Test>().configureEach {
  if (name.startsWith("desktop")) jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}
