# Testcontainers on Docker Sandboxes

An experimental Testcontainers Java provider that starts Docker inside a local `sbx` sandbox **when the test JVM first needs a Docker client**. Add the library as a test dependency; ordinary Testcontainers containers keep their existing API. No bridge scripts, manually created sandbox, Docker Desktop, or JUnit launcher extension are needed.

The Maven build at the repository root produces the strategy JAR. [`examples/maven`](examples/maven) is an independent, copyable project that downloads that JAR from **JitPack**, not from the root build or your local Maven install.

## Run the example

Requirements:

- Java 17 or newer, macOS or Linux, and OpenSSH (`ssh`) on `PATH`.
- A supported local [Docker Sandboxes installation](https://docs.docker.com/ai/sandboxes/install/), signed in with `sbx login`.
- A sandbox template providing Docker and `socat`. The default is `docker.io/docker/sandbox-templates:shell-docker`.
- Network access for Maven dependencies, the template, nginx, and Ryuk images. The provider does not change your network policy.

```sh
cd examples/maven
./mvnw -B -ntp test
```

Copy the entire `examples/maven` directory, including its `.mvn` directory and Maven wrapper, into another repository to try it independently. The example pins a published commit and asserts that Testcontainers actually selected the sbx provider. It checks HTTP readiness, calls nginx from the host JVM using `getHost()` and `getMappedPort()`, and runs a command inside the container.

If several `sbx` installations exist, select the one matching the running daemon:

```sh
./mvnw -B -ntp -Dsbx.executable=/absolute/path/to/sbx test
```

## Add the dependency

Use the exact `sbx.version` from the [example POM](examples/maven/pom.xml). These are JitPack coordinates; the GitHub repository name is also the published artifact name.

```xml
<repositories>
  <repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.shelajev</groupId>
  <artifactId>testcontainers-sbx-demo</artifactId>
  <version><!-- copy sbx.version from examples/maven/pom.xml --></version>
  <scope>test</scope>
</dependency>
```

The provider uses `META-INF/services/org.testcontainers.dockerclient.DockerClientProviderStrategy`. Creating or discovering the provider does not launch a process. `getTransportConfig()` lazily creates one sandbox for the selected provider; Testcontainers' singleton client then shares it across tests in that JVM. It works independently of the test framework.

### Selection and existing Docker configuration

The provider follows Testcontainers' existing discovery rules. An explicit `tc.host` or `DOCKER_HOST` is considered before discovered strategies. A saved strategy in `~/.testcontainers.properties` can also be tried first. The provider never edits that file, disables Ryuk, or overrides those explicit host settings.

To bypass a saved discovery choice for a particular test run, set:

```sh
TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=dev.agentcontainers.sbx.SbxDockerClientProviderStrategy ./mvnw test
```

The example configures this environment variable in Surefire. With Testcontainers 2.0.5, the sbx provider is non-persistable, so it is selected through the normal service-provider list at priority 200 after the configured-strategy step is skipped. This avoids saving a project-specific dependency into global configuration.

This is **not strict sandbox-only enforcement**: explicit Docker host configuration still wins, and Testcontainers may try another runtime if sbx initialization fails. The example asserts the selected strategy before starting its application container so that fallback cannot produce a misleading passing demonstration. If your application requires strict selection, check `DockerClientFactory.instance().isUsing(SbxDockerClientProviderStrategy.class)` after initializing its client, as the example does.

## Runtime and cleanup

```text
host test JVM → private Unix socket → SSH exec/socat → sandbox Docker socket
host test JVM → 127.0.0.1:PORT → sbx publication → Docker-assigned port → container
```

Each test JVM creates a uniquely named `tc-java-<uuid>` sandbox with no host workspace mounts and shared skills disabled. Defaults are 2 CPUs and 2 GiB of memory. Parallel JVMs own separate sandboxes. The provider uses its own temporary SSH configuration with sbx host-key verification; it does not run `sbx setup ssh` or edit your SSH configuration.

Docker start, restart, unpause, inspect, and list commands synchronously publish the required ports before returning. Guest Docker host ports are published on the **same numbered host IPv4 loopback ports**, including Ryuk's port. A collision fails the operation instead of returning an unreachable endpoint or selecting a different port behind Testcontainers' back. Containers sharing a Docker network communicate normally inside the sandbox.

Ryuk stays enabled, mounting the guest `/var/run/docker.sock`. On normal JVM exit, the provider closes its bridge and removes only its own sandbox after checking its UUID; this also removes its port publications, images, containers, and volumes. Testcontainers container reuse is therefore not supported across JVMs. A forcibly killed JVM or failed cleanup can leave a sandbox behind. Inspect `sbx ls`, then explicitly remove the logged name with `sbx rm --force NAME`.

Configuration is read from Java system properties only when the runtime is needed:

| Property | Default | Purpose |
| --- | --- | --- |
| `sbx.executable` | `sbx` on `PATH` | CLI binary, preferably an absolute path when several versions are installed |
| `sbx.template` | `docker.io/docker/sandbox-templates:shell-docker` | Template containing Docker and `socat` |
| `sbx.cpus` | `2` | Positive CPU allocation |
| `sbx.memory` | `2g` | Memory allocation in sbx format |
| `sbx.startupTimeoutSeconds` | `120` | Command timeout and Docker readiness budget |

For example, `./mvnw -Dsbx.memory=4g -Dsbx.cpus=4 test` configures the example's sandbox. The template uses sbx's `missing` pull policy; use an immutable template reference when reproducibility is required.

## Current boundaries

- This implementation targets local macOS/Linux sandboxes. Windows, cloud sandboxes, and a test JVM running inside another container are outside its supported scope.
- Same-number publication can collide with host services or other sandboxes. TCP and UDP IPv4 bindings are supported by the publisher; IPv6-only and interface-specific Docker bindings are rejected. The live smoke test covers TCP.
- Bind mounts resolve inside the mountless sandbox, so arbitrary host filesystem binds do not work. Use Testcontainers file-copy APIs or streamed image build contexts. Host Docker images are not automatically imported.
- Containers launched through a guest Docker socket or external client are not automatically observed. An inspect/list through the wrapped client acts as a publication barrier. Compose, Kubernetes/Kind, reverse host-port exposure, and external-client orchestration need separate compatibility work.
- Per-JVM isolation costs VM startup time and loses the guest Docker image cache at JVM exit. The SSH bridge starts a process per Docker API connection. These are deliberate initial implementation tradeoffs, not performance parity claims.
- The host JVM remains on the host. Only Docker workloads run inside sbx. Existing sbx governance, authentication, image architecture, and guest-kernel constraints still apply.

## Build and verify the library

```sh
# Unit tests and packaging: no sbx login, VM, or Docker required.
./mvnw -B -ntp verify

# Opt-in live test: creates and removes a sandbox, exercises HTTP, logs,
# exec, file copy, and restart with Ryuk enabled.
./mvnw -B -ntp -Pintegration verify
```

JitPack runs the ordinary build with Java 17. The example is deliberately **not** a Maven reactor module, and JitPack never runs live integration tests. To validate publication, copy the example outside this checkout and use an empty Maven local repository; the pinned strategy must download from `https://jitpack.io`.

The original script-based experiment and its scoped upstream-suite results remain available at [commit f26fa18](https://github.com/shelajev/testcontainers-sbx-demo/tree/f26fa18c4b14d9091dff095618c976346259e491). Those historical results are not a compatibility claim for this strategy.
