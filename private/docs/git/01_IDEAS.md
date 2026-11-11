# Git 1 — The ideas behind Git

[Git guide contents](../18_GIT_GUIDE.md) · [2. How this project uses Git →](02_THIS_PROJECT.md) · [Cheat sheet](16_CHEAT_SHEET.md)


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

[Git guide contents](../18_GIT_GUIDE.md) · [2. How this project uses Git →](02_THIS_PROJECT.md) · [Cheat sheet](16_CHEAT_SHEET.md)
