import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serializes the spmForKmp build tasks.
 *
 * They resolve into one SwiftPM cache shared by the whole machine, so two running at once race to
 * download the same binary artifact and the loser fails with `already exists in file system`.
 */
abstract class SwiftPackageBuildService : BuildService<BuildServiceParameters.None>
