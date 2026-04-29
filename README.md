# agent-tools

File and shell tools for [Embabel](https://github.com/embabel/embabel-agent) agents.
Exposes `@Tool`-annotated methods for read/write/edit/list/glob/grep and a
sandboxed bash invocation, with results bound to the agent blackboard as typed
domain objects.

- **Group:** `org.antoined`
- **Artifact:** `agent-tools`
- **Kotlin:** 2.3.0 (language/api level 2.3)
- **JVM target:** 21
- **License:** your call

## Build

```bash
./mvnw clean install
# or, if you don't vendor the wrapper:
mvn clean install
```

The Embabel and Spring Boot dependencies are declared `provided`, so the
consuming project supplies versions compatible with its runtime.

## Use

In your Embabel app's `pom.xml`:

```xml
<dependency>
  <groupId>org.antoined</groupId>
  <artifactId>agent-tools</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Import the configuration (component scan won't pick it up automatically across
package roots):

```kotlin
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
