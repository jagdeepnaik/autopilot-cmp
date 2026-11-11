# Git 2 — How this project uses Git

[Git guide contents](../18_GIT_GUIDE.md) · [← 1. The ideas behind Git](01_IDEAS.md) · [3. One-time setup →](03_SETUP.md) · [Cheat sheet](16_CHEAT_SHEET.md)


| Rule | Why |
| --- | --- |
| One branch, `main`, pushed to `origin` on GitHub | Simple; every machine builds the same line of history |
| Build machines (Legion, server, VM) only `git pull --ff-only` | They never create their own commits, so a pull is always a clean fast-forward |
| Commit and push on the Mac before any remote build | Builds come from Git only. `desktop.sh` refuses uncommitted Mac work, and pushes unpushed commits ([01](../01_ONE_COMMAND_BUILDS.md)) |
| Never overwrite uncommitted work on a build machine | If one has local changes, compare them with GitHub, stash as a backup, then pull ([recipe](14_RECIPES.md)) |
| The app version is `<major>.<minor>.<commit count>` | `git rev-list --count HEAD` gives the third part, so every commit raises the version ([01](../01_ONE_COMMAND_BUILDS.md)). Clones must have full history (not shallow) for this |
| `private/misc/`, `private/plans/`, `private/signing/` are git-ignored | Temporary files, working plans and secrets never reach GitHub (see AGENTS.md, "Private folder") |

Checkouts: Mac `/Users/jagdeep/Projects/AndroidStudio/AutoPilot` · Legion `C:\Users\jagde\Projects\autopilot-cmp` · Server `/srv/build/autopilot-cmp` · VM `C:\Build\autopilot-cmp`.

---

[Git guide contents](../18_GIT_GUIDE.md) · [← 1. The ideas behind Git](01_IDEAS.md) · [3. One-time setup →](03_SETUP.md) · [Cheat sheet](16_CHEAT_SHEET.md)
