package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.engine.runProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stopping a run against real processes, because the defect it pins only exists in a real process
 * tree: the runner is always started through a launcher (`cmd /c` on Windows, a wrapper script
 * elsewhere), so killing only the direct child used to leave the actual test run going.
 */
class ProcessRunTest {

    @TempDir
    lateinit var dir: Path

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** A launcher that stays alive as the parent of a long-running grandchild. */
    private val launcherWithGrandchild =
        if (isWindows) {
            listOf("cmd", "/c", "ping -n 120 127.0.0.1 > nul")
        } else {
            // `; true` keeps sh from exec-ing into sleep, so sleep stays a grandchild.
            listOf("sh", "-c", "sleep 120; true")
        }

    private fun spawnedSince(before: Set<Long>): List<ProcessHandle> =
        ProcessHandle.current().descendants().toList().filter { it.pid() !in before }

    @Test
    fun `stopping a run ends the launcher and everything it started`(): Unit =
        runBlocking {
            val before = ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()
            val run = async(Dispatchers.IO) { runProcess(launcherWithGrandchild, dir.toFile()) {} }

            // Wait until the grandchild exists, so the stop below has a tree to end.
            val tree =
                withTimeout(15_000) {
                    var spawned = spawnedSince(before)
                    while (spawned.size < 2) {
                        Thread.sleep(50)
                        spawned = spawnedSince(before)
                    }
                    spawned
                }

            run.cancelAndJoin()

            val survivors =
                tree.filter { handle ->
                    runCatching { handle.onExit().get(10, TimeUnit.SECONDS) }
                    handle.isAlive
                }
            // Clean up before asserting, so a failure does not leak a two-minute process.
            survivors.forEach { it.destroyForcibly() }
            assertTrue(
                survivors.isEmpty(),
                "still running after stop: " + survivors.joinToString { "${it.pid()} ${it.info().command().orElse("?")}" },
            )
        }

    @Test
    fun `a run that finishes returns the exit code and forwards its output`(): Unit =
        runBlocking {
            val command = if (isWindows) listOf("cmd", "/c", "echo hello& exit 3") else listOf("sh", "-c", "echo hello; exit 3")
            val lines = mutableListOf<String>()

            val code = withTimeout(15_000) { runProcess(command, dir.toFile()) { lines += it } }

            assertEquals(3, code)
            assertEquals(listOf("hello"), lines.map { it.trim() })
        }
}
