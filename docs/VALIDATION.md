# Validation

What has been tested, how, and what has not been.

## Live host: BOSS 9.5.22 on Windows 11

Loaded into the released desktop app (the official `BOSS-9.5.22.msi`, host plugin API 1.0.93) on
2026-09-24, through the host's own developer path rather than any shortcut:

| Step | Command / action | Result |
|---|---|---|
| Validate | `BOSS.exe plugin validate <jar> --json` | **12/12 checks pass**, including `bytecode-implements-plugin` and `apiVersion '1.0.51' is compatible (host: 1.0.93)` |
| Load | `BOSS.exe plugin link <jar> --json` | `linked_and_reloaded`, hot-loaded into the running app |
| Tools registered | `BOSS.exe mcp list --json` | `test_run`, `test_rerun_failed`, `test_results`, `test_list` present among the host's 28 tools |
| Agent run | `BOSS.exe mcp invoke test_run` on a pytest project with a bug | `6 tests: 4 passed, 2 failing`, each failure with `at tests/test_cart.py:15` and `:28` - the two failing `assert` lines |
| Panel | Opened from the Toolbox menu | Summary `4 passed, 2 failing` in red, failing cases marked, an open icon on exactly the two located failures |
| Click to source | The open icon on `test_ten_percent_discount` | The host editor opened `tests/test_cart.py` at **Ln 15, Col 1** |
| Rerun after a fix | Fixed the bug, `BOSS.exe mcp invoke test_rerun_failed` | `pytest --last-failed` ran only the two, `2 tests: 2 passed`, exit 0 |

Two defects that only a live run could show, both fixed before this commit:

- **The panel was called "Tests"** while everything else, the proposal included, says "Test
  Explorer". Told to open "Test Explorer", a person could not find it in the Toolbox menu.
- **pytest's tests were all grouped under "pytest"**, the name of the report's single
  `<testsuite>`. They are now grouped by module (`test_cart`), the way Gradle and Surefire groups
  already read.

## Automated: 53 tests

```bash
./gradlew test
```

| Suite | Tests | What it pins |
|---|---:|---|
| `SourceLocatorTest` | 12 | Where a failure broke: the test's own JVM frame below the assertion library's, Kotlin names with spaces, nested/lambda classes, pytest's module frame, Windows separators and drive letters, and resolving to a file while skipping build output and refusing to guess between two same-named files |
| `TestRunnerDetectionTest` | 9 | Which framework a project is, and the exact argv for a run and a rerun on both Unix and Windows |
| `TestRunnerReportsTest` | 7 | The report walk against a real directory tree: multi-module discovery, stale-run exclusion, and pytest's single file |
| `TestExplorerSessionTest` | 7 | Orchestration: one run at a time, a bounded wait that does not cancel the run, no project open, and failure locations published with the report |
| `TestExplorerMcpToolsTest` | 5 | The agent-facing contract, including which tools are declared as executing |
| `JUnitXmlParserTest` | 5 | Pass, fail, error and skip; the `<testsuites>` wrapper; pytest's blank classname; pytest's single suite grouped by module; non-JUnit input |
| `TestReportCollectorTest` | 4 | What a run means, including a non-zero exit with no failing test |
| `TestReportTextTest` | 4 | The agent-facing text: counts first, failures with their first message line and the `at file:line` they broke at, and truncation |

Four choices worth explaining.

**The `readOnly` flags are asserted, not assumed.** `test_run` and `test_rerun_failed` execute the
project's own code, and that flag is what the host governs an executing tool by. A change that
quietly made either read-only would be a governance regression rather than a cosmetic one, so the
test states the intent in the one place a reviewer will look.

**The framework knowledge is pure, so it is testable without a project.** Detection takes a set of
file names and command building takes a flag, so "what would this run on Windows" is a fact about a
list rather than something you can only learn by being on Windows. Every argv assertion in
`TestRunnerDetectionTest` is exact, because a command that is nearly right fails in ways that look
like the project is broken.

**The report walk is tested on disk, not mocked.** Finding a multi-module build's reports and
excluding a previous run's are the two things most likely to be silently wrong, and neither is
visible from a unit test of the parser. `TestRunnerReportsTest` builds a real directory tree in a
temp dir, including a report backdated well outside the grace window, and asserts what comes back.

**Nothing sleeps.** Staleness is driven by explicit `lastModified` stamps against an explicit start
time, so the whole suite runs in a couple of seconds.

## A bug this found

The first working version read reports only from `build/test-results` at the project root. That is
correct for a single-module project and wrong for every multi-module Gradle build, where each
subproject writes its own `moduleA/build/test-results/...`. On a multi-module repository it would
have reported a subset of the tests as if it were all of them, which is worse than reporting
nothing.

It was found by re-reading the walk rather than by a failing test, which is the point: the walk had
no test at all. `TestRunnerReportsTest` now covers it, with a nested module two directories deep, so
the regression cannot come back quietly.

Two smaller ones found the same way and fixed before release: `mvn` and `pytest` do not resolve on
Windows without going through `cmd` (the `.cmd` launcher is not on the raw exec path), and the
pytest report path was absolute, so a project under a directory with a space in it would have had
its `--junitxml` argument resplit by `cmd`. The report path is now relative to the project.

## What has not been tested

Stated so nobody assumes otherwise.

- **Only pytest has been run end to end inside BOSS.** Gradle was run by hand before the host
  was available; Maven has never been run end to end and is covered by argv assertions and the
  report walk only. The JVM half of click-to-source is covered by `SourceLocatorTest` against real
  JUnit trace shapes, not yet by a live Gradle run in the host.
- **No real process is spawned by the automated suite.** `TestRunner.execute` builds the command,
  streams the output and waits for the exit code, and that path is exercised by the live-host runs
  above, not by `./gradlew test`.
- **One host, one OS.** The live checks were on BOSS 9.5.22 on Windows 11. Built and unit tested on
  Linux in CI; never run on macOS.
- **The panel in a narrow sidebar** has been seen only at the docked bottom width shown above.

## Reproducing

```bash
# boss-plugin-api is a compile-only dependency; build it in a sibling checkout first.
git clone https://github.com/risa-labs-inc/boss-plugin-api ../boss-plugin-api
(cd ../boss-plugin-api && ./gradlew buildPluginJar)

./gradlew test buildPluginJar
```

Both were run on Temurin 17 (`JAVA_HOME` pointing at a JDK 17 toolchain). The build is warning
free; test results land in `build/test-results/test/*.xml` and the jar in
`build/libs/boss-plugin-test-explorer-0.2.0.jar`.
