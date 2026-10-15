# Testcontainers on Docker Sandboxes

An experimental Testcontainers Java provider that starts Docker inside a local `sbx` sandbox **when the test JVM first needs a Docker client**. Add the library as a test dependency; ordinary Testcontainers containers keep their existing API. No bridge scripts, manually created sandbox, Docker Desktop, or JUnit launcher extension are needed.

The Maven build at the repository root produces the strategy JAR. [`examples/maven`](examples/maven) is an independent, copyable project that downloads that JAR from **JitPack**, not from the root build or your local Maven install.

## Run the example

Requirements:

- Java 27 or newer and macOS or Linux.
- A supported local [Docker Sandboxes installation](https://docs.docker.com/ai/sandboxes/install/), signed in with `sbx login`.
- An sbx version supporting v3 kits and `run --detach`. The default shell kit is `docker.io/docker/sbx-kit-shell:1.0.0`; the Docker TCP mixin is bundled in this JAR.
- Network access for Maven dependencies, the shell kit, nginx, and Ryuk images. The provider does not change your network policy.

```sh
cd examples/maven
./mvnw -B -ntp test
```

Copy the entire `examples/maven` directory, including its `.mvn` directory and Maven wrapper, into another repository to try it independently. The example currently pins an earlier published commit with per-JVM sandbox cleanup; project sandbox reuse requires publishing this revision and updating that pin. It asserts that Testcontainers actually selected the sbx provider. It checks HTTP readiness, calls nginx from the host JVM using `getHost()` and `getMappedPort()`, and runs a command inside the container.

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
  <version>66df7453d352cd4a4e23cc8fa3edb41b296a515c</version>
  <scope>test</scope>
</dependency>
```

The provider uses `META-INF/services/org.testcontainers.dockerclient.DockerClientProviderStrategy`. Creating or discovering the provider does not launch a process. `getTransportConfig()` lazily creates or reconnects to the project sandbox; Testcontainers' singleton client shares its connection across tests in that JVM. It works independently of the test framework.

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
host test JVM → 127.0.0.1:AUTO_PORT → sbx publication → sandbox Docker TCP :2375
host test JVM → 127.0.0.1:PORT → sbx publication → Docker-assigned port → container
```

Each project uses a stable `tc-java-tcp-v1-<uuid>` sandbox name derived from its canonical directory path. The default directory is the test JVM's working directory; set `-Dsbx.projectDirectory=/absolute/project/root` to share it across Maven modules or IDE launch configurations. Separate checkouts use separate sandboxes. Parallel JVMs for the same project share its Docker daemon. New sandboxes have no host workspace mounts, shared skills disabled, 2 CPUs and 2 GiB of memory. The bundled kit configures Docker to listen on TCP port 2375 as well as its Unix socket. The kit’s port capability publishes that API to an automatically allocated loopback port; the provider discovers its IPv4 endpoint using `sbx ports --json`. No fixed host API port, SSH configuration, or socat bridge is needed. Source kits build in sbx’s builder sandbox by default, without a host Docker daemon; an explicit `SBX_KIT_BUILDER` environment setting takes precedence. The first build may need a longer `sbx.startupTimeoutSeconds` budget to download the builder and shell kits. The unauthenticated Docker API is accessible to local host processes.

Docker start, restart, unpause, inspect, and list commands synchronously publish the required ports before returning. Guest Docker host ports are published on the **same numbered host IPv4 loopback ports**, including Ryuk's port. A collision fails the operation instead of returning an unreachable endpoint or selecting a different port behind Testcontainers' back. Containers sharing a Docker network communicate normally inside the sandbox.

The provider discovers existing publications when it needs a new port, then caches successful publications within each JVM. The last JVM using a project sandbox removes its application-port loopback publications on normal exit, including stale publications from earlier runs. A shared OS file lock prevents cleanup while another participating JVM is active and prevents new JVMs from connecting during cleanup. Lock files live under `~/.testcontainers/sbx/`. The provider manages these publications exclusively; adding or changing matching publications externally is unsupported.

Standard Testcontainers Ryuk runs inside the sandbox, mounting the guest `/var/run/docker.sock`. It cleans up each test session's containers, networks and volumes. On JVM exit, the last JVM removes the application-port publications; the sandbox and pulled-image cache remain for the next run. This reuses the Docker runtime, without enabling Testcontainers' experimental container reuse.

To discard a project's sandbox and image cache, stop its test JVMs and run `sbx rm --force NAME` using the name logged by the provider or shown by `sbx ls`. The next test run recreates it. A stopped sandbox is started with `sbx run --name NAME --detach`. The sandbox stays running in detached mode after JVM exit, and the Docker API publication remains for subsequent JVMs. Run `sbx stop NAME` when it should stop consuming resources. The name includes the runtime layout version so incompatible sandboxes created by the SSH-based provider are left intact; the TCP provider creates its own sandbox automatically. Older sandboxes can be removed separately when no longer needed.

Configuration is read from Java system properties only when the runtime is needed:

| Property | Default | Purpose |
| --- | --- | --- |
| `sbx.projectDirectory` | JVM working directory | Canonical project path used to derive the persistent sandbox name |
| `sbx.executable` | `sbx` on `PATH` | CLI binary, preferably an absolute path when several versions are installed |
| `sbx.kit` | `docker.io/docker/sbx-kit-shell:1.0.0` | Base shell kit, composed with the bundled Docker TCP mixin |
| `sbx.cpus` | `2` | Positive CPU allocation |
| `sbx.memory` | `2g` | Memory allocation in sbx format |
| `sbx.startupTimeoutSeconds` | `120` | Command timeout and Docker readiness budget |

For example, `./mvnw -Dsbx.memory=4g -Dsbx.cpus=4 test` configures a new sandbox. Kit, CPU and memory settings apply only when creating it; remove the existing project sandbox to change them. The kit uses sbx's `missing` pull policy; use an immutable kit reference when reproducibility is required.

## Current boundaries

- This implementation targets local macOS/Linux sandboxes. Windows, cloud sandboxes, and a test JVM running inside another container are outside its supported scope.
- Same-number publication can collide with host services or other sandboxes. TCP and UDP IPv4 bindings are supported by the publisher; IPv6-only and interface-specific Docker bindings are rejected. The live smoke test covers TCP.
- Bind mounts resolve inside the mountless sandbox, so arbitrary host filesystem binds do not work. Use Testcontainers file-copy APIs or streamed image build contexts. Host Docker images are not automatically imported.
- Containers launched through a guest Docker socket or external client are not automatically observed. An inspect/list through the wrapped client acts as a publication barrier. Compose, Kubernetes/Kind, reverse host-port exposure, and external-client orchestration need separate compatibility work.
- Port publications remain until the last participating JVM exits, even if individual containers finish earlier. A forcibly killed JVM or failed CLI cleanup can leave publications behind; the next successful run cleans them up on exit. Older provider versions do not participate in locking and must not run concurrently against the same sandbox.
- The host JVM remains on the host. Only Docker workloads run inside sbx. Existing sbx governance, authentication, image architecture, and guest-kernel constraints still apply.

## Build and verify the library

```sh
# Unit tests and packaging: no sbx login, VM, or Docker required.
./mvnw -B -ntp verify

# Opt-in live test: creates or reuses the project sandbox; exercises HTTP, logs,
# exec, file copy, and restart with Ryuk enabled.
./mvnw -B -ntp -Pintegration verify
```

JitPack runs the ordinary build with Java 27. The example is deliberately **not** a Maven reactor module, and JitPack never runs live integration tests. To validate publication, copy the example outside this checkout and use an empty Maven local repository; the pinned strategy must download from `https://jitpack.io`.

The original script-based experiment and its scoped upstream-suite results remain available at [commit f26fa18](https://github.com/shelajev/testcontainers-sbx-demo/tree/f26fa18c4b14d9091dff095618c976346259e491). Those historical results are not a compatibility claim for this strategy.
