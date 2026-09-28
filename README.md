# Testcontainers + Docker Sandboxes

Run ordinary Testcontainers tests from a Mac while their Docker daemon lives in one named Docker Sandbox. Maven, Gradle, and the test JVM stay on the host. The repository also shows how to run its small Maven test entirely inside SBX.

The Java test starts `nginx:1.27-alpine`, waits for HTTP 200, reads its Testcontainers mapped port, and fetches the page. It uses the public Testcontainers API. The bridge and port watcher are client-side scripts; there is no Testcontainers fork or SBX-specific Java code.

## Requirements

- Docker Sandboxes `sbx`, signed in. These commands were exercised on macOS with `sbx v0.46.0-rc5` and Docker Engine 29.8.1 inside a `shell` sandbox.
- On the host: Python 3, OpenSSH, Docker CLI, Java 21 or newer, and Maven. The optional upstream core run also needs JDK 17. A host Docker daemon is not needed.
- The tested `shell` sandbox has Docker Engine and `socat`. No kit is used.

## Host JVM, sandbox Docker daemon

Clone this repository and create one sandbox for it:

```sh
git clone https://github.com/shelajev/testcontainers-sbx-demo.git
cd testcontainers-sbx-demo
sbx create --name tc-sbx-demo shell .
sbx setup ssh
ssh tc-sbx-demo.sbx hostname
```

Use three terminals in this repository's root.

### Terminal 1: Docker API socket

```sh
python3 scripts/bridge-docker-socket.py tc-sbx-demo "$PWD/.sbx-docker.sock"
```

The script creates a user-only Unix socket on the host. Each Docker API connection runs `socat` through an SSH **session** into this sandbox's `/var/run/docker.sock`. It opens no Docker TCP listener, and it never connects to the host-side sandbox management daemon. Leave it running while tests execute.

### Terminal 2: mapped ports

```sh
export DOCKER_HOST="unix://$PWD/.sbx-docker.sock"
docker info --format '{{.Name}} {{.ServerVersion}}'
./scripts/publish-ports.sh tc-sbx-demo
```

`docker info` should name `tc-sbx-demo`. Start the watcher before the test. It watches container start events, publishes each Docker-assigned host port with `sbx ports`, and reconnects if the Docker event stream drops. Publications belong only to this named sandbox and bind to host loopback. A host test JVM needs each mapped port it will contact, including Ryuk's; it does not need every container port in every sandbox.

### Terminal 3: Maven test

```sh
export DOCKER_HOST="unix://$PWD/.sbx-docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn --no-transfer-progress clean test
```

Expected result: `Tests run: 1, Failures: 0, Errors: 0`. The socket override tells Ryuk where the Docker socket is **inside** SBX. The printed `http://localhost:PORT` URL is fetched by the host JVM through an `sbx ports` publication. To keep nginx alive for inspection, set `DEMO_PAUSE_SECONDS=60` on the Maven command, then run `sbx exec tc-sbx-demo docker ps` and `sbx ports tc-sbx-demo` in another terminal. Ryuk removes nginx after the JVM exits; the watcher leaves port publications in place.

## Try upstream Testcontainers Java core

The same host bridge can run upstream's core tests. This is a larger probe than the single nginx demo. Keep the three host terminals above running, then in Terminal 3:

```sh
git clone --depth 1 https://github.com/testcontainers/testcontainers-java.git upstream-testcontainers-java
cd upstream-testcontainers-java
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :testcontainers:test --init-script ../scripts/upstream-core.init.gradle --continue --no-daemon --max-workers=2 --console=plain --rerun-tasks
```

The Gradle init script selects ordinary core tests. It excludes Compose, container reuse and exposed-host integration, Docker Model Runner, MCP Gateway, two fixtures that pin images incompatible with this arm64 sandbox, and one TLS wait test whose three-second package-install deadline was unreliable under the parallel suite. This is a **scoped core suite**, not the full upstream suite. The tests and Gradle run on the host; only Docker runs in SBX. Build output stays in the cloned upstream repository, which is ignored by this demo's local Git checkout.

