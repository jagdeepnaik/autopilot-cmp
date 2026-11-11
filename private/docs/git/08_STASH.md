# Git 8 — Stash: put changes aside

[Git guide contents](../18_GIT_GUIDE.md) · [← 7. Undoing things](07_UNDOING.md) · [9. Reading history →](09_HISTORY.md) · [Cheat sheet](16_CHEAT_SHEET.md)


```sh
git stash push -u -m "why I'm stashing"    # save tracked and untracked changes, clean the folder
git stash list                              # stash@{0} is the newest
git stash show -p stash@{0}                 # what's in it
git stash apply stash@{0}                   # bring it back, keep the stash
git stash pop                               # bring back the newest and delete it
git stash drop stash@{1}                    # delete one
git stash clear                             # ⚠️ delete all stashes
```

A stash is a hidden commit holding your working-tree and index changes. `-u` includes untracked files (without it, new files stay behind). This project uses stashes as **backups** on build machines before pulling, for example `stash@{0}: backup before pull 2026-11-11` on Legion. If applying one conflicts with newer code, Git leaves conflict markers and keeps the stash.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 7. Undoing things](07_UNDOING.md) · [9. Reading history →](09_HISTORY.md) · [Cheat sheet](16_CHEAT_SHEET.md)
