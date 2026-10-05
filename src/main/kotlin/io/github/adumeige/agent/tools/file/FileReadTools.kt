package io.github.adumeige.agent.tools.file

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.core.AgentProcess
import java.io.IOException
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * Read-only file operations. Give this to actions that should inspect but not
 * mutate the filesystem (extractors, reviewers, query planners).
 *
 * Tool method signatures are deliberately simple strings and ints — that's what
 * LLMs reliably produce. The sealed ToolResult hierarchy is for the Kotlin side.
 *
 * Note on `@Tool`: the import path here is for embabel-agent. If you're annotating
 * with Spring AI's `org.springframework.ai.tool.annotation.Tool` instead, swap
 * the import — the shapes are compatible.
 */
class FileReadTools(
    private val sandbox: SandboxRoot,
    private val limits: FileReadLimits = FileReadLimits(),
) {

    data class FileReadLimits(
        val maxBytes: Long = 512 * 1024,        // 512 KiB default cap per read
        val maxLines: Int = 2_000,
        val maxLineLength: Int = 2_000,         // truncate absurdly long lines
    )

    @LlmTool(
        description =
            "Read a UTF-8 text file by path relative to the working root. " +
                    "Optional startLine (1-indexed) and lineCount let you read a slice. " +
                    "Returns content plus total line count so you know if output was truncated. " +
                    "Binary files and files exceeding size limits are refused or truncated."
    )
    fun readFile(
        path: String,
        startLine: Int = 1,
        lineCount: Int = Int.MAX_VALUE,
    ): ToolResult {
        if (startLine < 1) return InvalidArgument("startLine must be >= 1")
        if (lineCount < 1) return InvalidArgument("lineCount must be >= 1")

        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        if (!resolved.exists()) return NotFound(path)
        if (!resolved.isRegularFile()) return NotAFile(path)

        return try {
            val totalLines = countLines(resolved)
            val requestedCount = minOf(lineCount, limits.maxLines)
            val sb = StringBuilder()
            var returned = 0
            var bytesUsed = 0L
            var truncatedByBytes = false

            resolved.bufferedReader(Charsets.UTF_8).use { reader ->
                var lineNo = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (lineNo < startLine) continue
                    if (returned >= requestedCount) break

                    val clipped = if (line.length > limits.maxLineLength)
                        line.substring(0, limits.maxLineLength) + " …[line truncated]"
                    else line

                    val separatorBytes = if (returned > 0) 1 else 0
                    val clippedBytes = clipped.toByteArray(Charsets.UTF_8).size
                    // Enforce the read cap in UTF-8 bytes, not Kotlin UTF-16 characters.
                    if (bytesUsed + separatorBytes + clippedBytes > limits.maxBytes) {
                        truncatedByBytes = true
                        break
                    }
                    if (returned > 0) {
                        sb.append('\n')
                        bytesUsed += separatorBytes
                    }
                    sb.append(clipped)
                    bytesUsed += clippedBytes
                    returned++
                }
            }

            val truncated = truncatedByBytes ||
                    (startLine - 1 + returned < totalLines && returned >= requestedCount)

            FileRead(
                path = sandbox.relativize(resolved),
                content = sb.toString(),
                totalLines = totalLines,
                returnedLines = returned,
                truncated = truncated,
                startLine = startLine,
            ).also { bindToBlackboard(it) }
        } catch (e: IOException) {
            IoFailure(path, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @LlmTool(
        description =
            "Return the size in bytes and whether a path exists and is a regular file. " +
                    "Cheap; use this before readFile on unknown files."
    )
    fun stat(path: String): ToolResult {
        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        if (!resolved.exists()) return NotFound(path)
        return DirEntry(
            name = resolved.fileName.toString(),
            path = sandbox.relativize(resolved),
            kind = when {
                resolved.isRegularFile() -> DirEntry.Kind.FILE
                java.nio.file.Files.isDirectory(resolved) -> DirEntry.Kind.DIRECTORY
                java.nio.file.Files.isSymbolicLink(resolved) -> DirEntry.Kind.SYMLINK
                else -> DirEntry.Kind.OTHER
            },
            sizeBytes = if (resolved.isRegularFile()) resolved.fileSize() else null,
        ).let { entry ->
            DirListing(path = entry.path, entries = listOf(entry), truncated = false)
        }
    }

    private fun countLines(path: java.nio.file.Path): Int {
        path.bufferedReader(Charsets.UTF_8).use { r ->
            var n = 0
            while (r.readLine() != null) n++
            return n
        }
    }

    private fun bindToBlackboard(result: ToolResult) {
        // Bind to the current AgentProcess if one is active, so downstream
        // @Action methods can consume the typed result from the blackboard.
        AgentProcess.get()?.addObject(result)
    }
}
