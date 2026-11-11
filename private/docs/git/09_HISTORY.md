# Git 9 — Reading history

[Git guide contents](../18_GIT_GUIDE.md) · [← 8. Stash: put changes aside](08_STASH.md) · [10. Moving, deleting and ignoring files →](10_FILES.md) · [Cheat sheet](16_CHEAT_SHEET.md)


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

[Git guide contents](../18_GIT_GUIDE.md) · [← 8. Stash: put changes aside](08_STASH.md) · [10. Moving, deleting and ignoring files →](10_FILES.md) · [Cheat sheet](16_CHEAT_SHEET.md)
