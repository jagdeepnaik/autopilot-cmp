# Git 4 — Everyday commands

[Git guide contents](../18_GIT_GUIDE.md) · [← 3. One-time setup](03_SETUP.md) · [5. Syncing with GitHub: fetch, pull, push →](05_SYNCING.md) · [Cheat sheet](16_CHEAT_SHEET.md)


### 4.1 `git status` — where am I?

```sh
git status            # full explanation
git status --short    # one line per file
git status -sb        # short, plus the branch and ahead/behind
```

Short-format codes: two columns, *index* then *working tree*.

| Code | Meaning |
| --- | --- |
| ` M` | Modified, not staged |
| `M ` | Modified and staged |
| `MM` | Staged, then modified again |
| `A ` | New file, staged |
| `D ` / ` D` | Deleted (staged / not staged) |
| `R ` | Renamed (staged) |
| `??` | Untracked: Git has never seen this file |
| `!!` | Ignored (only with `--ignored`) |

`## main...origin/main [ahead 1]` means one commit to push.

### 4.2 `git diff` — what exactly changed?

```sh
git diff                      # working tree vs index: changes you haven't staged
git diff --staged             # index vs last commit: what 'git commit' would record
git diff HEAD                 # everything since the last commit, staged or not
git diff origin/main          # your files vs GitHub's main (after a fetch)
git diff a8900fe b55d480      # between two commits
git diff --stat               # just files and line counts
git diff -- path/to/file      # one file only
```

`+` lines were added, `-` lines removed; `@@ -10,6 +10,8 @@` says where in the file.

### 4.3 `git add` — stage changes

```sh
git add file.kt                 # stage one file
git add composeApp/src          # stage everything under a folder
git add -A                      # stage all changes: new, modified and deleted
git add -p                      # go through each change and choose y/n (interactive)
```

`add` copies the file's *current* content into the index. If you edit it again, the new edit isn't staged until you `add` again. That's the `MM` state.

### 4.4 `git commit` — record a snapshot

```sh
git commit -m "Short summary of why"      # commit what's staged
git commit -am "Message"                  # stage modified tracked files, then commit (not new files)
git commit                                # opens the editor for a longer message
```

A good message: a short first line (around 50–70 characters) saying what the commit does, a blank line, then the reason if it isn't obvious. Commit small, coherent changes; they're easier to read, revert and test.

### 4.5 `git log` — see commits

```sh
git log --oneline -10                     # last 10, one line each
git log --oneline --graph --all           # the shape of all branches
git log -p -- path/to/file                # every change to one file, with diffs
git log --stat -3                         # last 3, with files changed
git log origin/main..HEAD --oneline       # commits you have and GitHub doesn't (to be pushed)
git log HEAD..origin/main --oneline       # commits GitHub has and you don't (after fetch)
git log --author=jagdeep --since="2 days ago"
git log -S "desktopVersion"               # commits that added or removed that text
```

### 4.6 `git show` — one commit in detail

```sh
git show a8900fe                 # message and full diff
git show --stat a8900fe          # message and files only
git show a8900fe:README.md       # the whole file as it was in that commit
```

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 3. One-time setup](03_SETUP.md) · [5. Syncing with GitHub: fetch, pull, push →](05_SYNCING.md) · [Cheat sheet](16_CHEAT_SHEET.md)
