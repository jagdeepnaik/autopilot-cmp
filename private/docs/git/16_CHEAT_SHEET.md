# Git 16 — Cheat sheet

[Git guide contents](../18_GIT_GUIDE.md) · [← 15. What can lose work](15_SAFETY.md)


| Task | Command | More |
| --- | --- | --- |
| What's changed? | `git status -sb` | [4.1](04_EVERYDAY.md) |
| Show changes | `git diff` · `git diff --staged` | [4.2](04_EVERYDAY.md) |
| Stage | `git add file` · `git add -A` · `git add -p` | [4.3](04_EVERYDAY.md) |
| Commit | `git commit -m "…"` | [4.4](04_EVERYDAY.md) |
| History | `git log --oneline -10` | [4.5](04_EVERYDAY.md) |
| One commit | `git show <hash>` | [4.6](04_EVERYDAY.md) |
| Download, change nothing | `git fetch` | [5.1](05_SYNCING.md) |
| Update (safe) | `git pull --ff-only` | [5.2](05_SYNCING.md) |
| Upload | `git push origin main` | [5.3](05_SYNCING.md) |
| New branch | `git switch -c name` | [6.1](06_BRANCHES.md) |
| Merge | `git merge name` | [6.2](06_BRANCHES.md) |
| Unstage | `git restore --staged file` | [7.1](07_UNDOING.md) |
| Undo last local commit | `git reset --soft HEAD~1` | [7.2](07_UNDOING.md) |
| Undo a pushed commit | `git revert <hash>` | [7.3](07_UNDOING.md) |
| Find lost commits | `git reflog` | [7.5](07_UNDOING.md) |
| Put changes aside | `git stash push -u -m "…"` · `git stash pop` | [8](08_STASH.md) |
| Who changed this line | `git blame -L a,b file` | [9](09_HISTORY.md) |
| Find a breaking commit | `git bisect` | [9.1](09_HISTORY.md) |
| Rename / move | `git mv old new` | [10.1](10_FILES.md) |
| Why is it ignored? | `git check-ignore -v path` | [10.2](10_FILES.md) |
| Mark a release | `git tag -a v1.0.34 -m "…"` | [11](11_TAGS.md) |
| Resolve a conflict | edit, `git add`, `git commit` | [12](12_CONFLICTS.md) |
| Version's commit count | `git rev-list --count HEAD` | [2](02_THIS_PROJECT.md) |

Official reference: `git help <command>` (for example `git help reset`) opens the full manual for any command. The free *Pro Git* book is at git-scm.com/book.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 15. What can lose work](15_SAFETY.md)