On Apple Silicon, many upstream tests use amd64-only images. Before the Gradle command, while the sandbox is running, enable emulation for this sandbox instance:

```sh
sbx exec tc-sbx-demo sh -lc 'mountpoint -q /proc/sys/fs/binfmt_misc || sudo mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc'
docker run --privileged --rm tonistiigi/binfmt --install amd64
```

The binfmt registration is runtime state and must be repeated if the sandbox stops. Some upstream Dockerfile fixtures install packages from Alpine's HTTP repository. If SBX policy blocks that exact endpoint, allow it only for this sandbox:

```sh
sbx policy log tc-sbx-demo --limit 20
sbx policy allow network --sandbox tc-sbx-demo dl-cdn.alpinelinux.org:80
```

The upstream experiment on 2026-09-28 used commit `8e549514e3f01c57d70546fbb8599d138f3903e5`, host JDK 17, and one `tc-java-sbx-suite` sandbox. The published command completed with **410 tests: 407 passed, 3 skipped, 0 failed, 0 errors** (`BUILD SUCCESSFUL` in 3m 16s). A separate run of this repository's host Maven test completed with **1 passed, 0 failed**; nginx and Ryuk were gone afterward.

The first exploratory core run had 8 failures among 413 tests. They exposed blocked Alpine HTTP package downloads, an amd64-only image without emulation, a short-lived container event that stopped the port watcher, Docker exec responses that stayed open through an SSH TCP forward, and an old RabbitMQ image that exited under emulation. The final scoped run used a narrow network rule, binfmt registration, the reconnecting watcher, the SSH session bridge, and the exclusions listed in the init script. These results do not claim that every upstream module or every core test works on this setup.

## Optional: put Maven and the test JVM inside SBX

The `shell` sandbox already has Java and Docker. Install Maven once in this named sandbox, then run the same test there:

```sh
sbx exec -u root tc-sbx-demo sh -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq maven'
sbx exec tc-sbx-demo mvn --no-transfer-progress clean test
```

This mode needs no host Docker bridge or port watcher because the test JVM contacts the sandbox's Docker socket and container ports locally. It was verified with the nginx demo; the upstream suite described above was run on the host.

## What is isolated?

| Resource | Host JVM mode | Inside SBX mode |
| --- | --- | --- |
| Java and Maven/Gradle | Host | Sandbox |
| Docker daemon, images, containers, networks, volumes | Sandbox | Sandbox |
| Docker API access | Host process that can open the bridge socket, plus sandbox processes | Sandbox processes |
| Build dependency cache | Host | Sandbox |
| Test JVM network policy | Host policy | SBX policy |
| Container traffic and image pulls | SBX policy on the tested setup | SBX policy on the tested setup |
| Container ports used by the JVM | Published individually to host loopback | Inside sandbox |

The workspace is bind-mounted read/write. A container can bind-mount paths visible inside SBX, including the workspace. It cannot automatically mount arbitrary Mac paths that are absent from SBX. Removing this sandbox deletes its Docker runtime and installed packages, but leaves the host checkout and build outputs.

## Limits and cleanup

The host bridge is demo glue, not a built-in SBX Docker context. On the tested SBX release, a normal SSH local port forward carried Docker API requests but held `docker exec` response streams open. A direct Unix socket SSH forward was rejected by the SBX SSH server. The session-based bridge above completed both streaming exec and longer container output in the host tests. It starts an SSH session per Docker API connection, so the upstream suite has extra connection cost.

The watcher assumes Docker's assigned sandbox port can be bound to the same free host loopback port. It leaves publications after containers stop; `sbx ports tc-sbx-demo` shows them. If a mapping conflicts, use `sbx ports tc-sbx-demo --unpublish HOST_PORT:SANDBOX_PORT`, or remove the sandbox when done. The host socket grants control of this sandbox's Docker daemon to processes that can open it.

Stop the watcher and bridge with Ctrl-C. Then remove this demo's sandbox:

```sh
sbx rm tc-sbx-demo
```

The `.sbx-docker.sock` file is removed when the bridge exits normally. If it remains after a forced exit, delete that socket file before restarting the bridge.
