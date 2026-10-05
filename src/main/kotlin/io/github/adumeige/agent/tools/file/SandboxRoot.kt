package io.github.adumeige.agent.tools.file

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A rooted sandbox. All tool operations resolve paths against this root and refuse
 * to escape it. Paths handed to tools are always interpreted as relative to the root,
 * even if the LLM sends something that looks absolute — we strip the leading separator
 * rather than honor it.
 */
class SandboxRoot(root: Path) {

    val root: Path = root.absolute().normalize().also {
        // Create the sandbox root if it doesn't exist yet, so a fresh environment
        // (e.g. a clean /tmp) works without a manual mkdir.
        if (!it.exists()) it.createDirectories()
        require(it.isDirectory()) { "Sandbox root must be an existing directory: $it" }
    }
    private val realRoot: Path = this.root.toRealPath()

    /**
     * Resolve a user-supplied path against the root, guaranteeing the result stays
     * inside the root. Returns null if the path escapes (via .., symlink tricks, or
     * absolute paths pointing elsewhere).
     */
    fun resolve(userPath: String): Path? {
        if (userPath.isBlank()) return root
        // Strip leading separators so "/foo" and "foo" both mean root/foo.
        val cleaned = userPath.trimStart('/', '\\')
        val candidate = root.resolve(cleaned).normalize().absolute()
        if (!candidate.startsWith(root)) return null

        // Normalize lexically first, then verify the real existing path or nearest
        // existing parent so symlinks cannot redirect reads/writes outside the root.
        val realAnchor = nearestExistingPath(candidate)?.toRealPath() ?: return null
        return if (realAnchor.startsWith(realRoot)) candidate else null
    }

    private fun nearestExistingPath(path: Path): Path? {
        var current: Path? = path
        while (current != null && !Files.exists(current)) {
            current = current.parent
        }
        return current
    }

    /** Render a path back to the LLM as relative to the root, using forward slashes. */
    fun relativize(p: Path): String {
        val rel = root.relativize(p.absolute().normalize()).toString()
        return if (rel.isEmpty()) "." else rel.replace('\\', '/')
    }
}
