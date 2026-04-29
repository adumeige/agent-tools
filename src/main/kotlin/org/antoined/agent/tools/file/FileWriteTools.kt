package org.antoined.agent.tools.file

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.core.AgentProcess
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.*

/**
 * Mutating file operations. Keep this bean behind a separate tool group from
 * FileReadTools so actions that should only observe don't get write capability
 * smuggled in.
 *
 * Edit semantics: `editFile` requires oldString to occur exactly once in the
 * file. The LLM should extend oldString with surrounding context until it is
 * unique. This rules out a whole class of silent-corruption failures.
 */
class FileWriteTools(
    private val sandbox: SandboxRoot,
    private val maxWriteBytes: Long = 4 * 1024 * 1024, // 4 MiB hard cap per write
) {

    @LlmTool(
        description =
            "Write or overwrite a UTF-8 text file. Creates parent directories. " +
                    "Refuses writes larger than the configured limit. Returns whether the " +
                    "file was newly created."
    )
    fun writeFile(path: String, content: String): ToolResult {
        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxWriteBytes) {
            return InvalidArgument(
                "Refusing to write ${bytes.size} bytes; limit is $maxWriteBytes. " +
                        "Split into smaller writes or raise the limit in FileWriteTools config."
            )
        }
        return try {
            val created = !resolved.exists()
            resolved.createParentDirectories()
            resolved.writeText(content, Charsets.UTF_8)
            FileWritten(
                path = sandbox.relativize(resolved),
                bytesWritten = bytes.size.toLong(),
                created = created,
            ).also { AgentProcess.get()?.addObject(it) }
        } catch (e: IOException) {
            IoFailure(path, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @LlmTool(
        description =
            "Replace oldString with newString in a file. oldString must occur exactly " +
                    "once; include enough surrounding context to make the match unique. " +
                    "Returns the number of replacements and a short unified-diff-style preview."
    )
    fun editFile(
        path: String,
        oldString: String,
        newString: String,
    ): ToolResult {
        if (oldString.isEmpty()) return InvalidArgument("oldString must not be empty")
        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        if (!resolved.exists()) return NotFound(path)
        if (!resolved.isRegularFile()) return NotAFile(path)

        return try {
            val before = resolved.readText(Charsets.UTF_8)
            val occurrences = countOccurrences(before, oldString)
            when {
                occurrences == 0 -> EditNotFound(path)
                occurrences > 1 -> EditNotUnique(path, occurrences)
                else -> {
                    val after = before.replace(oldString, newString)
                    resolved.writeText(after, Charsets.UTF_8)
                    FileEdited(
                        path = sandbox.relativize(resolved),
                        replacements = 1,
                        diffPreview = renderPreview(oldString, newString),
                    ).also { AgentProcess.get()?.addObject(it) }
                }
            }
        } catch (e: IOException) {
            IoFailure(path, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @LlmTool(description = "Create an empty directory (and parents as needed).")
    fun mkdir(path: String): ToolResult {
        val resolved = sandbox.resolve(path) ?: return PathEscape(path)
        return try {
            Files.createDirectories(resolved)
            DirListing(
                path = sandbox.relativize(resolved),
                entries = emptyList(),
                truncated = false,
            )
        } catch (e: IOException) {
            IoFailure(path, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) return count
            count++
            idx = found + needle.length
        }
    }

    private fun renderPreview(old: String, new: String): String {
        val oldPreview = old.lines().take(3).joinToString("\n")
        val newPreview = new.lines().take(3).joinToString("\n")
        return buildString {
            append("- ").append(oldPreview.replace("\n", "\n- "))
            if (old.lines().size > 3) append("\n- …")
            append('\n')
            append("+ ").append(newPreview.replace("\n", "\n+ "))
            if (new.lines().size > 3) append("\n+ …")
        }
    }
}
