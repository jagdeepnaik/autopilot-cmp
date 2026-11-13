# 19 — Git: How It Works and How to Use It

[All guides](00_INDEX.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md) · [Build on Legion](02_BUILD_WINDOWS_ON_LEGION.md)

A complete guide to Git as this project uses it: the ideas behind it, every command you're likely to need, what each one actually does, and recipes for the situations that come up. Commands are for the Mac Terminal unless a section says otherwise. They work the same in Windows PowerShell and on Linux.

## One-line commit and push

The preferred shortcut from any Mac Terminal folder is:

```sh
/Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts/git.sh push-all
```

Pass a commit message when useful:

```sh
/Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts/git.sh push-all "Describe the change"
```

The equivalent one-line Git command is:

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot && git add -A && git commit -m "Update AutoPilot" && git push origin main
```

The next command runs only if the previous command succeeds. Review `git status` first when the worktree contains unrelated changes.

> **Following links in Android Studio:** in the editor, Cmd+Click a link to jump to its section. In the Markdown **preview**, Android Studio's default runtime can't follow `#section` links and opens them in the browser instead. To fix that, open **Help → Find Action**, run **Choose Boot Java Runtime for the IDE**, pick a JetBrains Runtime "with JCEF", then restart. JetBrains notes that the preview doesn't work on Android Studio's default runtime ([known issue](https://issuetracker.google.com/issues/159933628)). On GitHub, the links work as they are.

