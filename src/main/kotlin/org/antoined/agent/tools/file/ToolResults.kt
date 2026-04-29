package org.antoined.agent.tools.file

/**
 * All tool operations return a ToolResult. We serialize to JSON for the LLM but keep
 * the sealed hierarchy for Kotlin consumers (agent code, tests, blackboard binders).
 *
 * Why not throw? LLMs deal better with structured failures than with exception
 * strings, and the planner can condition on result types on the blackboard.
 */
sealed interface ToolResult {
    val ok: Boolean
}

sealed interface ToolFailure : ToolResult {
    override val ok: Boolean get() = false
    val message: String
}

data class PathEscape(val requested: String) : ToolFailure {
    override val message = "Path '$requested' escapes the sandbox root and was refused."
}

data class NotFound(val path: String) : ToolFailure {
    override val message = "Path '$path' does not exist."
}

data class NotAFile(val path: String) : ToolFailure {
    override val message = "Path '$path' is not a regular file."
}

data class NotADirectory(val path: String) : ToolFailure {
    override val message = "Path '$path' is not a directory."
}

data class IoFailure(val path: String, val cause: String) : ToolFailure {
    override val message = "I/O error on '$path': $cause"
}

data class InvalidArgument(override val message: String) : ToolFailure

// --- File content ---

/**
 * The result of reading a file. [truncated] is true when we stopped short of the
 * real end of file; [totalLines] is the file's actual line count (cheap to compute
 * during the single read pass) so the LLM knows what it missed.
 */
data class FileRead(
    val path: String,
    val content: String,
    val totalLines: Int,
    val returnedLines: Int,
    val truncated: Boolean,
    val startLine: Int,
) : ToolResult {
    override val ok = true
}

data class FileWritten(
    val path: String,
    val bytesWritten: Long,
    val created: Boolean,
) : ToolResult {
    override val ok = true
}

// --- Edit ---

data class FileEdited(
    val path: String,
    val replacements: Int,
    val diffPreview: String,
) : ToolResult {
    override val ok = true
}

data class EditNotUnique(
    val path: String,
    val occurrences: Int,
) : ToolFailure {
    override val message =
        "Pattern occurred $occurrences times in '$path'; edit refused. " +
            "Extend `oldString` with surrounding context until it is unique."
}

data class EditNotFound(val path: String) : ToolFailure {
    override val message = "Pattern not found in '$path'."
}

// --- Listing / search ---

data class DirEntry(
    val name: String,
    val path: String,
    val kind: Kind,
    val sizeBytes: Long?,
) {
    enum class Kind { FILE, DIRECTORY, SYMLINK, OTHER }
}

data class DirListing(
    val path: String,
    val entries: List<DirEntry>,
    val truncated: Boolean,
) : ToolResult {
    override val ok = true
}

data class GlobMatches(
    val pattern: String,
    val matches: List<String>,
    val truncated: Boolean,
) : ToolResult {
    override val ok = true
}

data class GrepHit(val path: String, val lineNumber: Int, val line: String)

data class GrepMatches(
    val pattern: String,
    val hits: List<GrepHit>,
    val filesSearched: Int,
    val truncated: Boolean,
) : ToolResult {
    override val ok = true
}

// --- Shell ---

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
) : ToolResult {
    override val ok get() = exitCode == 0 && !timedOut
}
