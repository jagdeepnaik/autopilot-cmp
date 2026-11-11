# Git 13 — Less common but useful

[Git guide contents](../18_GIT_GUIDE.md) · [← 12. Merge conflicts](12_CONFLICTS.md) · [14. Recipes for common situations →](14_RECIPES.md) · [Cheat sheet](16_CHEAT_SHEET.md)


| Command | What it does | Used here |
| --- | --- | --- |
| `git worktree add ../other HEAD` | A second working folder on the same repository, at another commit | Briefly, for tests on Legion. Not any more: builds come from the main checkout |
| `git worktree list` / `remove <path>` / `prune` | Manage worktrees. On Windows, `remove` can fail on long paths: delete the folder with `rd /s /q \\?\C:\path`, then `git worktree prune` | Cleaning up Legion |
| `git bundle create file.bundle origin/main..HEAD` | Pack commits into one file, to carry them without GitHub | Once, now replaced by push-and-pull |
| `git cherry-pick <hash>` | Copy one commit onto the current branch | |
| `git clean -n` / `-fd` | Preview / delete untracked files (`-x` also ignored ones) ⚠️ | |
| `git archive -o src.zip HEAD` | A zip of the tracked files at a commit, no `.git` | |
| `git config --global alias.st "status -sb"` | Shortcut: `git st` | |
| `git fsck` | Check the repository for corruption | |
| `git gc` | Compact the repository; Git runs it automatically | |
| `git hash-object file` | The hash Git would give a file's content | Comparing Legion's files with GitHub's |
| `git rev-parse origin/main:path` | The hash of a file as committed on GitHub | Same check: equal hashes mean identical content |

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 12. Merge conflicts](12_CONFLICTS.md) · [14. Recipes for common situations →](14_RECIPES.md) · [Cheat sheet](16_CHEAT_SHEET.md)
