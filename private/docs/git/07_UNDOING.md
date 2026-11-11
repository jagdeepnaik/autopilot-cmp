# Git 7 — Undoing things

[Git guide contents](../18_GIT_GUIDE.md) · [← 6. Branches, merging and rebasing](06_BRANCHES.md) · [8. Stash: put changes aside →](08_STASH.md) · [Cheat sheet](16_CHEAT_SHEET.md)


Pick by *what* you want to undo. Commands marked ⚠️ discard work; see [15](15_SAFETY.md).

| I want to… | Command |
| --- | --- |
| Throw away my edits to one file (back to the last commit) | ⚠️ `git restore file` |
| Unstage a file but keep the edits | `git restore --staged file` |
| Fix the last commit's message, or add a forgotten file (not pushed yet) | `git add file` then `git commit --amend` |
| Undo the last commit but keep its changes, staged | `git reset --soft HEAD~1` |
| Undo the last commit but keep its changes, unstaged | `git reset HEAD~1` (that's `--mixed`, the default) |
| Undo the last commit **and** its changes | ⚠️ `git reset --hard HEAD~1` |
| Undo a commit that's **already pushed** | `git revert <hash>` |
| Make my files exactly match GitHub | ⚠️ `git fetch` then `git reset --hard origin/main` |
| Delete untracked files | ⚠️ `git clean -n` to preview, then `git clean -fd` |
| Find a commit I "lost" | `git reflog` |

### 7.1 `git restore` — files

`git restore file` copies the version from the index (or the last commit) over your working file. `--staged` does the same for the index only, which is how you unstage. `git restore --source=a8900fe file` brings back a file as it was in any commit.

### 7.2 `git reset` — move the branch

`reset <commit>` moves the current branch label to `<commit>`. The mode decides what happens to the index and your files:

| Mode | Branch moves | Index | Working tree |
| --- | --- | --- | --- |
| `--soft` | yes | kept (changes from undone commits are staged) | kept |
| `--mixed` (default) | yes | reset to match | kept (changes are unstaged) |
| `--hard` | yes | reset | ⚠️ **overwritten**: uncommitted edits are lost |

Use `reset` only on commits you haven't pushed.

### 7.3 `git revert` — undo by adding

`git revert <hash>` creates a **new** commit that applies the opposite of `<hash>`. History is kept and nothing is rewritten, so it's the right way to undo something already on GitHub. This project did exactly that: `b55d480` reverted `a8900fe` (the SSH helper move). A revert removes a file from the current code, but the file still exists in the old commit's history.

### 7.4 `git commit --amend`

Replaces the last commit with a new one that includes whatever is staged now, with a new message if you like. It gets a new hash, so amend only before pushing.

### 7.5 `git reflog` — the safety net

```sh
git reflog -20
```

Lists everywhere `HEAD` has been on this machine (commits, resets, checkouts, rebases) for about 90 days. After a mistaken `reset --hard` or a dropped branch, find the old hash there and restore it with `git reset --hard <hash>` or `git branch rescue <hash>`. The reflog is local: it can't recover work that was never committed.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 6. Branches, merging and rebasing](06_BRANCHES.md) · [8. Stash: put changes aside →](08_STASH.md) · [Cheat sheet](16_CHEAT_SHEET.md)
