# Plan: Gradle-native launchers and distributions

Status: agreed 2026-10-25; spike done the same day (findings at the end); implementation in progress. Owner decision: build on Gradle's own
`application`/`distribution` machinery instead of beside it; no custom installer.

## Why

Today the Java plugin generates its own launchers (`GenerateLaunchers`), assembles each target with
its own `Sync` (`appAssemble<T>`) and archives it with its own `Tar`/`Zip` (`appArchive<T>`), while
the `application` plugin it applies still builds `startScripts`, `installDist`, `distTar` and
`distZip`, unused, next to them. Two parallel pipelines confuse users and duplicate what Gradle
already supports and documents.

What Gradle provides, and what it does not:

| Gradle task | What it does | Gap for us |
|---|---|---|
| `startScripts` (`CreateStartScripts`) | Writes `bin/<app>` (POSIX `sh`) and `bin/<app>.bat`: resolves `APP_HOME` through symlinks, finds Java through `JAVA_HOME` else `PATH`, raises the open-files limit, converts paths on Cygwin/MSYS, builds the classpath, adds `DEFAULT_JVM_OPTS`, `JAVA_OPTS` and `<APP>_OPTS`, `exec`s `java <mainClass> "$@"` | Never uses an embedded runtime; no `run`/`healthcheck`/`version`; no `APP_ROOT` layout; no locked options |
| Customising it | `unixStartScriptGenerator.template = resources.text.fromFile(…)` (Groovy `SimpleTemplateEngine`; bindings `applicationName`, `mainClassName`, `moduleEntryPoint`, `classpath`, `modulePath`, `defaultJvmOpts`, `optsEnvironmentVar`, `exitEnvironmentVar`, `appNameSystemProperty`, `appHomeRelativePath`, `entryPointArgs`, `gitRef`), or a whole `ScriptGenerator` implementation | The template bindings are fixed: no place for our own settings (locked options) without a custom generator |
| `distribution` plugin | Named distributions (`distributions { create("x") { contents { … } } }`), each with `xDistTar`, `xDistZip`, `installXDist`; archives wrap contents in `<base>-<version>/` | Nothing per OS/CPU; we add one distribution per target |
| `installDist` | Copies a distribution into a directory (`build/install/<app>`) | A local copy, not a server installer: no service, no config handling, no switch or rollback |

## Target design

```
startScripts (Gradle's task; our ScriptGenerator)  ──┐
appManifest<T>, appSbom<T>                          ──┼─► distribution "<target>" ─► <target>DistTar / DistZip ─► publish
customiseRuntime<T> (Java) / runtime (Node)         ──┘                    └──────► install<Target>Dist (build/install/…)
                                  appVerifyPolicy<T> and appSmokeTest check the installed distribution
                                  appRpm<T> packs the installed distribution
```

