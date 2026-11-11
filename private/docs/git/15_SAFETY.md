# Git 15 — What can lose work

[Git guide contents](../18_GIT_GUIDE.md) · [← 14. Recipes for common situations](14_RECIPES.md) · [16. Cheat sheet →](16_CHEAT_SHEET.md) · [Cheat sheet](16_CHEAT_SHEET.md)


These commands can discard changes that were never committed, or remove commits. Run `git status`, and if in doubt `git stash push -u -m backup`, before using them:

| Command | What it can lose |
| --- | --- |
| `git reset --hard …` | All uncommitted edits to tracked files |
| `git restore <file>` / `git checkout -- <file>` | Uncommitted edits to that file |
| `git clean -fd` (`-fdx`) | Untracked (and ignored) files, permanently |
| `git stash drop` / `git stash clear` | The dropped stashes (recoverable for a while only by hash) |
| `git branch -D <branch>` | Commits only that branch had (recoverable through `reflog`) |
| `git push --force` | Commits that only GitHub had, for everyone |
| `git commit --amend`, `git rebase` on pushed commits | Forces others to repair their copies |

Committed work is hard to lose: until garbage collection removes unreachable commits (weeks later), `git reflog` can find it. Uncommitted work has no such safety net, so commit or stash often.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 14. Recipes for common situations](14_RECIPES.md) · [16. Cheat sheet →](16_CHEAT_SHEET.md) · [Cheat sheet](16_CHEAT_SHEET.md)
