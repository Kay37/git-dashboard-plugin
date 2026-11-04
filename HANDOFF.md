# Hand-off — state as of 2026-11-04

Read `CLAUDE.md` first (build commands, conventions, user preferences, code map).

## Wolfwood (separate plugin, no repo yet) — 2026-11-04

A second plugin (forest theme + tree spirits) lives in its own repo, `Kay37/wolfwood` (`main`,
`95b0f36`): design pages plus its own `HANDOFF.md`. Nothing in this repo is for Wolfwood.

## Where the code is

`origin/main` = `c3857e4` (unchanged this session). This session's work is on branch
**`claude/elegant-dijkstra-38q6jz`** (`9b67d86`), not merged into `main`. The user was sent the
built zip to install from disk. On 2026-11-03 they said they don't want to test each change
(user, 2026-11-03): fix the items, commit, push, give them the plugin.

## Done this session (on the branch, compile-checked, not yet tried in an IDE)

1. **Status bar "last commit" chip** (`🕑 2h ago`, after the branch chip). The tooltip shows date,
   subject and author; a click opens the commit in IntelliJ's Git log (`VcsLogNavigationUtil`).
2. **Background check for new commits** (`git/BackgroundFetch.kt`). Fetches the tracked remote
   30 s after open, every 10 min, and on return to the IDE after 5+ min away. Uses
   `AuthenticationMode.SILENT`, so it never shows a password prompt. Skips Power Save Mode.
   `fetchLock` is shared with Sync's fetch. The ↑/↓/✓ tooltips say "Checked for new commits …"
   (from FETCH_HEAD's date) or why the check failed. Right-click → Refresh now fetches first. A
   tick-box toggles the timer (`gitDashboard.autoFetch`).
3. **Right-click past the line end** gets IntelliJ's normal menu. The Git Dashboard menu shows
   only on the drawn pill (`BlameInlayRenderer.isOverContent`).
4. **Design fixes 1–4:**
   - Left-clicks and the hand cursor work only on the pill itself.
   - A conflict during Sync shows the "Sync paused" balloon instead of opening the merge dialog.
   - Double-click no longer lights up a commit (Alt+Shift+B does).
   - No pill on uncommitted lines or untracked files.

## Done too: design items 5–16 (`98e0b29`, compile-checked, zip sent to the user)

- Sticky error/warning balloons; no "Synced" balloon when nothing changed; "Show Details",
  "Open GitHub Settings" and "Sync Now" buttons.
- Ctrl+Enter = Commit, Ctrl+Alt+K = Commit & Push.
- "? origin/x" chip when the comparison fails. Clicking ✓ only checks for new commits. The ↓ chip
  says when it will also push. Long branch names are shortened.
- `SyncTracker` (in `SyncTask.kt`) is the project-wide "syncing" state.
- Colour and contrast fixes; badges and PR/CI chips no longer blink.
- Sync, Push and Switch Branch are registered actions (`toolwindow/GitDashboardActions.kt`).

## Next steps

1. The user installs the zip and reports anything wrong. Fix it all in one batch.
2. Merge `claude/elegant-dijkstra-38q6jz` into `main` once they're happy.
3. Then pick from the backlog.
4. Not done from the review:
   - Panel and chips still use their own buttons rather than the new registered actions.
   - "Last good" PR/CI chips aren't dimmed.

## Backlog (ideas the user liked)

- File summary above line 1; scrollbar map of not-committed / not-pushed / new lines.
- Line history, "undo my change to these lines", commit history list, stash shelf, commit message helpers.
- "2h ago" chip turning amber when there are uncommitted changes and no commit for hours.
- Background-check interval as a setting.
- Age stripes are rebuilt on the UI thread after every edit; rebuild them less often in big files.
- Later: Compose (Jewel) UI (deferred: the Compose compiler plugin is awkward under Maven).

## Known gotchas

- The 2026-10-19 blame crash fix (file reload) is still not confirmed by a pull or branch switch.
- JBR 25 crashed once in the sandbox (JVM-internal); workaround flag in `sandbox/idea64.vmoptions`.
- On 2026-10-17 a restart step closed the user's own IntelliJ: see the `.pid` rule in `CLAUDE.md`.
- `GitRepositoryFiles.getRootDir()` is the work tree, not `.git`; FETCH_HEAD sits beside `headFile`.
- In cloud sessions a stop hook asks to commit and push, but CLAUDE.md wins: ask the user first.
