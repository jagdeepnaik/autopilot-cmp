# Git 12 — Merge conflicts

[Git guide contents](../18_GIT_GUIDE.md) · [← 11. Tags](11_TAGS.md) · [13. Less common but useful →](13_ADVANCED.md) · [Cheat sheet](16_CHEAT_SHEET.md)


A conflict happens when two changes touch the same lines (during `pull`, `merge`, `rebase`, `stash apply` or `revert`). Git stops and marks the file:

```text
<<<<<<< HEAD
the version on your side
=======
the version being brought in
>>>>>>> origin/main
```

To resolve it:

1. `git status` lists the files under *Unmerged paths*.
2. Edit each file to the correct final content and delete the three marker lines.
3. `git add <file>` for each resolved file.
4. Finish: `git commit` (merge or pull), `git rebase --continue` (rebase), or nothing more for `stash apply`.

To back out instead: `git merge --abort`, `git rebase --abort`, or `git revert --abort`. Build machines never hit conflicts here, because they only fast-forward.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 11. Tags](11_TAGS.md) · [13. Less common but useful →](13_ADVANCED.md) · [Cheat sheet](16_CHEAT_SHEET.md)