- **Launchers (Java):** the standard `startScripts` task, with our `EmbeddedRuntimeScriptGenerator`
  (implements Gradle's `ScriptGenerator`) set as both generators. It renders our two templates
  (ported from `launcher.sh`/`launcher.bat`) from Gradle's `JavaAppStartScriptGenerationDetails`
  plus our own settings (runtime path, locked options, operator `jvm.options`). Users keep the
  standard knobs: `application { applicationDefaultJvmArgs }`, `startScripts { defaultJvmOpts,
  optsEnvironmentVar }`, or their own template/generator, documented as "replace at your own risk:
  the smoke test still has to pass".
- **Launchers (Node):** no `application` plugin exists for Node; keep `GenerateLaunchers` but have
  it share the generator's template engine so both ecosystems render from one place.
- **Archives:** one Gradle `distribution` per target, named by target id (`linuxX64`, …), with
  `distributionBaseName = appName` and the archive classifier set to the target id so file names and
  Maven classifiers stay exactly as today (`<app>-<version>-<target>.tar.gz`). Contents: `bin/`
  (only that OS's script), `lib/` (jar + `runtimeClasspath`) or `app/` (Node), `runtime/`,
  `conf.defaults/`, `META-INF/app.json`, `META-INF/sbom.cdx.json`.
- **The `main` distribution** becomes this machine's target, so the familiar `installDist`,
  `distTar` and `distZip` do the expected thing: this machine's app with its runtime.
- **Format:** `archiveFormat` picks which of `DistTar`/`DistZip` is built and published; tar.gz by
  setting `compression = GZIP` and `archiveExtension = "tar.gz"` on the Tar task.
- **Lifecycle names kept:** `appArchive` (all targets), `appSmokeTest`, `runApp`, `appRpm`,
  `appDockerContext`, `appVerifyPolicy<T>` keep working, wired to the new tasks.
- **Removed:** `GenerateLaunchers` (Java use), `appAssemble<T>`, `appArchive<T>`, `appInstall`,
  `appRollback`, `InstallLayout`, `InstallSettings`, `Installation`, `AppDeployPlugin`
  (`app-deploy`) and their tests and docs.

## Steps

Each step ends with `./gradlew check` green and a commit.

1. **Spike (half a day, throwaway branch).** Confirm, in a TestKit fixture:
   - a custom `ScriptGenerator` on `startScripts` works with the configuration cache (check whether
     the generator properties are `@Nested`/`@Internal` and whether the generator must be
     `Serializable`);
   - `SimpleTemplateEngine` escaping for a POSIX script full of `$` (Gradle's own template escapes
     as `\$`), and CRLF output for `.bat`;
   - per-target distributions: archive top directory, classifier, reproducible ordering and
     timestamps (our global `AbstractArchiveTask` settings apply), exec bits on `bin/**` and
     `runtime/bin/**`;
   - what `JavaAppStartScriptGenerationDetails.classpath` contains, and that `lib/<app>.jar` can stay
     first on the classpath.
   Record findings at the bottom of this file; adjust the steps if anything fails.
2. **Generator and templates.** Add `EmbeddedRuntimeScriptGenerator` and the two templates to
   `core`; port `LockedOptions` and the `jvm.options` lines into them; keep
   `LauncherTemplateTest`, `LockedOptionsTest`, `JavaLaunchCommandTest` as unit tests against the
   rendered output (no Gradle needed). Wire it onto `startScripts` in the Java plugin; the archive
   still uses the old pipeline at this point, fed from `startScripts`'s output.
3. **Per-target distributions.** Replace `appAssemble<T>`/`appArchive<T>` in `TargetPipeline` with
   `distributions.register(target)`; point `TargetTasks` at the install and archive tasks; repoint
   `AppPublishing` variants, `HostTasks` (smoke test, `runApp`), `DockerContext`, `RpmPackages` and
   `VerifyRuntimePolicy` at them. Make `main` the host target.
4. **Node.** Move Node onto per-target distributions too (contents from `NodeTargetTasks`), launchers
   from the shared template engine.
5. **Remove the installer.** Delete the install tasks, `app-deploy` and their tests; keep the
   runtime policy gate on the distribution (`appVerifyPolicy<T>`) and in the RPM build.
6. **Docs.** `docs/your-app.md` (launcher and how to customise it the Gradle way),
   `docs/packaging.md` (distributions, `installDist`, formats; installation via RPM/existing tooling),
   `docs/reference.md` (tasks), `AGENTS.md` (pipeline diagram, test map), the skills under
   `claude-plugin/`, and the article's launcher and archive passages.

## Tests to add or change

- Default launcher uses the embedded runtime even with `JAVA_HOME` pointing elsewhere.
- A project's own `startScripts` template is honoured (and its archive still has to pass the smoke
  test).
- `installDist` produces this machine's app with its runtime; `install<Target>Dist` for a cross
  target produces that target's tree.
- Archive names, classifiers, Gradle Module Metadata variants and byte-reproducibility unchanged
  (existing `JavaAppTargetsTest`, `JavaAppPluginTest` assertions keep passing).
- `.bat` output has CRLF and `--healthcheck` spacing (existing `LauncherTemplateTest`).
- Configuration cache reused on a second run (existing reproducibility test).

## Risks

| Risk | Mitigation |
|---|---|
| Custom `ScriptGenerator` not configuration-cache safe | Spike first; fall back to template-only customisation with our settings encoded in the template file we pass |
| Users replacing the template break `run`/`healthcheck` | The smoke test runs the real archive, so a broken launcher fails the build |
| Renamed tasks break user build scripts | Keep the lifecycle names; document the per-target task names in the reference |
| Node without `application` plugin diverges | Shared template engine; same distribution layout |

## Estimate

About a day of work after the spike, plus the spike itself (half a day).

## Spike findings (2026-10-25)

Run in a throwaway project on Gradle 9.8.2 with only Gradle's own `application` and `distribution`
plugins, twice in a row with the configuration cache on.

| Question | Finding |
|---|---|
| Custom `ScriptGenerator` with the configuration cache | Works: the entry is stored on the first run and reused on the second. The generator is a `Serializable` class holding only plain values (target OS, locked flag) |
| What the generator is given | `JavaAppStartScriptGenerationDetails`: `applicationName`, `mainClassName`, `defaultJvmOpts` (from `application { applicationDefaultJvmArgs }`), `optsEnvironmentVar` (`<APP>_OPTS`), `classpath` relative to the app home with the app's jar first (`lib/spike-1.2.3.jar`, then dependencies), `scriptRelPath` (`bin/spike`). There is **no** `appHomeRelativePath`: Gradle computes that only for its own template generator, so ours derives `APP_HOME` from `scriptRelPath` (one directory up from `bin/`) |
| Windows line endings | The generator writes CRLF itself and Gradle keeps it |
| Reproducible archives | Our existing `AbstractArchiveTask` settings apply to distribution archives: fixed timestamps (1970) and stable order |
| Exec bits | A `filesMatching` on the distribution's root spec does not reach a child `into("bin")` spec; set `filePermissions { unix("rwxr-xr-x") }` on the `bin` child spec itself. `runtime/` keeps the modes it was copied with |
| Archive name | Set `archiveFileName` on each target's `DistTar`/`DistZip` to keep `<app>-<version>-<target>.<ext>`; Maven classifiers are set on the publication, not derived from the file name |
| Top-level directory | The distribution plugin names it `<app>-<version>-<target>/`, not today's `<app>-<version>/`, whatever the archive is called. **Accepted as a contract change**: archives for two targets now unpack side by side, and nothing downstream needs the old name (the smoke test, Docker context and RPM all take the single top-level directory, whatever it is called) |
| `install<Target>Dist` | Installs into `build/install/<app>` |

Consequences for the steps above: in step 2 the generator computes `APP_HOME` from `scriptRelPath`;
in step 3 archive names come from `archiveFileName`, the `bin` spec sets its own permissions, and the
docs describe the new top-level directory.
