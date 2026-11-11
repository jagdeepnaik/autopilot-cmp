# 16 — AutoPilot Server Build Tests and Recorded Results

[All guides](00_INDEX.md)

## 4. Optional focused tests before packaging

Run these when validating a change or preparing a release. They are not GUI tests and do not need a monitor. These are the focused automation, DGS-study and data-path tests used for the initial server check, not the complete project test suite.

**Windows SSH window, in `C:\Build\autopilot-cmp`:**

```powershell
.\gradlew.bat --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :core:automation:jvmTest :feature:home:jvmTest --tests '*DgsStudy*' :composeApp:jvmTest --tests '*DesktopDataPathsTest*'
```

**Debian SSH window, in `/srv/build/autopilot-cmp`:**

```bash
nice -n 10 ionice -c 2 -n 7 ./gradlew --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :core:automation:jvmTest :feature:home:jvmTest --tests '*DgsStudy*' :composeApp:jvmTest --tests '*DesktopDataPathsTest*'
```

Fix/report test failures before release. Keep interactive UI/browser tests on the laptop or a dedicated display environment.

## 6. Current validation checkpoint

10 November 2026:

- Windows and Linux use Gradle 9.6.0 and JDK 21; SSH access works.
- Initial server checkouts were clean at GitHub commit `9a4a180`. Uncommitted Mac UI changes were not included.
- Linux: 30 focused tests passed, zero failures/errors/skips. DEB packaging succeeded; downloaded to the Mac and SHA256 matched.
- Linux artifact: `autopilot_1.0.0_amd64.deb`.
- Linux SHA256: `d0da4da86b92791fdba2ccffd10d0781bce6872a7e95d5d8be8e2e7aab0c0df3`.
- Local downloaded copy: `/Users/jagdeep/Projects/AndroidStudio/AutoPilot/composeApp/build/home-server-distribution/linux/autopilot_1.0.0_amd64.deb`.
- First Linux cold build: 8 minutes 56 seconds, including initial dependency preparation.
- The first Windows MSI build was stopped at the owner's request. It is not a verified completed MSI build.
- Logs: Debian `/srv/build/logs/autopilot-linux-first-build.log`; Windows `C:\Build\logs\autopilot-windows-first-build.log`.
- A portable dependency cache was seeded at Windows `C:\BuildCache`; the stopped assistant build used it via a process-local environment setting. Ordinary commands above still work without it and may download uncached dependencies.
- No build monitoring or automatic reruns will continue after the stop request. The Windows VM and existing website containers are left running.

Full installation reference: [Debian and Windows setup](26_SERVER_SETUP_QUICK_LINKS.md).