**On this page:**
[1 Ideas](#1-the-ideas-behind-git) ·
[2 This project](#2-how-this-project-uses-git) ·
[3 Setup](#3-one-time-setup) ·
[4 Everyday](#4-everyday-commands) ·
[5 Syncing](#5-syncing-with-github-fetch-pull-push) ·
[6 Branches](#6-branches-merging-and-rebasing) ·
[7 Undoing](#7-undoing-things) ·
[8 Stash](#8-stash-put-changes-aside) ·
[9 History](#9-reading-history) ·
[10 Files](#10-moving-deleting-and-ignoring-files) ·
[11 Tags](#11-tags) ·
[12 Conflicts](#12-merge-conflicts) ·
[13 Advanced](#13-less-common-but-useful) ·
[14 Recipes](#14-recipes-for-common-situations) ·
[15 Safety](#15-what-can-lose-work) ·
[16 Cheat sheet](#16-cheat-sheet)

## Project Git script

The project includes a safe daily Git wrapper. Run it from any folder with the absolute path, or use `./git.sh` after changing to `private/scripts`:

| Command | What it does | Changes files? |
| --- | --- | --- |
| `private/scripts/git.sh status` | Shows the current branch and changed files. | No |
| `private/scripts/git.sh check` | Fetches GitHub, then shows whether `main` is ahead, behind or synchronized. | No project files; updates remote-tracking information |
| `private/scripts/git.sh fetch` | Downloads the latest GitHub history without changing the working files. | No project files |
| `private/scripts/git.sh pull` | Pulls `origin/main` with fast-forward-only safety. It refuses to run with local changes. | Yes, only by fast-forwarding `main` |
| `private/scripts/git.sh diff` | Shows both unstaged and staged changes. | No |
| `private/scripts/git.sh history` | Shows the latest 20 commits. | No |
| `private/scripts/git.sh branches` | Shows local branches and their tracking state. | No |
| `private/scripts/git.sh push-all` | Stages all non-ignored changes, commits them, and pushes `main`. | Yes; commits and pushes |
| `private/scripts/git.sh push-all "Message"` | Same as `push-all`, with a custom commit message. | Yes; commits and pushes |

The wrapper intentionally does not include destructive commands such as `reset --hard`, `clean -fd` or force-push. Use those only after reviewing the Git guide and confirming the exact files affected.

---

## 1. The ideas behind Git

### 1.1 What Git is

Git keeps the full history of a project as a series of **snapshots** called *commits*. Each commit records the exact contents of every tracked file at that moment, who made it, when, a message saying why, and which commit came before it (its *parent*). Because every commit points back to its parent, the history forms a chain you can walk back through, compare, or restore from.

Git is **distributed**: every copy of the repository (your Mac, Legion, the home server, the Windows VM) holds the complete history. GitHub is simply one more copy that everyone agrees to share through. You can commit, inspect history and undo things with no network at all; only `fetch`, `pull` and `push` talk to GitHub.

### 1.2 The three places your changes live

| Place | What it is | How things get there |
| --- | --- | --- |
| **Working tree** | The files you see and edit in the project folder | You edit them |
| **Index** (also called the *staging area*) | The snapshot that the *next* commit will record | `git add` copies a file's current content here |
| **Repository** (the `.git` folder) | Every commit ever made | `git commit` saves the index as a new commit |

A file can therefore be in three states at once: changed in the working tree but not staged, staged but not committed, or committed. `git status` shows which. The index lets you commit only some of your changes, and review exactly what a commit will contain before you make it.

### 1.3 Commits, hashes and HEAD

- Every commit has a unique ID, a 40-character **hash** such as `a8900fe3c2…`, computed from its contents. Git commands accept the first 7 or so characters (`a8900fe`) as long as they're unambiguous. Because the hash depends on the contents *and* the parent, history can't be changed silently: changing an old commit produces new hashes for it and everything after it.
- A **branch** is just a movable label pointing at one commit. `main` is this project's only branch. When you commit, the current branch's label moves forward to the new commit.
- **HEAD** means "the commit you're on now", normally via the current branch. `HEAD~1` is its parent, `HEAD~2` the grandparent, and so on.
- **Detached HEAD** means you're on a commit directly rather than on a branch (for example after `git checkout a8900fe`). You can look around, but new commits there belong to no branch. `git switch main` returns you.

### 1.4 Remotes and tracking branches

- A **remote** is another copy of the repository that yours knows about. Here there's one, called **`origin`**: `https://github.com/jagdeepnaik/autopilot-cmp.git`.
- **`origin/main`** is your machine's *record* of where `main` was on GitHub the last time you talked to it. It only updates on `fetch`, `pull` or `push`.
- Your `main` **tracks** `origin/main`, so Git can tell you how far apart they are: *ahead 2* means you have 2 commits GitHub doesn't; *behind 3* means GitHub has 3 you don't.

### 1.5 How a change travels to Legion

```text
Mac working tree --git add--> index --git commit--> Mac main --git push--> GitHub main
                                                                            |
Legion working tree <--checkout-- Legion main <--git pull (fetch + merge)--+
```

Nothing travels between the Mac and Legion directly. That's why the build machines only ever build what's been pushed.

---

## 2. How this project uses Git

| Rule | Why |
| --- | --- |
| One branch, `main`, pushed to `origin` on GitHub | Simple; every machine builds the same line of history |
| Build machines (Legion, server, VM) only `git pull --ff-only` | They never create their own commits, so a pull is always a clean fast-forward |
| Commit and push on the Mac before any remote build | Builds come from Git only. `desktop.sh` refuses uncommitted Mac work, and pushes unpushed commits ([01](01_ONE_COMMAND_BUILDS.md#1-git-is-the-only-way-code-reaches-a-build-machine)) |
| Never overwrite uncommitted work on a build machine | If one has local changes, compare them with GitHub, stash as a backup, then pull ([recipe](#147-a-build-machine-has-local-changes)) |
| The app version is `<major>.<minor>.<commit count>` | `git rev-list --count HEAD` gives the third part, so every commit raises the version ([01](01_ONE_COMMAND_BUILDS.md#2-one-version-number-per-commit)). Clones must have full history (not shallow) for this |
| `private/misc/`, `private/plans/`, `private/signing/` are git-ignored | Temporary files, working plans and secrets never reach GitHub (see AGENTS.md, "Private folder") |

Checkouts: Mac `/Users/jagdeep/Projects/AndroidStudio/AutoPilot` · Legion `C:\Users\jagde\Projects\autopilot-cmp` · Server `/srv/build/autopilot-cmp` · VM `C:\Build\autopilot-cmp`.

---

## 3. One-time setup

### 3.1 Who you are

Every commit records a name and email. Set them once per machine:

```sh
git config --global user.name "jagdeep"
git config --global user.email "jagdeepnaik@gmail.com"
```

`--global` writes to `~/.gitconfig` (all repositories for this user). Without it, the setting goes in this repository's `.git/config` only. Check what's set, and where it came from:

```sh
git config --list --show-origin
```

### 3.2 Useful settings

```sh
git config --global init.defaultBranch main      # new repositories start on main
git config --global pull.ff only                 # 'git pull' refuses anything but a fast-forward
git config --global fetch.prune true             # forget branches deleted on GitHub
git config --global core.editor "nano"           # editor for commit messages (or "code --wait")
```

### 3.3 Line endings (why Windows prints *"LF will be replaced by CRLF"*)

macOS and Linux end lines with LF; Windows traditionally uses CRLF. On Windows, Git for Windows usually sets `core.autocrlf=true`: files are stored with LF in the repository but checked out with CRLF. The warning only says that conversion will happen the next time Git writes the file; the content is unchanged. It's harmless. To see the setting: `git config core.autocrlf`.

### 3.4 Signing in to GitHub

- **HTTPS** (this project): Git asks for credentials once and the macOS Keychain or Windows Credential Manager remembers them. GitHub needs a *personal access token* or the GitHub CLI login (`gh auth login`), not your account password.
- **SSH:** add a public key to GitHub and use a `git@github.com:…` remote. Either way, never put a token in a remote URL.

### 3.5 Getting a copy

```sh
git clone https://github.com/jagdeepnaik/autopilot-cmp.git              # full history (needed for the version number)
git clone https://github.com/jagdeepnaik/autopilot-cmp.git my-folder    # into a named folder
```

`clone` downloads every commit, sets up `origin`, and checks out `main`. Avoid `--depth 1` (a *shallow* clone) here, because it would make the commit count, and therefore the version, wrong. Check with `git rev-parse --is-shallow-repository` (should print `false`).

---

## 4. Everyday commands

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
git add shared/src          # stage everything under a folder
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

## 5. Syncing with GitHub: fetch, pull, push

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

Nothing is changed when this happens. See [recipe 14.7](#147-a-build-machine-has-local-changes).

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

## 6. Branches, merging and rebasing

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
- If both sides changed the same lines, you get a [conflict](#12-merge-conflicts).

### 6.3 Rebasing

```sh
git switch try-idea
git rebase main
```

Rebase takes the branch's commits and **replays them on top of** the latest `main`, as if you'd started from there. The history becomes a straight line, but the replayed commits get new hashes. Rule: rebase only commits you haven't pushed. Rewriting pushed commits forces everyone else into the force-push problem above.

---

## 7. Undoing things

Pick by *what* you want to undo. Commands marked ⚠️ discard work; see [15](#15-what-can-lose-work).

| I want to… | Command |
| --- | --- |
| Throw away my edits to one file (back to the last commit) | ⚠️ `git restore file` |
| Unstage a file but keep the edits | `git restore --staged file` |
| Fix the last commit's message, or add a forgotten file (not pushed yet) | `git add file` then `git commit --amend` |
| Undo the last commit but keep its changes, staged | `git reset --soft HEAD~1` |
| Undo the last commit but keep its changes, unstaged | `git reset HEAD~1` (that's `--mixed`, the default) |
| Undo the last commit **and** its changes | ⚠️ `git reset --hard HEAD~1` |
| Undo a commit that's **already pushed** | `git revert <hash>` |
| Make my files exactly match GitHub | ⚠️ `git fetch` then `git reset --hard origin/main` |
| Delete untracked files | ⚠️ `git clean -n` to preview, then `git clean -fd` |
| Find a commit I "lost" | `git reflog` |

### 7.1 `git restore` — files

`git restore file` copies the version from the index (or the last commit) over your working file. `--staged` does the same for the index only, which is how you unstage. `git restore --source=a8900fe file` brings back a file as it was in any commit.

### 7.2 `git reset` — move the branch

`reset <commit>` moves the current branch label to `<commit>`. The mode decides what happens to the index and your files:

| Mode | Branch moves | Index | Working tree |
| --- | --- | --- | --- |
| `--soft` | yes | kept (changes from undone commits are staged) | kept |
| `--mixed` (default) | yes | reset to match | kept (changes are unstaged) |
| `--hard` | yes | reset | ⚠️ **overwritten**: uncommitted edits are lost |

Use `reset` only on commits you haven't pushed.

### 7.3 `git revert` — undo by adding

`git revert <hash>` creates a **new** commit that applies the opposite of `<hash>`. History is kept and nothing is rewritten, so it's the right way to undo something already on GitHub. This project did exactly that: `b55d480` reverted `a8900fe` (the SSH helper move). A revert removes a file from the current code, but the file still exists in the old commit's history.

### 7.4 `git commit --amend`

Replaces the last commit with a new one that includes whatever is staged now, with a new message if you like. It gets a new hash, so amend only before pushing.

### 7.5 `git reflog` — the safety net

```sh
git reflog -20
```

Lists everywhere `HEAD` has been on this machine (commits, resets, checkouts, rebases) for about 90 days. After a mistaken `reset --hard` or a dropped branch, find the old hash there and restore it with `git reset --hard <hash>` or `git branch rescue <hash>`. The reflog is local: it can't recover work that was never committed.

---

## 8. Stash: put changes aside

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

## 9. Reading history

```sh
git blame path/to/file                  # who last changed each line, and in which commit
git blame -L 120,140 path/to/file       # only those lines
git log --follow -- path/to/file        # a file's history through renames
git grep "desktopVersion"               # search tracked files (faster than grep, skips ignored)
git rev-list --count HEAD               # number of commits up to HEAD: the version's third part
git rev-parse HEAD                      # full hash of the current commit
git rev-parse --short HEAD              # short hash
git describe --tags                     # nearest tag plus commits since (if tags exist)
git shortlog -sn                        # commits per author
```

### 9.1 `git bisect` — find the commit that broke something

```sh
git bisect start
git bisect bad                 # the current commit has the problem
git bisect good 1a13630        # this older commit didn't
# Git checks out a commit halfway; test it, then say:
git bisect good                # or: git bisect bad
# ...repeat until Git names the first bad commit, then:
git bisect reset
```

It halves the range each time, so even 1,000 commits need only about 10 tests.

---

## 10. Moving, deleting and ignoring files

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

`.gitignore` only affects *untracked* files. A file already committed stays tracked even if a rule matches it (`private/plans/AUTOCLICKER_PLAN.md` is an example here). To stop tracking such a file: `git rm --cached <file>` and commit. A file that was ever pushed stays in history; for a leaked secret, see [recipe 14.9](#149-i-committed-a-secret).

---

## 11. Tags

```sh
git tag                                   # list
git tag -a v1.0.34 -m "Release 1.0.34"    # annotated tag on HEAD
git tag -a v1.0.34 a8900fe -m "…"         # on a specific commit
git push origin v1.0.34                   # tags aren't pushed by default
git tag -d v1.0.34                        # delete locally
git push origin :refs/tags/v1.0.34        # delete on GitHub
```

A tag is a permanent name for one commit, useful for marking a release you shipped. Here the version comes from the commit count, so a tag such as `v1.0.34` simply labels the commit that built 1.0.34.

---

## 12. Merge conflicts

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

## 13. Less common but useful

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

## 14. Recipes for common situations

### 14.1 Commit and push everything on the Mac

```sh
git status --short
git add -A
git commit -m "What changed and why"
git push origin main
```

`desktop.sh` then pulls it on the build machines ([01](01_ONE_COMMAND_BUILDS.md)).

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

## 15. What can lose work

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

## 16. Cheat sheet

| Task | Command | More |
| --- | --- | --- |
| What's changed? | `git status -sb` | [4.1](#41-git-status--where-am-i) |
| Show changes | `git diff` · `git diff --staged` | [4.2](#42-git-diff--what-exactly-changed) |
| Stage | `git add file` · `git add -A` · `git add -p` | [4.3](#43-git-add--stage-changes) |
| Commit | `git commit -m "…"` | [4.4](#44-git-commit--record-a-snapshot) |
| History | `git log --oneline -10` | [4.5](#45-git-log--see-commits) |
| One commit | `git show <hash>` | [4.6](#46-git-show--one-commit-in-detail) |
| Download, change nothing | `git fetch` | [5.1](#51-git-fetch--download-change-nothing) |
| Update (safe) | `git pull --ff-only` | [5.2](#52-git-pull--fetch-then-combine) |
| Upload | `git push origin main` | [5.3](#53-git-push--upload) |
| New branch | `git switch -c name` | [6.1](#61-creating-and-switching) |
| Merge | `git merge name` | [6.2](#62-merging) |
| Unstage | `git restore --staged file` | [7.1](#71-git-restore--files) |
| Undo last local commit | `git reset --soft HEAD~1` | [7.2](#72-git-reset--move-the-branch) |
| Undo a pushed commit | `git revert <hash>` | [7.3](#73-git-revert--undo-by-adding) |
| Find lost commits | `git reflog` | [7.5](#75-git-reflog--the-safety-net) |
| Put changes aside | `git stash push -u -m "…"` · `git stash pop` | [8](#8-stash-put-changes-aside) |
| Who changed this line | `git blame -L a,b file` | [9](#9-reading-history) |
| Find a breaking commit | `git bisect` | [9.1](#91-git-bisect--find-the-commit-that-broke-something) |
| Rename / move | `git mv old new` | [10.1](#101-moves-and-deletes) |
| Why is it ignored? | `git check-ignore -v path` | [10.2](#102-gitignore) |
| Mark a release | `git tag -a v1.0.34 -m "…"` | [11](#11-tags) |
| Resolve a conflict | edit, `git add`, `git commit` | [12](#12-merge-conflicts) |
| Version's commit count | `git rev-list --count HEAD` | [2](#2-how-this-project-uses-git) |

Official reference: `git help <command>` (for example `git help reset`) opens the full manual for any command. The free *Pro Git* book is at git-scm.com/book.
