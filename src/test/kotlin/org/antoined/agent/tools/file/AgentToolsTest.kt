package org.antoined.agent.tools.file

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.measureTimeMillis
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AgentToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `sandbox resolves paths inside root and refuses traversal`() {
        val sandbox = SandboxRoot(tempDir)

        assertThat(sandbox.resolve(".")).isEqualTo(tempDir.toAbsolutePath().normalize())
        assertThat(sandbox.resolve("nested/file.txt")).isEqualTo(
            tempDir.resolve("nested/file.txt").toAbsolutePath().normalize(),
        )
        assertThat(sandbox.resolve("/absolute-looking.txt")).isEqualTo(
            tempDir.resolve("absolute-looking.txt").toAbsolutePath().normalize(),
        )
        assertThat(sandbox.resolve("../outside.txt")).isNull()
    }

    @Test
    fun `sandbox creates its root when it does not exist yet`() {
        val missing = tempDir.resolve("not-created-yet/workdir")
        assertThat(missing.exists()).isFalse()

        val sandbox = SandboxRoot(missing)

        assertThat(missing.isDirectory()).isTrue()
        assertThat(sandbox.resolve(".")).isEqualTo(missing.toAbsolutePath().normalize())
    }

    @Test
    fun `writeFile creates parent directories and readFile can read slices`() {
        val sandbox = SandboxRoot(tempDir)
        val writer = FileWriteTools(sandbox)
        val reader = FileReadTools(sandbox)

        val written = writer.writeFile("docs/example.txt", "one\ntwo\nthree")

        assertThat(written).isInstanceOf(FileWritten::class.java)
        written as FileWritten
        assertThat(written.path).isEqualTo("docs/example.txt")
        assertThat(written.created).isTrue()
        assertThat(tempDir.resolve("docs/example.txt").readText()).isEqualTo("one\ntwo\nthree")

        val read = reader.readFile("docs/example.txt", startLine = 2, lineCount = 1)

        assertThat(read).isInstanceOf(FileRead::class.java)
        read as FileRead
        assertThat(read.content).isEqualTo("two")
        assertThat(read.totalLines).isEqualTo(3)
        assertThat(read.returnedLines).isEqualTo(1)
        assertThat(read.truncated).isTrue()
        assertThat(read.startLine).isEqualTo(2)
    }

    @Test
    fun `writeFile refuses paths escaping the sandbox and oversized writes`() {
        val sandbox = SandboxRoot(tempDir)
        val writer = FileWriteTools(sandbox, maxWriteBytes = 4)

        assertThat(writer.writeFile("../escape.txt", "oops")).isInstanceOf(PathEscape::class.java)
        assertThat(writer.writeFile("big.txt", "12345")).isInstanceOf(InvalidArgument::class.java)
        assertThat(tempDir.resolve("big.txt").exists()).isFalse()
    }

    @Test
    fun `editFile replaces exactly one occurrence and rejects ambiguous edits`() {
        val sandbox = SandboxRoot(tempDir)
        val writer = FileWriteTools(sandbox)
        tempDir.resolve("notes.txt").writeText("alpha\nbeta\ngamma\nbeta\n")

        assertThat(writer.editFile("notes.txt", "beta", "BETA")).isInstanceOf(EditNotUnique::class.java)
        assertThat(writer.editFile("notes.txt", "missing", "replacement")).isInstanceOf(EditNotFound::class.java)

        val edited = writer.editFile("notes.txt", "alpha\nbeta\ngamma", "alpha\nBETA\ngamma")

        assertThat(edited).isInstanceOf(FileEdited::class.java)
        edited as FileEdited
        assertThat(edited.replacements).isEqualTo(1)
        assertThat(tempDir.resolve("notes.txt").readText()).isEqualTo("alpha\nBETA\ngamma\nbeta\n")
        assertThat(edited.diffPreview).contains("- alpha", "+ alpha")
    }

    @Test
    fun `mkdir creates directories and listDirectory returns sorted entries`() {
        val sandbox = SandboxRoot(tempDir)
        val writer = FileWriteTools(sandbox)
        val search = FileSearchTools(sandbox)

        val created = writer.mkdir("dir/subdir")
        tempDir.resolve("dir/z.txt").writeText("z")
        tempDir.resolve("dir/a.txt").writeText("a")

        assertThat(created).isInstanceOf(DirListing::class.java)
        assertThat(tempDir.resolve("dir/subdir").isDirectory()).isTrue()

        val listing = search.listDirectory("dir")

        assertThat(listing).isInstanceOf(DirListing::class.java)
        listing as DirListing
        assertThat(listing.path).isEqualTo("dir")
        assertThat(listing.entries.map { it.name }).containsExactly("a.txt", "subdir", "z.txt")
        assertThat(listing.entries.first { it.name == "subdir" }.kind).isEqualTo(DirEntry.Kind.DIRECTORY)
    }

    @Test
    fun `glob finds matching files and grep searches text content`() {
        val sandbox = SandboxRoot(tempDir)
        val search = FileSearchTools(sandbox)
        tempDir.resolve("src/main/kotlin").createDirectories()
        tempDir.resolve("src/main/kotlin/App.kt").writeText("fun main() { println(\"Hello Agent\") }\n")
        tempDir.resolve("README.md").writeText("hello readme\n")
        Files.write(tempDir.resolve("binary.bin"), byteArrayOf(1, 0, 2))

        val glob = search.glob("**/*.kt")
        assertThat(glob).isInstanceOf(GlobMatches::class.java)
        glob as GlobMatches
        assertThat(glob.matches).containsExactly("src/main/kotlin/App.kt")

        val grep = search.grep("agent", pathGlob = "**/*.kt", caseInsensitive = true)
        assertThat(grep).isInstanceOf(GrepMatches::class.java)
        grep as GrepMatches
        assertThat(grep.hits).hasSize(1)
        val hit = grep.hits.single()
        assertThat(hit.path).isEqualTo("src/main/kotlin/App.kt")
        assertThat(hit.lineNumber).isEqualTo(1)
        assertThat(hit.line).contains("Hello Agent")
        assertThat(grep.filesSearched).isEqualTo(1)
    }

    @Test
    fun `readFile enforces maxBytes as UTF-8 bytes rather than characters`() {
        val sandbox = SandboxRoot(tempDir)
        val reader = FileReadTools(
            sandbox,
            FileReadTools.FileReadLimits(maxBytes = 5, maxLines = 10, maxLineLength = 100),
        )
        tempDir.resolve("unicode.txt").writeText("ééé")

        val result = reader.readFile("unicode.txt")

        assertThat(result).isInstanceOf(FileRead::class.java)
        result as FileRead
        assertThat(result.content.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(5)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `readFile validates arguments and reports missing or non-file paths`() {
        val sandbox = SandboxRoot(tempDir)
        val reader = FileReadTools(sandbox)
        tempDir.resolve("dir").createDirectories()

        assertThat(reader.readFile("missing.txt")).isInstanceOf(NotFound::class.java)
        assertThat(reader.readFile("dir")).isInstanceOf(NotAFile::class.java)
        assertThat(reader.readFile("missing.txt", startLine = 0)).isInstanceOf(InvalidArgument::class.java)
        assertThat(reader.readFile("missing.txt", lineCount = 0)).isInstanceOf(InvalidArgument::class.java)
    }

    @Test
    fun `bash executes in sandbox with scrubbed environment and captures failures`() {
        val bash = findBash()
        assumeTrue(bash != null, "No bash binary available")

        val sandbox = SandboxRoot(tempDir)
        val tool = BashTool(
            sandbox = sandbox,
            bashBinary = bash,
            envAllowlist = emptySet(),
            extraEnv = mapOf("VISIBLE" to "yes"),
        )

        val result = tool.bash("pwd; printf \"%s\" \"\$VISIBLE\"; printf \"err\" >&2", timeoutSeconds = 5)

        assertThat(result).isInstanceOf(ShellResult::class.java)
        result as ShellResult
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.timedOut).isFalse()
        assertThat(result.stdout.lines().first()).isEqualTo(tempDir.toRealPath().toString())
        assertThat(result.stdout).contains("yes")
        assertThat(result.stderr).isEqualTo("err")
    }

    @Test
    fun `sandbox refuses writes through symlinks that point outside root`() {
        val sandbox = SandboxRoot(tempDir)
        val writer = FileWriteTools(sandbox)
        val outside = Files.createTempDirectory("agent-tools-outside")
        val link = tempDir.resolve("outside-link")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are not supported")
        }

        val result = writer.writeFile("outside-link/escaped.txt", "escaped")

        assertThat(result).isInstanceOf(PathEscape::class.java)
        assertThat(outside.resolve("escaped.txt")).doesNotExist()
    }

    @Test
    fun `glob does not traverse symlinked directories outside root`() {
        val sandbox = SandboxRoot(tempDir)
        val search = FileSearchTools(sandbox)
        val outside = Files.createTempDirectory("agent-tools-outside")
        outside.resolve("secret.txt").writeText("secret")
        try {
            Files.createSymbolicLink(tempDir.resolve("outside-link"), outside)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are not supported")
        }

        val result = search.glob("**/*.txt")

        assertThat(result).isInstanceOf(GlobMatches::class.java)
        result as GlobMatches
        assertThat(result.matches).doesNotContain("outside-link/secret.txt")
    }

    @Test
    fun `bash uses constructor default timeout when caller omits timeoutSeconds`() {
        val bash = findBash()
        assumeTrue(bash != null, "No bash binary available")

        val result = BashTool(
            sandbox = SandboxRoot(tempDir),
            bashBinary = bash,
            defaultTimeout = Duration.ofMillis(100),
        ).bash("sleep 1")

        assertThat(result).isInstanceOf(ShellResult::class.java)
        result as ShellResult
        assertThat(result.timedOut).isTrue()
        assertThat(result.ok).isFalse()
    }

    @Test
    fun `bash timeout tears down descendants without waiting for inherited pipes to close`() {
        val bash = findBash()
        assumeTrue(bash != null, "No bash binary available")
        val pidFile = tempDir.resolve("child.pid")
        lateinit var result: ToolResult

        val elapsed = measureTimeMillis {
            result = BashTool(SandboxRoot(tempDir), bashBinary = bash)
                .bash("sleep 5 & echo \$! > ${pidFile.fileName}; wait", timeoutSeconds = 1)
        }

        assertThat(result).isInstanceOf(ShellResult::class.java)
        result as ShellResult
        assertThat(result.timedOut).isTrue()
        assertThat(elapsed).isLessThan(3_000)

        val childPid = pidFile.readText().trim().toLong()
        Thread.sleep(200)
        assertThat(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)).isFalse()
    }

    @Test
    fun `bash returns timeout result when command exceeds timeout`() {
        val bash = findBash()
        assumeTrue(bash != null, "No bash binary available")

        val result = BashTool(SandboxRoot(tempDir), bashBinary = bash).bash("sleep 2", timeoutSeconds = 1)

        assertThat(result).isInstanceOf(ShellResult::class.java)
        result as ShellResult
        assertThat(result.timedOut).isTrue()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.ok).isFalse()
    }

    private fun findBash(): Path? {
        val candidates = listOf("/bin/bash", "/usr/bin/bash", "/opt/homebrew/bin/bash")
            .map(Path::of) +
            (System.getenv("BASH")?.let { listOf(Path.of(it)) } ?: emptyList())

        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}
