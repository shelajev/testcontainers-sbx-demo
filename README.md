# Testcontainers + Docker Sandboxes

Run the same Java Testcontainers test in two ways:

1. **Inside SBX:** Maven, the test JVM, and Docker run in one sandbox. This is the shortest way to try the demo.
2. **Host-driven:** Maven and the test JVM stay on your Mac, while Docker runs in the sandbox. This shows what an IDE-friendly integration could look like, with explicit bridge and port-publishing steps.

The test starts `nginx:1.27-alpine`, waits for HTTP 200, asks Testcontainers for the mapped port, and fetches the page. It uses the public Testcontainers API; there is no fork, patched library, or sbx-specific Java code.

## Requirements

- Docker Sandboxes `sbx` installed and signed in. These instructions were exercised on macOS with `sbx v0.46.0-rc5`; CLI behavior may differ in other versions.
- Network access for the shell sandbox to install Maven, resolve Maven dependencies, and pull nginx and Ryuk images. Your SBX network policy may require narrowly scoped allows.
- For the optional host-driven mode: Java 21 or newer, Maven, the Docker CLI, and OpenSSH on the host. No host Docker daemon is required.

The tested `shell` sandbox already includes Docker Engine, Java 25, and `socat`. No kit is used.

## Quick start: run everything inside SBX

Clone this repository and create a named sandbox with the repository mounted as its workspace:

```sh
git clone https://github.com/shelajev/testcontainers-sbx-demo.git
cd testcontainers-sbx-demo
sbx create --name tc-sbx-demo shell .
```

Install Maven inside the sandbox. Java and Docker are already there:

```sh
sbx exec -u root tc-sbx-demo sh -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq maven'
```

Run the test:

```sh
sbx exec tc-sbx-demo mvn --no-transfer-progress test
```

Expected result: `Tests run: 1, Failures: 0, Errors: 0`. The log should say Testcontainers found `unix:///var/run/docker.sock` and print an nginx URL such as `http://localhost:32769`. That address is reached **by the test JVM inside SBX**; it does not require a host port publication.

To inspect Ryuk and nginx while the test is alive, run the test with a pause in one terminal:

```sh
sbx exec -e DEMO_PAUSE_SECONDS=60 tc-sbx-demo mvn --no-transfer-progress test
```

In another terminal:

```sh
sbx exec tc-sbx-demo docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

Run the test again for a warm run. The named sandbox retains its Maven dependencies and Docker image layers:

```sh
time sbx exec tc-sbx-demo mvn --no-transfer-progress test
```

## Optional: keep Maven and Java on the host

Here the host test JVM needs two separate paths into SBX:

```text
Docker API:   host Unix socket → SSH → sandbox loopback → sandbox Docker socket
Container:    host localhost:random-port → sbx ports → sandbox Docker port
```

The Docker API path lets Testcontainers create containers. The port path lets the host JVM contact Ryuk and nginx. `sbx` does not automatically publish Docker's random mapped ports. The small [port watcher](scripts/publish-ports.sh) uses Docker events and `sbx ports` to publish them for this named sandbox.

Use three terminals from this repository's root. Do not run host and sandbox Maven simultaneously; both write to the bind-mounted `target/` directory.

### Terminal 1: bridge the Docker API

Configure SBX SSH once, then confirm that the named sandbox is reachable:

```sh
sbx setup ssh
ssh tc-sbx-demo.sbx hostname
```

Expose the sandbox's inner Docker socket on **sandbox loopback only**:

```sh
sbx exec tc-sbx-demo sh -lc 'nohup socat TCP-LISTEN:23750,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:/var/run/docker.sock >/tmp/tc-sbx-socat.log 2>&1 </dev/null & echo $!'
```

Keep this SSH forward running. It creates a user-owned Unix socket in the repository root; `.gitignore` excludes it:

```sh
ssh -N -o ExitOnForwardFailure=yes -L "$PWD/.sbx-docker.sock:127.0.0.1:23750" tc-sbx-demo.sbx
```

If an interrupted SSH session left a stale `.sbx-docker.sock`, delete that socket file before starting the forward again. The host-side `sandboxd/docker.sock`, which reports `docker-next`, is the sandbox **management** daemon and is not used here.

### Terminal 2: watch Docker ports

```sh
export DOCKER_HOST="unix://$PWD/.sbx-docker.sock"
docker info --format '{{.Name}} {{.ServerVersion}}'
./scripts/publish-ports.sh tc-sbx-demo
```

`docker info` should report `tc-sbx-demo`. Leave the watcher running before starting Maven. It prints a `Published 127.0.0.1:...` line for Ryuk and another for nginx.

### Terminal 3: run the host test

```sh
export DOCKER_HOST="unix://$PWD/.sbx-docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
DEMO_PAUSE_SECONDS=60 mvn --no-transfer-progress test
```

The socket override gives Ryuk the socket path **inside** the sandbox. The test should print an endpoint such as `http://localhost:32781`, fetch nginx from the host JVM, and pass.

