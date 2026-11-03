# Hand-off — state as of 2026-11-03

Read `CLAUDE.md` first (build commands, conventions, user preferences, code map).

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

## Next steps: remaining design items (user asked for all of them, 2026-11-03)

5. **Balloons:**
   - No "Synced" balloon when nothing changed (`SyncTask.kt` onSuccess).
   - A second notification group, `STICKY_BALLOON`, for ERROR/WARNING in `notifyUser`.
6. **Commit shortcuts** (`CommitSection.kt:106`):
   - Ctrl+Enter = Commit; Ctrl+Alt+K = Commit & Push.
   - Update the empty-text hint at :64.
7. **False green ✓:** when `ahead`/`behind` is null but upstream exists, show a grey
   "? origin/x — couldn't compare" chip instead of ✓ Synced (`SyncStatusBarWidget.stateChips`).
8. **One project-wide "sync running" flag** (a service) that the panel and chip both check and
   show. Today `syncing` is local to the widget.
9. **Buttons on error balloons:** "Push rejected… Click Sync" → Sync Now; "Check your account
   under Settings" → Open Settings. Hide raw git output behind "Show details".
10. **What the chips do when clicked:**
    - ✓ click should only check for new commits (`BackgroundFetchService.checkNow(userAsked=true)`), not a full sync.
    - ↓ tooltip/text should say it will also push when ahead > 0 ("Pull 3 · push 2").
11. **One meaning per colour:**
    - Age stripes move off orange to the pill's purple (`CommitAgeStripes.kt`).
    - Conflicts are red in the panel too (`GitDashboardPanel`).
    - Publish and PR chips get different colours.
12. **Light-theme contrast:** pill text about #5F52A8, orange about #B35C00 (colour scheme
    XMLs, `ChipBar.textOn`, `TO_PUSH`). Same-commit tint alpha 20 → about 50.
13. **Badge blink:** `CommitBadges` drops the unpushed list on a repo change (line ~80). Keep it
    until the new list arrives.
14. **Status bar width:**
    - Shorten branch names over 25 characters (full name in the tooltip).
    - Keep the last good GitHub PR/CI state (dimmed) when a refresh fails, instead of hiding the chips.
15. **Push consistency:**
    - Panel "Push" vs "Push…" behave differently.
    - "Switch Branch…" and "Open Git Dashboard" share an icon.
    - "Copy GitHub Link to Line" is listed twice in the editor menu (`plugin.xml`).
16. **Find Action / keymap:** declare Sync, Push and Switch Branch as real actions in
    `plugin.xml` and reuse them in the panel and chips.

Then build the zip, send it to the user, and merge the branch into `main` once they're happy.

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
