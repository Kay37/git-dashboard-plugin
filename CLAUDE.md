# Git Dashboard — IntelliJ plugin

A free, friendly Git & GitHub plugin for IntelliJ: a side panel (commit, push, sync, branches,
pull requests), an inline blame "pill" on the caret line, and coloured status-bar chips. The goal
is that using this plugin is easier and clearer than any other way of doing Git, with visuals
that stand out from the code rather than blending in.

GitHub: `Kay37/git-dashboard-plugin` (private). See `HANDOFF.md` for current state and next steps.

## Working with this user

- **Maven, not Gradle.** The user chose this knowingly; don't suggest switching.
- **Explain in plain, ELI5 terms, in the chat itself.** Don't bury explanations between tool calls.
- **Commit and push only when the user approves.** The usual loop: build → relaunch the test IDE →
  the user tries it → they say "commit" → commit → they say "push" → push.
- User-facing text (notifications, dialogs, tooltips) is plain English. Explain what happened and
  what to do; never dump raw git output when a friendly message is possible.

## Working rules (token and effort savers)

Adopted from the user's DHARMA Initiative project (2026-10-18), trimmed to what fits a solo plugin.

- **Suggest a model and effort** at the start of each new request, in one line, with why. The user
  switches them in the app's model picker. When in doubt go one step lower; step up if work comes back wrong.

  | Kind of work | Model | Effort |
  |---|---|---|
  | New feature design, tricky IntelliJ API / threading bugs | Opus 5.5 | high |
  | Feature from a clear spec, UI polish passes | Opus 5.5 | medium |
  | Routine code, docs, HANDOFF/CLAUDE.md updates, wrap-up | Sonnet 5.5 | medium |
  | Look-ups, renames, one-line fixes, relaunching the test IDE | Haiku 5.5 or Sonnet 5.5 | low |

- **Literal changes.** Repeat each request back in one line, then change only what the user named.
  Ideas go in a "you might also want" line; never build them unasked. If the words could mean two
  things, ask one short question.
- **Lean reading.** Search first (Grep), then read only the lines needed. Never read `idea.log`,
  `ide-platform/pom.xml` or anything in `target/` whole — grep them. For IntelliJ APIs use `javap`
  on the one class (below), not broad jar listings.
- **Bounded check passes.** Build fully, relaunch once (`scripts/restart-test-ide.ps1`), let the user
  try it, then fix everything they found in one batch. One message with five notes is one round.
- **Ask for the best tool early.** If a tool would make a task faster or better, say so at the start
  (what it is, size, free, why). Free and open-source only; installing anything needs a yes.
- **Test before writing "broken".** Before a note says something doesn't work, rerun it once and try
  the documented fix; the note says what was tried.
- **Windows habits.** Scripts longer than a line or with nested quotes go in a file in the scratchpad,
  never inline. Builds and the IDE restart run with `run_in_background`; foreground `sleep` is blocked.
- **Helper agents** only for research or review with a clear end point; never for UI the user must see.
- **One fact, one home.** `CLAUDE.md` = rules and setup that always hold; `HANDOFF.md` = where things
  stand and what's next (rewritten, not appended, under 8 KB). Elsewhere, point to the file.
- **Session budget: about 250k tokens.** The status line shows the size (`scripts/context.mjs`) and a
  hook warns past 250k. Past it: finish the item in hand, run `/wrap-up`, and give the user a one-line
  prompt for a fresh session. A new ask past 250k gets that prompt back instead of new work, unless
  the user says "keep going". One session per feature or day. (The hooks only run when the session is
  opened in this folder, not in `claude_general_PC`.)
- **End every run with three headings:** **Blocked on me** (what only the user can do, step by step),
  **Changed**, **Found**. End a session with `/wrap-up`.

## Build & run

