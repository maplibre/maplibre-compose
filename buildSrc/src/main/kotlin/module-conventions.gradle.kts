import fr.brouillard.oss.jgitver.Strategies

plugins { id("fr.brouillard.oss.gradle.jgitver") }

group = "org.maplibre.compose"

jgitver {
  strategy(Strategies.MAVEN)
  nonQualifierBranches("main")
}

tasks.withType<AbstractTestTask>().configureEach { failOnNoDiscoveredTests = false }
