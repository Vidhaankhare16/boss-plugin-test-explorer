package ai.rever.boss.plugin.dynamic.testexplorer.engine

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Run [command] in [directory], forwarding each line of its merged stdout/stderr to [onOutput], and
 * return its exit code.
 *
 * Cancelling the calling coroutine destroys the whole process tree, not just the direct child. The
 * direct child is rarely the test runner: on Windows it is `cmd /c`, which starts `gradlew.bat`,
 * which starts the Gradle client JVM, and pytest and Maven are launched the same way. Killing only
 * `cmd` left the build running to completion and, because the survivors still held the output pipe,
 * left the reader below blocked on it.
 */
internal suspend fun runProcess(
    command: List<String>,
    directory: File,
    onOutput: (String) -> Unit,
): Int =
    suspendCancellableCoroutine { continuation ->
        val process =
            ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .start()

        continuation.invokeOnCancellation { destroyTree(process) }

        // One reader thread drains the merged stdout/stderr so the pipe never fills and stalls
        // the child, forwarding each line to the panel's live log.
        val pump =
            CoroutineScope(Dispatchers.IO).launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(onOutput)
                }
            }

        CoroutineScope(Dispatchers.IO).launch {
            val code = process.waitFor()
            pump.join()
            continuation.resumeIfActive(code)
        }
    }

/**
 * Forcibly end [process] and everything it started.
 *
 * The descendants are listed before anything is killed: once a parent is gone, Windows no longer
 * links its children to it, so they could not be found afterwards. A Gradle daemon that this run
 * itself started is a descendant too and ends with it, so the next run starts a fresh daemon; a
 * daemon that was already running belongs to no process here and is left alone.
 */
internal fun destroyTree(process: Process) {
    val descendants = process.toHandle().descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

private fun CancellableContinuation<Int>.resumeIfActive(value: Int) {
    if (isActive) resume(value)
}
