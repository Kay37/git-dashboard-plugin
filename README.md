# Git Dashboard

An IntelliJ plugin that puts Git and GitHub in one friendly side panel: branch and sync status,
commit & push, branch switching, and pull requests with CI status.

It builds on IntelliJ's bundled Git and GitHub plugins, so Git operations and GitHub sign-in are
handled by the IDE itself (Settings > Version Control > GitHub).

## Building with Maven

JetBrains only publishes a Gradle plugin for building IntelliJ plugins, so this project compiles
against the IntelliJ installed on this machine instead:

- `ide-platform/pom.xml` is a **generated** parent POM listing the IDE's jars as dependencies.
- After updating IntelliJ, regenerate it, pointing at the new install folder:

  ```powershell
  .\scripts\generate-ide-platform-pom.ps1 -IdeaHome "C:\Program Files\JetBrains\IntelliJ IDEA <version>"
  ```

  Then update `since-build` in `src/main/resources/META-INF/plugin.xml` if you want to require that version.

Commands (from IntelliJ's Maven tool window, or a terminal with Maven and `JAVA_HOME` set to a JDK 21+):

| Command | What it does |
| --- | --- |
| `mvn package` | Builds `target/git-dashboard-<version>.zip` and installs it into the test IDE. |
| `mvn package exec:exec@run-ide` | Builds, then opens a separate test IntelliJ with the plugin. |
| `mvn package exec:exec@debug-ide` | Same, with a debugger port on `localhost:5005` (attach with a "Remote JVM Debug" run configuration). |

The test IDE keeps its own settings, caches and logs in `%USERPROFILE%\.idea-sandbox\git-dashboard`,
outside OneDrive and separate from your normal IntelliJ setup. Close it before rebuilding so the
new jar is picked up on the next launch.

To install the plugin in your real IDE: Settings > Plugins > gear icon > Install Plugin from Disk,
and pick the `.zip` from `target/`.

## Layout

- `src/main/kotlin/dev/gitdashboard/toolwindow/` — the side panel (tool window) UI
- `src/main/kotlin/dev/gitdashboard/git/` — reads Git state through IntelliJ's Git plugin
- `src/main/resources/META-INF/plugin.xml` — plugin id, dependencies and extension points
- `sandbox/` — settings for the test IDE launched by `run-ide` / `debug-ide`
