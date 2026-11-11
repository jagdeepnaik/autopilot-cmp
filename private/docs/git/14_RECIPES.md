# Git 14 — Recipes for common situations

[Git guide contents](../18_GIT_GUIDE.md) · [← 13. Less common but useful](13_ADVANCED.md) · [15. What can lose work →](15_SAFETY.md) · [Cheat sheet](16_CHEAT_SHEET.md)


### 14.1 Commit and push everything on the Mac

```sh
git status --short
git add -A
git commit -m "What changed and why"
git push origin main
```

`desktop.sh` then pulls it on the build machines ([01](../01_ONE_COMMAND_BUILDS.md)).

### 14.2 See what's waiting to be pushed, or pulled

```sh
git fetch -q && git status -sb
git log origin/main..HEAD --oneline     # to push
git log HEAD..origin/main --oneline     # to pull
```

### 14.3 Undo the last commit before pushing

```sh
git reset --soft HEAD~1      # changes stay staged; fix them and commit again
```

### 14.4 Undo a pushed commit

```sh
git revert <hash>
git push origin main
```

### 14.5 Bring back a deleted or broken file

```sh
git log --oneline -- path/to/file           # find a good commit
git restore --source=<hash> path/to/file    # restore it into the working tree
```

### 14.6 Push rejected: *"Updates were rejected because the remote contains work…"*

GitHub has commits you don't. On the Mac:

```sh
git pull --rebase      # replay your commits on top of GitHub's
git push origin main
```

### 14.7 A build machine has local changes

`git pull --ff-only` stops with *"Your local changes … would be overwritten"*, or `desktop.sh` stops with *"has uncommitted changes"*. Don't reset. Compare first, then keep a backup:

```powershell
git fetch -q
git status --short                      # what's changed locally
git diff --stat origin/main             # how the files differ from GitHub
git stash push -u -m "backup before pull YYYY-MM-DD"
git pull --ff-only
```

To check whether an untracked file is identical to GitHub's, compare `git hash-object <file>` with `git rev-parse origin/main:<file>`. Delete old backup stashes with `git stash drop stash@{N}` once you're sure they aren't needed.

### 14.8 *"You are in 'detached HEAD' state"*

You checked out a commit, not a branch. Go back with `git switch main`. To keep commits made there: `git switch -c keep-this` first.

### 14.9 I committed a secret

- **Not pushed yet:** remove it and amend (`git rm --cached secret.txt`, add it to `.gitignore`, `git commit --amend`), or reset the commit.
- **Already pushed:** treat the secret as exposed. **Change the password or revoke the key first.** Removing it from history needs a history rewrite (`git filter-repo`) and a force push, which affects every clone; ask before doing it.

### 14.10 Two copies of the same work are out of step

```sh
git fetch
git diff --stat HEAD origin/main       # which files differ
git diff HEAD origin/main -- file      # how one file differs
```

### 14.11 Find which commit introduced a line

```sh
git log -S "the exact text" --oneline
git blame -L 40,60 path/to/file
```

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 13. Less common but useful](13_ADVANCED.md) · [15. What can lose work →](15_SAFETY.md) · [Cheat sheet](16_CHEAT_SHEET.md)
