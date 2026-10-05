# agent-tools

File and shell tools for [Embabel](https://github.com/embabel/embabel-agent) agents.
Exposes `@Tool`-annotated methods for read/write/edit/list/glob/grep and a
sandboxed bash invocation, with results bound to the agent blackboard as typed
domain objects.

- **Group:** `io.github.adumeige.agent-tools`
- **Artifact:** `agent-tools`
- **Release:** `1.0.0`
- **Kotlin:** 2.3.0 (language/api level 2.3)
- **JVM target:** 21
- **License:** Apache-2.0

## Build

```bash
mvn clean install
```

The Embabel and Spring Boot dependencies are declared `provided`, so the
consuming project supplies versions compatible with its runtime.

## Use

In your Embabel app's `pom.xml`:

```xml
<dependency>
  <groupId>io.github.adumeige.agent-tools</groupId>
  <artifactId>agent-tools</artifactId>
  <version>1.0.0</version>
</dependency>
```

Import the configuration (component scan won't pick it up automatically across
package roots):

```kotlin
import io.github.adumeige.agent.tools.file.FileToolsConfiguration

@SpringBootApplication
@Import(FileToolsConfiguration::class)
class MyAgentApplication
```

`application.yml`:

```yaml
antoined:
  agent:
    tools:
      file:
        root: ${AGENT_WORKDIR:/tmp/agent-workdir}
        # bash-binary: /usr/bin/bash   # optional override
```

Then inject the beans where you need them and bind per `@Action`:

```kotlin
@Agent(description = "Read and summarize code")
class SummarizerAgent(
    private val reads: FileReadTools,
    private val search: FileSearchTools,
) {
    @Action
    fun summarize(input: UserInput, context: OperationContext): Summary =
        context.promptRunner()
            .withToolObject(reads)
            .withToolObject(search)
            .createObject("Summarize the code at ${input.content}", Summary::class.java)
}
```

## What's in it

| File | Tools exposed |
|------|---------------|
| `FileReadTools.kt` | `readFile`, `stat` |
| `FileWriteTools.kt` | `writeFile`, `editFile`, `mkdir` |
| `FileSearchTools.kt` | `listDirectory`, `glob`, `grep` |
| `BashTool.kt` | `bash` (via explicit `[bash, -c, command]`) |
| `SandboxRoot.kt` | path resolution that refuses sandbox escapes |
| `ToolResults.kt` | sealed `ToolResult` / `ToolFailure` hierarchy |
| `FileToolsConfiguration.kt` | Spring beans + `@ConfigurationProperties` |

## Notes

- **`@Tool` import:** code uses `com.embabel.agent.api.annotation.Tool`. If your
  Embabel snapshot moved it, or you prefer Spring AI's
  `org.springframework.ai.tool.annotation.Tool`, swap the import in the four
  tool classes. Signatures are compatible.
- **Bash resolution order:** constructor arg → `EMBABEL_BASH` → `BASH` →
  `PATH` → common Windows locations (Git Bash, WSL, MSYS2). Never goes through
  the system default shell.
- **Per-action scoping:** each tool is a separate bean. Give an action only the
  beans it should be able to call.
- **Blackboard binding:** every successful call does
  `AgentProcess.get()?.addObject(result)`, silently no-op outside a running
  process.


## Maven Central and releases

After release `1.0.0` is published, the dependency above resolves from Maven Central
without extra repository declarations or credentials. This project uses
`io.github.adumeige.agentic-parent:agentic-parent:1.0.0`; publish that parent first.
The Kotlin package root is `io.github.adumeige.agent.tools`. Consumers migrating
from `org.antoined.agent.tools` must update their imports. The Spring configuration
prefix remains `antoined.agent.tools.file` as shown above.

Released artifacts are mirrored to [GitHub Packages](https://github.com/adumeige/agent-tools/packages)
and attached to [GitHub Releases](https://github.com/adumeige/agent-tools/releases).
GitHub Packages requires authenticated Maven downloads; Central is the recommended
source for consumers. Pushes and pull requests verify the library and its sources
and Dokka API documentation; they do not publish snapshots or releases.

### Publish a release

Configure these repository secrets in **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `CENTRAL_USERNAME` | Sonatype Central Portal token username |
| `CENTRAL_PASSWORD` | Sonatype Central Portal token password |
| `GPG_PRIVATE_KEY` | Full ASCII-armored exported private signing key |
| `GPG_PASSPHRASE` | Signing key passphrase |

Reuse the existing Central account token and signing key. The
`io.github.adumeige` namespace must be verified, and the public signing key must be
on a supported keyserver, such as `keyserver.ubuntu.com`. GitHub publishing uses
the built-in `GITHUB_TOKEN`; no extra token secret is needed.

1. Publish `agentic-parent:1.0.0` to Central, then merge this project's changes into
   `main` and check that CI passes.
2. Open **Actions → Build and publish agent-tools → Run workflow**.
3. Select `main` and a new release version, initially `1.0.0`.
4. The workflow creates a versioned release commit, builds and signs once, and
   automatically publishes to Central. It waits up to an hour for publication;
   there is no final portal **Publish** click.
5. It creates an annotated `v<version>` tag and draft GitHub Release, mirrors and
   verifies the same signed POM, JAR, sources, and documentation in GitHub Packages,
   attaches the artifacts, and makes the release public with generated notes.

The tag points to the commit containing release-version POMs; development on
`main` keeps its snapshot version. Tags do not trigger another publication.
Published versions are immutable, so choose a new version for each release.

### Recover an interrupted release

Central and GitHub publication is sequential. If Central succeeds and the GitHub
job fails, use **Re-run failed jobs** on the same Actions run. The original bundle
and release source are retained for 90 days. The mirror skips byte-identical files
already uploaded and refuses conflicting ones. The release stays a draft until
its packages and assets succeed, although the tag may already be visible.

Do not rerun all jobs or start a fresh run for a version already published to
Central. If Central itself fails or times out, inspect its deployment in
[Central Portal](https://central.sonatype.com/publishing/deployments) before retrying;
publication may have continued after the runner stopped. The saved bundle permits
manual recovery without rebuilding.

To verify release artifacts locally without signing or uploading:

```bash
mvn -Pcentral-release verify -Dgpg.skip=true
```

A local `-Pcentral-release deploy` stages for manual Central approval by default;
the workflow explicitly enables automatic publication.
