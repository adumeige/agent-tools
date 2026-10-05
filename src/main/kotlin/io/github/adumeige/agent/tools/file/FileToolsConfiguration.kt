package io.github.adumeige.agent.tools.file

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

/**
 * Drop-in Spring configuration for the file tool beans.
 *
 * Each tool is a separate bean so @Action methods can pull in only what they need,
 * via Embabel's tool binding (instance tools, toolObject, or by declaring them
 * in a ToolGroup bean).
 *
 * Configure via application.yml:
 *
 *   antoined:
 *     agent:
 *       tools:
 *         file:
 *           root: /path/to/working/root        # required
 *           bash-binary: /usr/bin/bash         # optional; else EMBABEL_BASH / BASH / PATH
 *           max-write-bytes: 4194304           # optional overrides
 */
@ConfigurationProperties(prefix = "antoined.agent.tools.file")
class FileToolsProperties {
    /** Required. Absolute path to the sandbox root. */
    lateinit var root: String

    /** Optional. If null, bash is resolved from EMBABEL_BASH, BASH, then PATH. */
    var bashBinary: String? = null

    var maxWriteBytes: Long = 4 * 1024 * 1024
    var bashTimeoutSeconds: Long = 60
    var bashMaxOutputBytes: Int = 256 * 1024
}

@Configuration
@EnableConfigurationProperties(FileToolsProperties::class)
class FileToolsConfiguration(private val props: FileToolsProperties) {

    @Bean
    fun sandboxRoot(): SandboxRoot = SandboxRoot(Paths.get(props.root))

    @Bean
    fun fileReadTools(root: SandboxRoot) = FileReadTools(root)

    @Bean
    fun fileWriteTools(root: SandboxRoot) =
        FileWriteTools(root, maxWriteBytes = props.maxWriteBytes)

    @Bean
    fun fileSearchTools(root: SandboxRoot) = FileSearchTools(root)

    @Bean
    fun bashTool(root: SandboxRoot): BashTool = BashTool(
        sandbox = root,
        bashBinary = props.bashBinary?.let { Paths.get(it) },
        defaultTimeout = java.time.Duration.ofSeconds(props.bashTimeoutSeconds),
        maxOutputBytes = props.bashMaxOutputBytes,
    )
}
