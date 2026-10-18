# Hand-off — state as of 2026-10-18

Read `CLAUDE.md` first (build commands, conventions, user preferences, code map).

## Done and pushed (`origin/main` = `dca7665`)

1. Panel scaffold + status card (branch, ↑/↓ vs upstream).
2. Commit & Push flow: changed-files tree with checkboxes (untracked included), message box,
   Commit / Commit & Push (Ctrl+Enter), Undo Last Commit.
3. Standalone Push with "push up to this commit" picker.
4. One-click Sync: fetch → fast-forward / merge (or rebase if `pull.rebase`) with autostash → push;
   conflicts open IntelliJ's merge dialog, then finish/continue; Resolve/Abort on the card.
5. Create Git repository, Publish to GitHub (private by default), Remotes dialog.
6. Inline blame pill on the caret line: author initials circle, "You", relative date, subject,
   `#N shorthash`, ↑ Not pushed / ✦ New badges; click → commit card; right-aligned at the margin
   guide; same-commit tint on double-click or Alt+Shift+B; Copy GitHub Link to Line; colours under
   Settings → Editor → Color Scheme → Git Dashboard; toggles in the editor right-click menu.
7. Status bar chips: branch ▾ (opens switcher), solid ↑ push / ↓ pull, ✓ Synced, spinner, publish,
   conflict; right-click menu.
8. Branch switcher popup: search, switch (smart checkout), New Branch, Delete key, Delete Merged.
9. GitHub pull requests & CI: `github/GitHubApi.kt` (REST client), `github/GitHubStatusService.kt`
   (shared PR/CI state, polling), `toolwindow/PullRequestsSection.kt`, `toolwindow/CreatePullRequest.kt`,
   PR / CI chips in `statusbar/SyncStatusBarWidget.kt`.
10. Workflow kit (from the DHARMA project): `scripts/restart-test-ide.ps1`, `scripts/context.mjs` +
    `.claude/settings.json` (status line and 250k budget warning), `/wrap-up` skill, Working rules in `CLAUDE.md`.

## Committed, not yet pushed (2026-10-18) — user tried the stripes and said they work

**Commit age stripes** (`blame/CommitAgeStripes.kt`, wired in `InlineBlameManager.showAgeStripes`):
a rounded stripe in the gutter's left edge per run of lines from one commit, colour mixed from
"faded" to "bright" by age (log scale, fully faded at 2 years); hover = who/when/subject, click =
commit card. Toggle "Show Commit Age Stripes in the Gutter" (`gitDashboard.ageStripes`, default on);
colours are `ColorKey`s on the Git Dashboard colour page.

**PR & CI edge cases** (reviewed in code; no fork or expired token was available to click through):
- No CI: `checks()` already returns null → no chip. Fixed: a failed checks call no longer hides the PRs.
- Forks: PRs are now read from / opened on the parent repo (`RepoInfo.parent`), head `owner:branch`,
  current PR matched by head repo; "Check out" only for PRs whose repo is one of the project's remotes.
- Signed out: no account → "Sign in to GitHub…" (unchanged). Rejected token (401) → "Sign in to GitHub
  again…" (`requestReLogin`). Account changes in Settings refresh the panel at once (`accountsState`).

## Ideas the user liked (backlog)

- File summary above line 1
  ("3 authors · last changed 2 days ago"); scrollbar map of not-committed / not-pushed / new lines.
- Line history ("how this line changed"), "undo my change to these lines".
- Commit history list with revert / copy hash / open on GitHub; stash shelf;
  automatic background fetch; commit message helpers.
- Possible later: Compose (Jewel) UI — deferred because the Compose compiler plugin is awkward under Maven.

## Known gotchas

- JBR 25 crashed once in the sandbox (JVM-internal, not plugin code); workaround flag is in
  `sandbox/idea64.vmoptions`. Report to JetBrains if it recurs with the flag.
- On 2026-10-17 a restart step closed the user's own IntelliJ by mistake — see the `.pid` rule in `CLAUDE.md`.