There is no `mvn` on PATH and no `JAVA_HOME`; use IntelliJ's bundled Maven with JDK 21:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
MVN="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.3/plugins/maven-plugin/lib/maven3/bin/mvn"
"$MVN" -B -ntp -q compile                    # quick compile check
"$MVN" -B -ntp -q package exec:exec@run-ide  # build, install into sandbox, launch test IDE
```

Show only real compile errors:
`... compile 2>&1 | grep -v 'starting the daemon' | grep -E '\[ERROR\] file|e: |w: '`

- The test IDE ("sandbox") lives in `~/.idea-sandbox/git-dashboard` (config/system/plugins/log).
  Check `log/idea.log` for `Loaded custom plugins: Git Dashboard` and for `ERROR` / `dev.gitdashboard`.
- **Restarting the test IDE: never close "all idea64 except X".** The user's own IntelliJ runs
  alongside it (this went wrong once). Only close the PID in
  `~/.idea-sandbox/git-dashboard/system/.pid`, and only if that process is `idea64` and started
  within a couple of minutes of the file's write time. If unsure, ask the user to close it.
  **Use `scripts/restart-test-ide.ps1`** (run in the background): it builds first, applies exactly
  that check (exit 2 = unsafe, ask the user), relaunches, and reports the load line and any plugin
  errors from this launch. `-NoBuild` skips the build.
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/restart-test-ide.ps1`
- `ide-platform/pom.xml` is **generated**: it lists the local IDE's jars as system-scoped deps.
  After an IntelliJ update run `scripts/generate-ide-platform-pom.ps1 -IdeaHome "<new install>"`.
- Target: IntelliJ 2026.2.3 (build 262), JBR 25, Kotlin 2.4 (`kotlin.version` 2.4.21, jvmTarget 21).
- `sandbox/idea64.vmoptions` adds `-XX:-UseCompactObjectHeaders` after a JBR 25 internal crash
  ("Node hash code has changed"); `debug-ide` adds a debugger on localhost:5005.

## Finding IntelliJ APIs

There are no sources; inspect the installed jars with `javap` (Windows paths via `cygpath -w`):

```bash
IDE="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.3"
CP="$(for j in "$IDE/plugins/vcs-git/lib/vcs-git.jar" "$IDE"/lib/intellij.platform.vcs*.jar; do printf '%s;' "$(cygpath -w "$j")"; done)"
"/c/Program Files/Java/jdk-21/bin/javap.exe" -cp "$CP" git4idea.branch.GitBrancher
```

To find which jar holds a class: loop over `"$IDE"/lib/*.jar` with `unzip -l | grep 'path/Name.class'`.

## Code map (`src/main/kotlin/dev/gitdashboard/`)

| Package | What's there |
|---|---|
| `git/` | Blocking git operations (call off the EDT): `GitOperations` (commit/push/undo, `runGit`/`tryGit`), `SyncOperations` (fetch+merge/rebase+push, conflicts), `RepoStatus`, `LocalChanges`, `Branches`, `RepositorySetup` (init, remotes) |
| `github/` | `GitHubApi` (REST via IntelliJ `HttpRequests` + Gson), `GitHubStatusService` (shared PR/CI state, polling), `GitHubRepositories` (create repo), `GitHubLinks` (URLs, coordinates) |
| `toolwindow/` | The Git Dashboard panel (`GitDashboardPanel`) and everything it opens: commit section, push dialog, sync/conflict tasks, branch switcher, remotes, publish, pull requests section, create-PR dialog |
| `blame/` | Inline blame: `InlineBlameManager` (caret tracking, inlay, on-demand same-commit tint), `BlameInlayRenderer` (pill + badges, right-aligned at margin), `BlameCache`, `CommitBadges` (↑ Not pushed / ✦ New), `BlamePopup`, settings/toggles, Copy GitHub link, colour settings page |
| `statusbar/` | `SyncStatusBarWidget` + `ChipBar` (coloured clickable chips: branch, ↑/↓, ✓, PR, CI) |

Conventions: background work uses `Task.Backgroundable` (or the project `GitDashboardScope`
coroutine scope); user messages go through `notifyUser(...)`; git failures become
`GitOperationException` with a plain-English message; Git4Idea's `GitBrancher` is used for
checkout/branch operations (smart checkout). Settings are `PropertiesComponent` keys prefixed
`gitDashboard.`. Shortcut: Alt+Shift+B lights up a commit's lines (Alt+Shift+G is taken by IntelliJ).

## Environment notes

- Windows 11, project inside OneDrive. Git warns about LF→CRLF on every commit; that's normal.
- `gh` CLI is signed in as Kay37; read-only `gh api` calls are handy for checking GitHub response shapes.
