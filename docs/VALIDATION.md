# Validation

What has been tested, how, and what has not been.

## Automated: 38 tests

```bash
./gradlew test
```

| Suite | Tests | What it pins |
|---|---:|---|
| `TestRunnerDetectionTest` | 9 | Which framework a project is, and the exact argv for a run and a rerun on both Unix and Windows |
| `TestRunnerReportsTest` | 7 | The report walk against a real directory tree: multi-module discovery, stale-run exclusion, and pytest's single file |
| `TestExplorerSessionTest` | 6 | Orchestration: one run at a time, a bounded wait that does not cancel the run, and no project open |
| `TestExplorerMcpToolsTest` | 5 | The agent-facing contract, including which tools are declared as executing |
| `JUnitXmlParserTest` | 4 | Pass, fail, error and skip; the `<testsuites>` wrapper; pytest's blank classname; non-JUnit input |
| `TestReportCollectorTest` | 4 | What a run means, including a non-zero exit with no failing test |
| `TestReportTextTest` | 3 | The agent-facing text: counts first, failures with their first message line, and truncation |

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

- **It has never been loaded into a running BOSS host.** The jar builds, carries a valid manifest,
  and its classes resolve against `boss-plugin-api` 1.0.89 at compile time, but no BOSS desktop has
  loaded it. The panel's live appearance, its behaviour in a narrow sidebar, and the theme are
  unverified.
- **The MCP round-trip is unverified.** The tool definitions and their text output are unit tested,
  but no agent has called `test_run` through a real `boss` MCP server.
- **No real process has been spawned in a test.** `TestRunner.execute` builds the command, streams
  the output and waits for the exit code, and that path is exercised only by using the plugin, not
  by the suite. What is tested is everything on either side of it: the command it would run, and
  what it does with the reports afterwards.
- **Only Gradle has been run by hand at all.** Maven and pytest are covered by argv assertions and
  by the report walk, not by running either tool end to end.
- **Windows and Linux only.** Developed on Windows 11, built and tested on Linux in CI. Not run on
  macOS.

## Reproducing

```bash
# boss-plugin-api is a compile-only dependency; build it in a sibling checkout first.
git clone https://github.com/risa-labs-inc/boss-plugin-api ../boss-plugin-api
(cd ../boss-plugin-api && ./gradlew buildPluginJar)

./gradlew test buildPluginJar
```

Both were run on Temurin 17 (`JAVA_HOME` pointing at a JDK 17 toolchain). The build is warning
free; test results land in `build/test-results/test/*.xml` and the jar in
`build/libs/boss-plugin-test-explorer-0.1.0.jar`.
