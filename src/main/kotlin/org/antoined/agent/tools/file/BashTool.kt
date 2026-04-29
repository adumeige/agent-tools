package org.antoined.agent.tools.file

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.core.AgentProcess
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Bash tool. Unlike naive `Runtime.exec(cmd)` or shelling out to the system's
 * default interpreter, this tool resolves a concrete `bash` binary and invokes
 * it directly as argv: [bash, -c, command]. This matters because:
 *
 *  - On Windows with an opencode/agent host that defaults to cmd or PowerShell,
 *    `Runtime.exec("ls")` would go through cmd.exe and break anything
 *    bash-specific (heredocs, process substitution, $'...' quoting, etc).
 *  - On macOS, the user's default shell is often zsh, which differs from bash
 *    in word splitting, array handling, and history expansion.
 *  - On fresh CI containers, sh may be dash, which is not bash.
 *
 * Bash is located in this precedence order:
 *
 *   1. Explicit constructor arg (`bashBinary`)
 *   2. Environment variable `EMBABEL_BASH`
 *   3. Environment variable `BASH`
 *   4. First `bash` on PATH (or `bash.exe` on Windows — e.g. Git Bash,
 *      WSL's bash.exe, MSYS2)
 *
 * If none is found, construction fails fast rather than silently falling back.
 */
class BashTool(
    private val sandbox: SandboxRoot,
    bashBinary: Path? = null,
    private val defaultTimeout: java.time.Duration = java.time.Duration.ofSeconds(60),
    private val maxOutputBytes: Int = 256 * 1024,
    private val envAllowlist: Set<String> = DEFAULT_ENV_ALLOWLIST,
    private val extraEnv: Map<String, String> = emptyMap(),
) {

    private val bash: Path = bashBinary?.takeIf { it.exists() && it.isExecutable() }
        ?: resolveBash()
        ?: error(
            "No usable bash binary found. Set EMBABEL_BASH or BASH, " +
                    "install bash on PATH, or pass bashBinary explicitly."
        )

    @LlmTool(
        description =
            "Run a bash command in the working root. The command is passed to bash -c " +
                    "so you can use pipes, heredocs, and shell features freely. " +
                    "The working directory is the sandbox root. " +
                    "Stdout and stderr are captured separately and truncated if large. " +
                    "Returns exit code, stdout, stderr, and whether the command timed out. " +
                    "Default timeout is 60 seconds; pass timeoutSeconds to override (max 600)."
    )
    fun bash(command: String, timeoutSeconds: Int = 0): ToolResult {
        if (command.isBlank()) return InvalidArgument("Command must not be blank")
        // A zero/omitted timeout means use the constructor default; positive values are LLM-supplied seconds.
        val effectiveTimeoutMillis = if (timeoutSeconds <= 0) {
            defaultTimeout.toMillis().coerceAtLeast(1)
        } else {
            java.time.Duration.ofSeconds(timeoutSeconds.coerceAtMost(600).toLong()).toMillis()
        }

        val pb = ProcessBuilder(bash.toAbsolutePath().toString(), "-c", command)
            .directory(sandbox.root.toFile())

        // Scrub the environment: start from empty, then add allowlisted vars
        // from the parent process, then add extras. Prevents an agent from
        // leaking secrets present in the parent environment.
        val env = pb.environment()
        val parent = System.getenv().toMap()
        env.clear()
        for (name in envAllowlist) {
            parent[name]?.let { env[name] = it }
        }
        env.putAll(extraEnv)
        // Normalize PATH so the command itself can find common binaries.
        if ("PATH" !in env && "PATH" in parent) env["PATH"] = parent["PATH"]!!

        return try {
            val process = pb.start()
            process.outputStream.close() // no stdin

            val stdoutReader = StreamCapture(process.inputStream, maxOutputBytes).start()
            val stderrReader = StreamCapture(process.errorStream, maxOutputBytes).start()

            val finished = process.waitFor(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                val descendants = process.toHandle().descendants().toList()
                // Kill descendants too; otherwise background jobs can keep pipes open after bash exits.
                descendants.forEach { it.destroy() }
                process.destroy()
                Thread.sleep(200)
                descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                descendants.forEach { descendant ->
                    if (descendant.isAlive) {
                        try {
                            descendant.onExit().get(2, TimeUnit.SECONDS)
                        } catch (_: Exception) {
                            descendant.destroyForcibly()
                        }
                    }
                }
            }
            val stdout = stdoutReader.join()
            val stderr = stderrReader.join()

            ShellResult(
                command = command,
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.text,
                stderr = stderr.text,
                timedOut = !finished,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
            ).also { AgentProcess.get()?.addObject(it) }
        } catch (e: IOException) {
            IoFailure("bash", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Report which bash the tool resolved to; useful for debugging and tests. */
    fun resolvedBashPath(): Path = bash

    // --- internals ---

    private class StreamCapture(
        private val stream: java.io.InputStream,
        private val cap: Int,
    ) {
        data class Captured(val text: String, val truncated: Boolean)

        private lateinit var thread: Thread
        private var result: Captured = Captured("", false)

        fun start(): StreamCapture {
            thread = Thread {
                val buf = ByteArray(4096)
                val out = java.io.ByteArrayOutputStream()
                var truncated = false
                stream.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        val remaining = cap - out.size()
                        if (remaining <= 0) {
                            truncated = true
                            // Drain silently so the child process isn't blocked on a full pipe.
                            while (s.read(buf) >= 0) { /* discard */
                            }
                            break
                        }
                        out.write(buf, 0, minOf(n, remaining))
                        if (n > remaining) truncated = true
                    }
                }
                result = Captured(out.toString(StandardCharsets.UTF_8), truncated)
            }
            thread.isDaemon = true
            thread.start()
            return this
        }

        fun join(): Captured {
            thread.join()
            return result
        }
    }

    companion object {
        /** Minimal env exposed to bash by default. Override via constructor if needed. */
        val DEFAULT_ENV_ALLOWLIST: Set<String> = setOf(
            "PATH", "HOME", "USER", "LANG", "LC_ALL", "LC_CTYPE",
            "TZ", "TMPDIR", "TEMP", "TMP",
        )

        private fun resolveBash(): Path? {
            System.getenv("EMBABEL_BASH")?.let { tryPath(it)?.also { return it } }
            System.getenv("BASH")?.let { tryPath(it)?.also { return it } }

            val pathEnv = System.getenv("PATH") ?: return null
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val candidates = if (isWindows) listOf("bash.exe", "bash") else listOf("bash")
            val sep = java.io.File.pathSeparator

            for (dir in pathEnv.split(sep)) {
                if (dir.isBlank()) continue
                for (name in candidates) {
                    val candidate = Paths.get(dir, name)
                    if (candidate.exists() && candidate.isExecutable()) return candidate
                }
            }

            // Fallbacks for the common Windows bash installs that may not be on PATH.
            if (isWindows) {
                val common = listOf(
                    "C:\\Program Files\\Git\\bin\\bash.exe",
                    "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                    "C:\\Windows\\System32\\bash.exe", // WSL
                    "C:\\msys64\\usr\\bin\\bash.exe",
                )
                for (c in common) {
                    val p = Paths.get(c)
                    if (p.exists() && Files.isRegularFile(p)) return p
                }
            }
            return null
        }

        private fun tryPath(s: String): Path? {
            val p = Paths.get(s)
            return if (p.exists() && p.isExecutable()) p else null
        }
    }
}