While it pauses, inspect the two containers and the per-sandbox port publications in another terminal:

```sh
sbx exec tc-sbx-demo docker ps --format 'table {{.Names}}\t{{.Ports}}'
sbx ports tc-sbx-demo
```

After the JVM exits, Ryuk removes nginx and itself. A brief `Can not connect to Ryuk` warning can appear before the watcher publishes its port; the verified host run passed after that retry. `sbx ports` entries remain; the watcher does not unpublish them. If a host port is occupied or an old mapping conflicts, remove that mapping with `sbx ports tc-sbx-demo --unpublish HOST:SANDBOX`, or use a fresh named sandbox.

## What is isolated?

| Resource | Inside-SBX mode | Host-driven mode |
| --- | --- | --- |
| Java and Maven | Sandbox | Host |
| Docker socket and daemon | Sandbox | Sandbox; API bridged to a host Unix socket |
| Maven cache | Sandbox `/home/agent/.m2` | Host `~/.m2` |
| Docker images, containers, networks, volumes | Sandbox | Sandbox |
| Test JVM network policy | SBX policy | Host policy |
| Container ports used by test JVM | Inside sandbox | Published individually to host loopback |

The workspace is bind-mounted read/write, so source files and `target/` remain on the host. A container can bind-mount workspace paths visible to the sandbox daemon. It cannot automatically mount arbitrary host paths that are absent inside SBX. On the test machine, an nginx container could mount this project, while a mount of the host's `.ssh` path failed because that path was not present inside the sandbox.

`sbx policy ls tc-sbx-demo` and `sbx policy log tc-sbx-demo --limit 20` show the effective policy and observed requests. The test machine had a global policy with many allowed domains; the observed denial of `example.com` from the sandbox and a nested container is not a claim that all nested-container traffic is covered by policy on every setup.

## Cleanup

Stop the port watcher and SSH command with Ctrl-C. Remove this demo's named sandbox:

```sh
sbx rm tc-sbx-demo
```

This removes its Docker runtime state and published ports, along with packages and caches installed inside it. The checked-out repository, shared `target/` output, and any host Maven cache remain. Remove a stale `.sbx-docker.sock` if SSH left one behind.

## Verified behavior and current limits

This was validated on macOS with `sbx v0.46.0-rc5`, Docker Engine 29.8.1 in the sandbox, and Testcontainers 2.0.5. Both execution modes passed. On the earlier `sbx v0.45.0-rc2`, Ryuk also cleaned up after the host test JVM was forcibly killed.

The host bridge uses supported SSH access plus `socat`; it is demo glue, not a built-in SBX Docker context. The host Unix socket grants control of the sandbox's Docker daemon to processes that can open it. The watcher assumes each Docker-assigned port can be bound to the same free number on host loopback and leaves mappings behind until removed. The test uses an HTTP wait strategy because Testcontainers' default port wait hung on a Docker `exec` stream over the bridge during the earlier experiment. That streaming behavior has not been fully diagnosed.

A future SBX/Testcontainers integration could manage the Docker API endpoint, mapped port translation, Ryuk, and cleanup as one lifecycle. This repository demonstrates those pieces with ordinary client-side commands so their boundaries remain visible.
