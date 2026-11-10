package dev.sluide.servertools

import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.Executor

private val log = System.getLogger(ToolUpdates::class.java.name)

private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")

/**
 * Checks [source] for a newer version of [tool], only when the user asks (Check for Updates; ADR 0144, ADR 0159, no
 * background fetch), off the FX thread on [executor]. A newer version comes with the [dev.sluide.localmodels.ToolRelease]
 * of this platform's file, its size and checksum as the source lists them. A language module's update bean extends it.
 */
open class ToolUpdates(
    private val tool: ServerTool,
    private val source: UpdateSource,
    private val executor: Executor = Executor { NamedThreads.virtual("${tool.name}-update-check", it) },
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) {
    /** The pinned version: what is compared with when none is installed. */
    val pinnedVersion: String get() = tool.pin.version

    /**
     * Asks the source for the latest release and compares it with [installedVersion] (the pin if none is installed);
     * [onResult] gets the answer on the FX thread.
     */
    fun check(
        installedVersion: String?,
        onResult: (UpdateCheck) -> Unit,
    ) {
        executor.execute {
            val result =
                try {
                    checkNow(installedVersion ?: tool.pin.version)
                } catch (_: HttpTimeoutException) {
                    UpdateCheck.Failed("${source.name} did not answer within ${UPDATE_TIMEOUT.inWholeSeconds} s")
                } catch (e: IOException) {
                    UpdateCheck.Failed("Cannot reach ${source.name}: ${e.message ?: e.javaClass.simpleName}")
                } catch (e: Exception) {
                    // Whatever went wrong, the page gets an answer and stops "checking".
                    log.log(System.Logger.Level.ERROR, "${tool.name} update check failed", e)
                    UpdateCheck.Failed("The check failed: ${e.message ?: e.javaClass.simpleName}")
                }
            log.log(System.Logger.Level.INFO, "${tool.name} update check: $result")
            dispatch(Runnable { onResult(result) })
        }
    }

    /** What the source's latest release means compared with [compared]; blocking, may throw as the source does. */
    private fun checkNow(compared: String): UpdateCheck {
        val latest =
            when (val answer = source.latest()) {
                is LatestRelease.Failed -> return UpdateCheck.Failed(answer.message)
                is LatestRelease.Release -> answer
            }
        val version = latest.version
        if (!VERSION.matches(version)) {
            return UpdateCheck.Failed("${source.name}'s latest ${tool.name} release has no usable version")
        }
        if (latest.prerelease || compareVersions(version, compared) <= 0) return UpdateCheck.UpToDate(compared)
        val notes = latest.releaseNotes
        val platform = tool.platform ?: return UpdateCheck.NoBuild(version, notes)
        return when (val found = latest.asset(platform)) {
            is PlatformAsset.Missing -> {
                UpdateCheck.NoBuild(version, notes)
            }

            is PlatformAsset.Failed -> {
                UpdateCheck.Failed(found.message)
            }

            is PlatformAsset.Found -> {
                val newerThanPinned = compareVersions(version, tool.pin.version) > 0
                UpdateCheck.Available(version, tool.release(version, found.asset), newerThanPinned, notes)
            }
        }
    }
}
