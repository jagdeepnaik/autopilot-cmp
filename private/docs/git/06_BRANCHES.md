# Git 6 — Branches, merging and rebasing

[Git guide contents](../18_GIT_GUIDE.md) · [← 5. Syncing with GitHub: fetch, pull, push](05_SYNCING.md) · [7. Undoing things →](07_UNDOING.md) · [Cheat sheet](16_CHEAT_SHEET.md)


This project works on `main` only, but branches are how you try something without disturbing it.

### 6.1 Creating and switching

```sh
git branch                    # list local branches (* marks the current one)
git branch -a                 # include GitHub's (remotes/origin/…)
git switch -c try-idea        # create a branch here and switch to it
git switch main               # go back to main
git branch -d try-idea        # delete a merged branch (-D forces, losing unmerged commits)
```

`git checkout <branch>` is the older command for `git switch <branch>`. It also restores files (`git checkout -- file`), which is why `switch` and `restore` were split out of it.

### 6.2 Merging

```sh
git switch main
git merge try-idea
```

- If `main` hasn't moved since the branch started, Git **fast-forwards**: `main` just moves to the branch's last commit.
- Otherwise it creates a **merge commit** with two parents, joining both lines of work.
- If both sides changed the same lines, you get a [conflict](12_CONFLICTS.md).

### 6.3 Rebasing

```sh
git switch try-idea
git rebase main
```

Rebase takes the branch's commits and **replays them on top of** the latest `main`, as if you'd started from there. The history becomes a straight line, but the replayed commits get new hashes. Rule: rebase only commits you haven't pushed. Rewriting pushed commits forces everyone else into the force-push problem above.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 5. Syncing with GitHub: fetch, pull, push](05_SYNCING.md) · [7. Undoing things →](07_UNDOING.md) · [Cheat sheet](16_CHEAT_SHEET.md)
