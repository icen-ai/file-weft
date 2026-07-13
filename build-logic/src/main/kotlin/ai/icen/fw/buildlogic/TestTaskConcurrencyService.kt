package ai.icen.fw.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Shared build-service semaphore used to bound concurrently running JVM test tasks.
 *
 * The service intentionally has no mutable state. Gradle enforces the configured
 * maxParallelUsages value for every Test task that declares this service.
 */
abstract class TestTaskConcurrencyService : BuildService<BuildServiceParameters.None>
