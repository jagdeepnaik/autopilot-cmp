# Git 11 — Tags

[Git guide contents](../18_GIT_GUIDE.md) · [← 10. Moving, deleting and ignoring files](10_FILES.md) · [12. Merge conflicts →](12_CONFLICTS.md) · [Cheat sheet](16_CHEAT_SHEET.md)


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

[Git guide contents](../18_GIT_GUIDE.md) · [← 10. Moving, deleting and ignoring files](10_FILES.md) · [12. Merge conflicts →](12_CONFLICTS.md) · [Cheat sheet](16_CHEAT_SHEET.md)
