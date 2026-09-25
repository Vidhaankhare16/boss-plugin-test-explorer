# BOSS Test Explorer

Run the open project's tests from a BOSS sidebar panel, see a pass/fail tree, and let an agent
in a terminal run them too and read structured results, through `test_*` MCP tools.

## Who it helps

A developer or student working in BOSS runs tests over and over. Today the run happens in a
terminal and its result is a wall of scrolling text: to know what failed you re-read the tail, and
an agent driving the workspace has nothing better than the same scraping. The `run-configurations`
plugin can *launch* an entry point, but it shows no results.

Test Explorer closes that gap on both sides at once:

- **For a person:** one Run button, a summary line (how many passed, failed, skipped), and a tree
  of suites and cases coloured by outcome. A failing case expands to its message and stack, and
  **one click opens its file at the line that broke**, in BOSS's own editor. A **Rerun failed**
  button runs only what broke.
- **For an agent:** the same run is reachable as MCP tools that return **structured** results, so
  "did my change pass the tests, and if not which ones broke, why, and where" is one tool call,
  not a parse of terminal output. Each failure comes with `at <file>:<line>`, so the agent can go
  straight to the fix and then `test_rerun_failed` to prove it.

**Red tests stay visible.** After a run, the host status bar shows the outcome, a red
"2 failing" or a green "8 passed", so a failure is not forgotten once the panel is closed.
Clicking it opens the panel. Nothing is shown before the first run.

**Run only what changed.** The panel's **Changed** button and the `test_affected` tool read the
uncommitted changes from git (staged, unstaged and untracked) and run just the tests they touch: a
changed test file, and every test that imports a changed source file. A changed build or test
configuration file (`build.gradle.kts`, `pom.xml`, `conftest.py`, `pyproject.toml`) runs everything,
because it can affect any test, and the run says so. A changed file that no test refers to is named,
because "no test failed" says nothing about code no test covers.

The line is read from the runner's own report, not guessed from a test's name: the test's own
stack frame for Gradle and Maven (skipping the assertion library's frames above it), and the frame
in the test's module for pytest.

## Supported runners

Anything that emits the **JUnit XML** report format, auto-detected from the project root:

| Runner | Detected by | Reports read from |
|---|---|---|
| **Gradle** | `build.gradle(.kts)`, `settings.gradle(.kts)`, `gradlew` | `build/test-results/**/*.xml` |
| **Maven** (Surefire) | `pom.xml` | `target/surefire-reports/*.xml` |
| **pytest** | `pytest.ini`, `pyproject.toml`, `conftest.py`, `setup.cfg`, `tox.ini` | its `--junitxml` report |

One parser backs all three, because that report format is their common surface. When both a JVM
build tool and pytest markers are present, the build tool wins.

## MCP tools

| Tool | Read-only | What it does |
|---|---|---|
| `test_run` | no | Run the whole suite; report counts, exit code, and the failing tests with messages and `file:line`. |
| `test_rerun_failed` | no | Rerun only the tests that failed last time (a full run when there were none). |
| `test_affected` | no | Run only the tests the uncommitted changes touch, and say which changed files no test refers to. |
| `test_results` | yes | The most recent run's outcome, without starting a run. |
| `test_list` | yes | Every test from the last run with its pass/fail/skip status. |

A long suite can outlast the host's per-call MCP timeout, so `test_run` / `test_rerun_failed` wait
a bounded time and, if the run is still going, return a "still running" note; the agent then polls
`test_results`. The panel and the tools share one run, so a run either side starts is the same run.

## Build

```bash
# Build the boss-plugin-api jar in a sibling checkout first (compile-only dependency):
#   git clone https://github.com/risa-labs-inc/boss-plugin-api ../boss-plugin-api
#   (cd ../boss-plugin-api && ./gradlew buildPluginJar)
./gradlew test buildPluginJar
```

The loadable jar is written to `build/libs/boss-plugin-test-explorer-<version>.jar`.

## Install (local development)

Copy the jar into a **dev-mode** BOSS data root so it never touches a production install:

```bash
cp build/libs/boss-plugin-test-explorer-*.jar ~/.boss_debug/plugins/
rm -rf ~/.boss_debug/plugin-cache/ai.rever.boss.plugin.dynamic.testexplorer
```

Restart the dev host and open the **Tests** panel in the left sidebar. To use the MCP tools,
enable them under Toolbox -> MCP and attach an agent.

## How it works

- The framework knowledge (detect, invoke, where reports land, how to rerun) is pure data in
  `core/TestRunnerDetection.kt`; the JUnit-XML parsing is in `core/JUnitXmlParser.kt`; interpreting
  a run into a report is in `core/TestReportCollector.kt`. All three are unit tested.
- `engine/TestRunner.kt` is the one impure piece: it spawns the runner as a child process (never
  through a shell, so no argument is word-split or glob-expanded), streams its output, and reads
  only the reports this run wrote.
- Reports are parsed with DTDs and external entities disabled, so a crafted report in a repository
  cannot make the parser open a file or URL of its choosing.

## Compatibility, access, and data

**Versions.** Built against `boss-plugin-api` 1.0.89. The manifest declares `apiVersion 1.0.51` and
`minBossVersion 9.2.20`, deliberately lower than the jar it compiles against: the only API symbols
it uses are panel registration and the MCP tool provider, both present well below the current
level, so the pin is set to the oldest host that can actually serve them rather than to the newest
one available. That is the range it is written for, not a range it has been run against.

**Operating systems.** Developed on Windows 11 and built and tested on Linux in CI. Nothing
platform specific is used beyond JDK APIs and the runner you already use, but the panel has not
been exercised on macOS.

**Permissions.** None are requested. The plugin reads `PluginContext.projectPath` and registers a
panel and an MCP tool provider; a blank project path is treated as "no project open" and reported
as such rather than crashing.

**What leaves the machine: nothing.** No telemetry, no external service, no network call of any
kind. The plugin runs your project's own test command and reads the report files it writes.

**What it writes.** Only `.boss-test-explorer/pytest-report.xml` inside the open project, and only
when the project is a pytest project. Gradle and Maven write their reports where they already do.

**Privileges.** It runs your test command with your user's privileges, exactly as running it in the
terminal does. That is the point of it, and worth stating plainly: `test_run` is an executing tool
and is declared `readOnly = false` so the host governs it accordingly.

## Scope and limits

Stated here rather than discovered later.

- **First release (0.1.0).** It runs a project's tests and reports them. It does not watch files,
  debug, edit test code, or run a single test by clicking it.
- **Only JUnit-XML runners.** A runner that does not emit that format (a bare `npm test`,
  `cargo test`, `go test` without a JUnit reporter) is not detected. That is a deliberate v1 scope,
  not a claim those cannot be added.
- **Rerun-failed on Windows with spaced test names is imprecise.** The command goes through
  `cmd /c` so a `.bat` wrapper resolves, and `cmd` resplits an argument containing spaces, so a
  Kotlin test named with backticks may not filter exactly. The full run is unaffected.
- **A stopped run keeps the previous report.** Stop kills the process tree and abandons the run
  rather than reporting a half-finished one.
- **Reports are matched by modification time.** A run that writes no report at all (a compile
  error) is reported as exactly that, but a runner that leaves a report untouched because it
  skipped work will look like it produced nothing.
- **Validated live on BOSS 9.5.22 (Windows 11) with pytest.** Gradle and Maven are covered by
  tests but not yet by a live run inside the host, and macOS is untested. See
  [docs/VALIDATION.md](docs/VALIDATION.md) for exactly what has and has not been tested.

## License

Apache-2.0. See [LICENSE](LICENSE).
