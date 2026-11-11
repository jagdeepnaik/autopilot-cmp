# Git 3 — One-time setup

[Git guide contents](../18_GIT_GUIDE.md) · [← 2. How this project uses Git](02_THIS_PROJECT.md) · [4. Everyday commands →](04_EVERYDAY.md) · [Cheat sheet](16_CHEAT_SHEET.md)


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

[Git guide contents](../18_GIT_GUIDE.md) · [← 2. How this project uses Git](02_THIS_PROJECT.md) · [4. Everyday commands →](04_EVERYDAY.md) · [Cheat sheet](16_CHEAT_SHEET.md)
