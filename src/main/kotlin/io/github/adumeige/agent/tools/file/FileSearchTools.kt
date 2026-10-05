package io.github.adumeige.agent.tools.file

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.core.AgentProcess
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Read-only navigation and search. Safe to compose with FileReadTools for
 * extraction-style actions.
 *
 * Glob uses java.nio's PathMatcher with the "glob:" syntax, which handles
 * `**` for recursive wildcards and normalizes separators. LLMs sometimes send
 * backslashes on Windows-style prompts — we normalize those to forward slashes
 * before matching.
 */
class FileSearchTools(
    private val sandbox: SandboxRoot,
    private val limits: SearchLimits = SearchLimits(),
) {

    data class SearchLimits(
        val maxListEntries: Int = 500,
        val maxGlobMatches: Int = 500,
        val maxGrepHits: Int = 200,
        val maxGrepFiles: Int = 5_000,
        val maxGrepLineLength: Int = 400,
        val skipFileLargerThanBytes: Long = 2 * 1024 * 1024, // skip big files in grep
    )

    @LlmTool(
        description =
            "List immediate entries (files and subdirectories) of a directory, " +
                    "relative to the working root. Pass '.' or '' for the root itself."
    )
    fun listDirectory(path: String = "."): ToolResult {
        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        if (!Files.exists(resolved)) return NotFound(path)
        if (!resolved.isDirectory()) return NotADirectory(path)

        return try {
            val entries = mutableListOf<DirEntry>()
            var truncated = false
            Files.list(resolved).use { stream ->
                stream.asSequence()
                    .sortedBy { it.name }
                    .forEach { p ->
                        if (entries.size >= limits.maxListEntries) {
                            truncated = true
                            return@forEach
                        }
                        entries += toDirEntry(p)
                    }
            }
            DirListing(
                path = sandbox.relativize(resolved),
                entries = entries,
                truncated = truncated,
            )
        } catch (e: IOException) {
            IoFailure(path, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @LlmTool(
        description =
            "Find files matching a glob pattern, recursively from the working root. " +
                    "Pattern syntax is Java NIO glob: '*' matches within a segment, " +
                    "'**' matches across segments, '?' matches one char. " +
                    "Examples: '**/*.kt', 'src/**/test/**/*.java', '*.md'."
    )
    fun glob(pattern: String): ToolResult {
        if (pattern.isBlank()) return InvalidArgument("Pattern must not be blank")
        val normalized = pattern.replace('\\', '/')
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$normalized")
        } catch (e: IllegalArgumentException) {
            return InvalidArgument("Invalid glob pattern: ${e.message}")
        }

        return try {
            val matches = mutableListOf<String>()
            var truncated = false
            // Do not FOLLOW_LINKS here: directory symlinks may point outside the sandbox.
            Files.walk(sandbox.root).use { stream ->
                for (p in stream) {
                    if (p == sandbox.root) continue
                    val rel = sandbox.root.relativize(p)
                    // Match against relative path with forward-slash form.
                    val asString = rel.toString().replace('\\', '/')
                    val matchPath = FileSystems.getDefault().getPath(asString)
                    if (matcher.matches(matchPath) || matcher.matches(rel)) {
                        if (matches.size >= limits.maxGlobMatches) {
                            truncated = true
                            break
                        }
                        matches += sandbox.relativize(p)
                    }
                }
            }
            GlobMatches(
                pattern = pattern,
                matches = matches.sorted(),
                truncated = truncated,
            ).also { AgentProcess.get()?.addObject(it) }
        } catch (e: IOException) {
            IoFailure(pattern, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @LlmTool(
        description =
            "Search file contents with a regular expression. " +
                    "`pathGlob` optionally restricts which files are searched " +
                    "(e.g. '**/*.kt'); omit or use '**' to search everything. " +
                    "Large and binary-looking files are skipped. Returns at most " +
                    "a capped number of hits; extend the pattern or narrow pathGlob " +
                    "if results are truncated."
    )
    fun grep(
        pattern: String,
        pathGlob: String = "**",
        caseInsensitive: Boolean = false,
    ): ToolResult {
        if (pattern.isBlank()) return InvalidArgument("Pattern must not be blank")
        val regex = try {
            if (caseInsensitive) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: Exception) {
            return InvalidArgument("Invalid regex: ${e.message}")
        }
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:${pathGlob.replace('\\', '/')}")
        } catch (e: IllegalArgumentException) {
            return InvalidArgument("Invalid pathGlob: ${e.message}")
        }

        val hits = mutableListOf<GrepHit>()
        var filesSearched = 0
        var truncated = false

        return try {
            // Do not FOLLOW_LINKS here: directory symlinks may point outside the sandbox.
            Files.walk(sandbox.root).use { stream ->
                for (p in stream) {
                    if (hits.size >= limits.maxGrepHits ||
                        filesSearched >= limits.maxGrepFiles
                    ) {
                        truncated = true
                        break
                    }
                    if (!p.isRegularFile()) continue
                    val rel = sandbox.root.relativize(p)
                    if (!matcher.matches(rel)) continue
                    if (p.fileSize() > limits.skipFileLargerThanBytes) continue
                    if (looksBinary(p)) continue

                    filesSearched++
                    searchOneFile(p, regex, hits)
                }
            }
            GrepMatches(
                pattern = pattern,
                hits = hits,
                filesSearched = filesSearched,
                truncated = truncated || hits.size >= limits.maxGrepHits,
            ).also { AgentProcess.get()?.addObject(it) }
        } catch (e: IOException) {
            IoFailure(pattern, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun searchOneFile(p: Path, regex: Regex, hits: MutableList<GrepHit>) {
        try {
            Files.newBufferedReader(p, Charsets.UTF_8).use { r ->
                var lineNo = 0
                while (true) {
                    if (hits.size >= limits.maxGrepHits) return
                    val line = r.readLine() ?: return
                    lineNo++
                    if (regex.containsMatchIn(line)) {
                        val clipped = if (line.length > limits.maxGrepLineLength)
                            line.substring(0, limits.maxGrepLineLength) + " …"
                        else line
                        hits += GrepHit(
                            path = sandbox.relativize(p),
                            lineNumber = lineNo,
                            line = clipped,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Non-UTF-8 or unreadable — skip silently, that's the contract of grep.
        }
    }

    private fun looksBinary(p: Path): Boolean {
        // Quick heuristic: any NUL byte in the first 4 KiB means binary.
        return try {
            Files.newInputStream(p).use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) return false
                for (i in 0 until n) if (buf[i].toInt() == 0) return true
                false
            }
        } catch (_: IOException) {
            true // can't read it as text, treat as binary
        }
    }

    private fun toDirEntry(p: Path): DirEntry {
        val kind = when {
            Files.isSymbolicLink(p) -> DirEntry.Kind.SYMLINK
            p.isRegularFile() -> DirEntry.Kind.FILE
            p.isDirectory() -> DirEntry.Kind.DIRECTORY
            else -> DirEntry.Kind.OTHER
        }
        return DirEntry(
            name = p.name,
            path = sandbox.relativize(p),
            kind = kind,
            sizeBytes = if (p.isRegularFile()) p.fileSize() else null,
        )
    }
}
