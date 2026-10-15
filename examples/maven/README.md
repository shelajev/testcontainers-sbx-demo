# Standalone JitPack consumer

Copy this entire directory (including `.mvn`, `mvnw`, and `mvnw.cmd`) into any directory. It has no parent POM, source inclusion, or dependency on a local build of the strategy.

Install Java 27+ and [Docker Sandboxes](https://docs.docker.com/ai/sandboxes/install/), then run `sbx login` once. Run:

```sh
./mvnw -B -ntp test
```

The POM resolves the sbx strategy from JitPack using a pinned Git commit. The first Docker client request provisions the runtime; the test starts nginx, checks HTTP from the host and executes a command inside nginx. Standard Testcontainers Ryuk runs inside the sandbox.

The checked-in pin predates project sandbox reuse: that version removes its sandbox at JVM exit. The current repository source retains one sandbox and its image cache per canonical project directory, removing port publications when the last test JVM exits. Once that revision is published, update the pin to use it. Set `-Dsbx.projectDirectory=/absolute/project/root` when different working directories should share the same project sandbox.

Surefire sets `TESTCONTAINERS_DOCKER_CLIENT_STRATEGY` for this test JVM to bypass a cached discovery choice without changing your home-directory configuration. Explicit `DOCKER_HOST` or `tc.host` still takes precedence; the test fails if another provider is selected. Remove those overrides for this example.

If your Maven process finds an older sbx installation than your shell does, select the binary explicitly:

```sh
./mvnw -B -ntp -Dsbx.executable=/absolute/path/to/sbx test
```

To prove the dependency downloads from JitPack rather than a local install:

```sh
./mvnw -B -ntp -Dmaven.repo.local="$(mktemp -d)" test
```

This downloads Maven plugins and all dependencies again. Do not install the library locally for this check. See the strategy repository README for runtime boundaries and configuration options.
