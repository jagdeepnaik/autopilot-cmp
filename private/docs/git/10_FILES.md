# Git 10 — Moving, deleting and ignoring files

[Git guide contents](../18_GIT_GUIDE.md) · [← 9. Reading history](09_HISTORY.md) · [11. Tags →](11_TAGS.md) · [Cheat sheet](16_CHEAT_SHEET.md)


### 10.1 Moves and deletes

```sh
git mv old/path new/path      # rename or move, staged in one step
git rm file                   # delete and stage the deletion
git rm --cached file          # stop tracking, but keep the file on disk
```

Git doesn't store renames; it notices them by matching content (that's the `R` in status). `git mv` is the same as moving the file and running `git add -A` on both paths. This project used `git mv` to move `tools/` to `private/scripts/` and to renumber the guides, so each file's history follows it (`git log --follow`).

### 10.2 `.gitignore`

Patterns in `.gitignore` tell Git to leave matching untracked files alone:

| Pattern | Matches |
| --- | --- |
| `/private/misc/` | That folder at the project root only (leading `/`) |
| `captures` | Any file or folder named `captures`, anywhere |
| `*.jks` | Any file ending `.jks` |
| `!keep.txt` | Exception: don't ignore this one |

```sh
git check-ignore -v private/misc/notes.md     # which rule ignores it, and where
git status --ignored                          # list ignored files too
```

`.gitignore` only affects *untracked* files. A file already committed stays tracked even if a rule matches it (`private/plans/AUTOCLICKER_PLAN.md` is an example here). To stop tracking such a file: `git rm --cached <file>` and commit. A file that was ever pushed stays in history; for a leaked secret, see [recipe 14.9](14_RECIPES.md).

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 9. Reading history](09_HISTORY.md) · [11. Tags →](11_TAGS.md) · [Cheat sheet](16_CHEAT_SHEET.md)
