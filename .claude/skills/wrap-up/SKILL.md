---
name: wrap-up
description: End a Git Dashboard plugin work session cleanly — rewrite HANDOFF.md so a fresh session can pick up, file any new rules into CLAUDE.md, check the build, offer to commit and push (only with the user's yes), then give the Blocked on me / Changed / Found summary and the one-line prompt for the next session. Use when the user types /wrap-up, says to wrap up or end the session, or the session passes its 250k token budget.
---

# Wrap up a session

The next session starts cold from `CLAUDE.md` and `HANDOFF.md`. Leave them true, current and short.
Skip a step only when nothing this session touched it.

## 1. List what happened

From this conversation and `git status` / `git diff --stat` / `git log origin/main..HEAD`, note:
user decisions (dated), features built or changed, what the user tried and approved, what's built but
untested, lessons learnt the hard way, and anything only the user can do.

## 2. File each item in its one home

| Item | Goes in |
|---|---|
| A rule or preference that always applies, a build/setup fact | `CLAUDE.md`, dated `(user, YYYY-MM-DD)` |
| A new package or major class | `CLAUDE.md` → Code map (one row or a few words) |
| Where things stand, untested work, next steps, backlog | `HANDOFF.md` |
| A gotcha that cost a round | `HANDOFF.md` → Known gotchas (or `CLAUDE.md` if it always applies) |

Never write the same fact in two files; point to its home instead. Don't copy things into memory
that these files already hold.

## 3. Rewrite HANDOFF.md

Rewrite (don't append) so it's true today: Done and pushed (with the `origin/main` hash), Built but
not committed (with how to test it), Next steps in order, Backlog, Known gotchas. Keep it **under
8 KB** (`(Get-Item HANDOFF.md).Length`); trim finished detail rather than growing it. Update the
date in its title. Refresh `NEXT_SESSION_PROMPT.md` so it says where to pick up.

## 4. Check, then ask before committing

- If code changed: compile (`"$MVN" -B -ntp -q compile`, CLAUDE.md → Build & run) and report real errors.
- **Commit and push only with the user's yes** (CLAUDE.md → Working with this user). Ask in one
  short question; on a yes, commit with a plain-English message ending with the session's attribution
  lines, and push only if they also said push.

## 5. Tell the user

End with the three headings, in plain words:
- **Blocked on me**: what only the user can do, step by step
- **Changed**: what's different now, and how to see it
- **Found**: problems noticed, stale facts, anything left untested

Then give the one-line prompt to paste into a fresh session opened in the `git-dashboard-plugin`
folder (the same text as `NEXT_SESSION_PROMPT.md`).
