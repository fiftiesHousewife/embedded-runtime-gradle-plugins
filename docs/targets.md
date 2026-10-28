# Building for Linux, Mac and Windows

One build can make an archive for each OS and CPU you deploy to, and publish them all together:

```kotlin
javaApp {                                       // or nodeApp
    targets("linux-x64", "linux-aarch64", "windows-x64", "mac-aarch64")
}
```

or, without changing the build, `./gradlew build -PjavaApp.targets=linux-x64,windows-x64`. The
default is the machine you build on. A Mac or Windows laptop can build Linux archives with no
containers or WSL.

Only the archive for the machine you build on can be started there, so only that one is
smoke-tested. `publish` refuses archives that were never started unless you pass
`-PjavaApp.allowUntestedPublish=true` (`nodeApp.` for Node.js). The safest setup
publishes each target from a CI job running on it; [smoke-testing the published app](publishing.md#smoke-testing-a-published-app)
on each target afterwards is the alternative.
