# Standalone JitPack consumer

Copy this entire directory (including `.mvn`, `mvnw`, and `mvnw.cmd`) into any directory. It has no parent POM, source inclusion, or dependency on a local build of the strategy.

Install Java 17+, OpenSSH, and [Docker Sandboxes](https://docs.docker.com/ai/sandboxes/install/), then run `sbx login` once. Run:

```sh
./mvnw -B -ntp test
```

The POM resolves the sbx strategy from JitPack using a pinned Git commit. The first Docker client request creates a mountless sandbox; the test starts nginx, checks HTTP from the host and executes a command inside nginx. Ryuk is enabled. Normal JVM exit removes the sandbox and its port publications.

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
