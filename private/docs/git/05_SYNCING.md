# Git 5 — Syncing with GitHub: fetch, pull, push

[Git guide contents](../18_GIT_GUIDE.md) · [← 4. Everyday commands](04_EVERYDAY.md) · [6. Branches, merging and rebasing →](06_BRANCHES.md) · [Cheat sheet](16_CHEAT_SHEET.md)


### 5.1 `git fetch` — download, change nothing

```sh
git fetch origin
```

Downloads new commits from GitHub and moves `origin/main`. Your files and your `main` don't change, so it's always safe. Afterwards, `git status -sb` shows whether you're behind.

### 5.2 `git pull` — fetch, then combine

```sh
git pull --ff-only
```

`pull` is `fetch` followed by integrating `origin/main` into your `main`. With `--ff-only` (what every machine here uses), it only does a **fast-forward**: if your `main` has no commits GitHub lacks, Git simply moves `main` forward to `origin/main` and updates your files. If both sides have new commits, it refuses instead of creating a merge. That's the signal that something unexpected happened.

Pull also refuses if a commit would change files you've edited locally:

```text
error: Your local changes to the following files would be overwritten by merge
```

Nothing is changed when this happens. See [recipe 14.7](14_RECIPES.md).

Other pull styles: `git pull --rebase` replays your local commits on top of GitHub's (straight history); plain `git pull` creates a merge commit when histories have diverged.

### 5.3 `git push` — upload

```sh
git push origin main         # send your new main commits to GitHub
git push                     # same, once main tracks origin/main
git push -u origin feature   # first push of a new branch, and set it to track
```

Push only succeeds if it's a fast-forward for GitHub. If GitHub has commits you don't, it's rejected (*"fetch first"* or *"non-fast-forward"*): pull, then push again. **Never** use `git push --force` on `main`: it replaces GitHub's history with yours and discards commits that only GitHub had. If force is ever truly needed on your own branch, `--force-with-lease` is the safer form: it refuses if GitHub has changed since you last fetched.

### 5.4 `git remote` — which GitHub?

```sh
git remote -v                             # list remotes and their URLs
git remote show origin                    # details: branches, tracking, ahead/behind
git remote set-url origin <new-url>       # change the URL (for example after a rename)
```

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 4. Everyday commands](04_EVERYDAY.md) · [6. Branches, merging and rebasing →](06_BRANCHES.md) · [Cheat sheet](16_CHEAT_SHEET.md)
