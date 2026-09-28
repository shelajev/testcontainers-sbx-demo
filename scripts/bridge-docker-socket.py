#!/usr/bin/env python3
"""Expose one sandbox's Docker socket on a host-only Unix socket via SSH sessions."""

import argparse
import os
import socket
import socketserver
import subprocess
import sys
import threading


class DockerConnection(socketserver.BaseRequestHandler):
    def handle(self):
        request_start = b""
        while b"\r\n" not in request_start and len(request_start) < 8192:
            data = self.request.recv(8192)
            if not data:
                return
            request_start += data

        request_line = request_start.split(b"\r\n", 1)[0]
        parts = request_line.split(b" ", 2)
        path = parts[1] if len(parts) == 3 else b""
        # These responses can outlive a client-side write half-close.
        streaming = (
            (b"/exec/" in path and b"/start" in path)
            or (b"/containers/" in path and (b"/attach" in path or b"/logs" in path))
            or b"/images/create" in path
            or b"/build" in path
        )

        process = subprocess.Popen(
            [
                "ssh",
                "-T",
                "-o",
                "BatchMode=yes",
                f"{self.server.sandbox}.sbx",
                "socat",
                "STDIO",
                "UNIX-CONNECT:/var/run/docker.sock",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
        )

        def send_request():
            try:
                process.stdin.write(request_start)
                process.stdin.flush()
                while data := self.request.recv(65536):
                    process.stdin.write(data)
                    process.stdin.flush()
            except (BrokenPipeError, ConnectionError, OSError, ValueError):
                pass
            finally:
                if not streaming:
                    try:
                        process.stdin.close()
                    except OSError:
                        pass

        sender = threading.Thread(target=send_request, daemon=True)
        sender.start()
        try:
            while data := process.stdout.read1(65536):
                self.request.sendall(data)
        except (BrokenPipeError, ConnectionError, OSError):
            pass
        finally:
            try:
                self.request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            process.stdin.close()
            process.stdout.close()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait()


class DockerSocketServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sandbox", help="name of the existing sandbox")
    parser.add_argument("socket", help="host Unix socket to create")
    args = parser.parse_args()

    if os.path.lexists(args.socket):
        print(f"Socket path already exists: {args.socket}", file=sys.stderr)
        return 1

    previous_umask = os.umask(0o077)
    try:
        server = DockerSocketServer(args.socket, DockerConnection)
    finally:
        os.umask(previous_umask)
    server.sandbox = args.sandbox
    print(f"Docker bridge listening on {args.socket}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        os.unlink(args.socket)
    return 0


if __name__ == "__main__":
    sys.exit(main())
