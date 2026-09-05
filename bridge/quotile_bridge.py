#!/usr/bin/env python3
"""Quotile quota-only bridge. Python 3.10+, standard library, no model turns."""
from __future__ import annotations

import argparse
import collections
import copy
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import queue
import re
import secrets
import signal
import socket
import ssl
import stat
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.0.0"
MAX_RPC_LINE = 1024 * 1024
ERRORS = {"upstream_timeout", "upstream_unavailable", "bucket_unavailable", "quota_window_unavailable"}


class UpstreamError(Exception):
    def __init__(self, code="upstream_unavailable"):
        self.code = code if code in ERRORS else "upstream_unavailable"
        super().__init__(self.code)


def empty_snapshot():
    return {"schemaVersion": 1, "plan": None, "weekly": None, "fiveHour": None,
            "updatedAt": 0, "stale": True, "error": "upstream_unavailable"}


def normalize(result, limit_id="codex", now=None):
    """Unknown windows stay null. Never infer a plan tier or quota amount."""
    if not isinstance(result, dict):
        raise UpstreamError()
    now = int(time.time()) if now is None else int(now)
    out = empty_snapshot()
    out.update(updatedAt=now, stale=False, error=None)
    buckets = result.get("rateLimitsByLimitId")
    if buckets is not None:
        if not isinstance(buckets, dict):
            raise UpstreamError()
        bucket = buckets.get(limit_id)
    else:
        bucket = result.get("rateLimits")
        if isinstance(bucket, dict) and bucket.get("limitId", "codex") != limit_id:
            bucket = None
    if not isinstance(bucket, dict) or bucket.get("limitId", limit_id) != limit_id:
        out["error"] = "bucket_unavailable"
        return out
    plan = bucket.get("planType")
    if isinstance(plan, str) and 0 < len(plan) <= 80 and not any(ord(c) < 32 for c in plan):
        out["plan"] = plan
    candidates = {300: [], 10080: []}
    for key in ("primary", "secondary"):
        window = bucket.get(key)
        if not isinstance(window, dict):
            continue
        duration = window.get("windowDurationMins")
        if type(duration) is not int or duration not in candidates:
            continue
        used, reset = window.get("usedPercent"), window.get("resetsAt")
        valid_used = type(used) in (int, float) and math.isfinite(used) and 0 <= used <= 100
        valid_reset = type(reset) is int and 0 < reset <= 4102444800
        candidates[duration].append({"remainingPercent": round(100 - used, 6), "resetsAt": reset}
                                    if valid_used and valid_reset else None)
    for duration, name in ((300, "fiveHour"), (10080, "weekly")):
        matches = candidates[duration]
        out[name] = matches[0] if len(matches) == 1 else None
    if out["weekly"] is None and out["fiveHour"] is None:
        out["error"] = "quota_window_unavailable"
    if any(out[key] and out[key]["resetsAt"] <= now for key in ("weekly", "fiveHour")):
        out["stale"] = True
    return out


class CodexRPC:
    """One persistent stdio app-server; timeout discards that process and its queue."""
    def __init__(self, command=None, timeout=4.0):
        self.command = command or ["codex", "app-server"]
        self.timeout = timeout
        self._lock = threading.Lock()
        self._proc = None
        self._queue = None
        self._reader = None
        self._next_id = 1

    @staticmethod
    def _pump(proc, inbox):
        def emit(item):
            try:
                inbox.put_nowait(item)
                return True
            except queue.Full:
                return False
        try:
            while True:
                raw = proc.stdout.readline(MAX_RPC_LINE + 1)
                if not raw or len(raw) > MAX_RPC_LINE or not raw.endswith(b"\n"):
                    break
                try:
                    message = json.loads(raw)
                except (ValueError, UnicodeError):
                    break
                if not isinstance(message, dict):
                    break
                # Notifications contain no quota response and are deliberately not retained.
                if "id" in message and not emit(message):
                    break
        except (OSError, ValueError):
            pass
        finally:
            emit(None)

    def _send(self, message):
        try:
            self._proc.stdin.write(json.dumps(message, separators=(",", ":")).encode() + b"\n")
            self._proc.stdin.flush()
        except (OSError, ValueError, AttributeError):
            raise UpstreamError() from None

    def _request(self, method, deadline, params=None):
        request_id = self._next_id
        self._next_id += 1
        message = {"id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self._send(message)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise UpstreamError("upstream_timeout")
            try:
                reply = self._queue.get(timeout=remaining)
            except queue.Empty:
                raise UpstreamError("upstream_timeout") from None
            if reply is None:
                raise UpstreamError()
            if "method" in reply:
                # This client cannot grant approvals, supply credentials, or start work.
                self._send({"id": reply.get("id"), "error": {"code": -32601, "message": "Unsupported method"}})
                continue
            if reply.get("id") != request_id:
                continue
            if "error" in reply or "result" not in reply:
                raise UpstreamError()
            return reply["result"]

    def read(self):
        with self._lock:
            deadline = time.monotonic() + self.timeout
            try:
                if self._proc is None or self._proc.poll() is not None:
                    self._stop()
                    self._proc = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                                  stderr=subprocess.DEVNULL, bufsize=0,
                                                  start_new_session=(os.name == "posix"))
                    self._queue = queue.Queue(maxsize=32)
                    self._reader = threading.Thread(target=self._pump, args=(self._proc, self._queue), daemon=True)
                    self._reader.start()
                    self._request("initialize", deadline, {"clientInfo": {"name": "quotile", "title": "Quotile", "version": VERSION}})
                    self._send({"method": "initialized", "params": {}})
                return self._request("account/rateLimits/read", deadline)
            except UpstreamError:
                self._stop()
                raise
            except (OSError, ValueError):
                self._stop()
                raise UpstreamError() from None

    def _stop(self):
        proc, reader = self._proc, self._reader
        self._proc = self._reader = self._queue = None
        if proc is None:
            return
        try:
            if proc.poll() is None:
                if os.name == "posix":
                    os.killpg(proc.pid, signal.SIGTERM)
                else:
                    proc.terminate()
                try:
                    proc.wait(timeout=0.3)
                except subprocess.TimeoutExpired:
                    if os.name == "posix":
                        os.killpg(proc.pid, signal.SIGKILL)
                    else:
                        proc.kill()
                    proc.wait(timeout=0.3)
        except (OSError, subprocess.TimeoutExpired):
            pass
        for stream in (proc.stdin, proc.stdout):
            try:
                stream.close()
            except (OSError, ValueError):
                pass
        if reader is not None:
            reader.join(timeout=0.2)

    def close(self):
        with self._lock:
            self._stop()


class QuotaCache:
    def __init__(self, upstream, limit_id="codex", ttl=30, clock=time.monotonic, wall_clock=time.time):
        self.upstream, self.limit_id, self.ttl = upstream, limit_id, ttl
        self.clock, self.wall_clock = clock, wall_clock
        self._lock = threading.Lock()
        self._expires = -float("inf")
        self._snapshot = empty_snapshot()

    def get(self):
        # Serializes only one upstream fetch; all concurrent callers reuse its result.
        with self._lock:
            if self.clock() >= self._expires:
                try:
                    self._snapshot = normalize(self.upstream.read(), self.limit_id, self.wall_clock())
                except UpstreamError as failure:
                    self._snapshot = {**self._snapshot, "stale": True, "error": failure.code}
                except Exception:
                    self._snapshot = {**self._snapshot, "stale": True, "error": "upstream_unavailable"}
                self._expires = self.clock() + self.ttl
            out = copy.deepcopy(self._snapshot)
            if any(out[key] and out[key]["resetsAt"] <= self.wall_clock() for key in ("weekly", "fiveHour")):
                out["stale"] = True
            return out


class RateLimiter:
    """Bounded per-peer token buckets; forwarded headers never override peer identity."""
    def __init__(self, burst=30, per_second=1, max_peers=1024, clock=time.monotonic):
        self.burst, self.rate, self.max_peers, self.clock = burst, per_second, max_peers, clock
        self._peers = collections.OrderedDict()
        self._lock = threading.Lock()

    def allow(self, peer):
        with self._lock:
            now = self.clock()
            tokens, last = self._peers.pop(peer, (self.burst, now))
            tokens = min(self.burst, tokens + max(0, now - last) * self.rate)
            allowed = tokens >= 1
            self._peers[peer] = (tokens - 1 if allowed else tokens, now)
            while len(self._peers) > self.max_peers:
                self._peers.popitem(last=False)
            return allowed


class QuotaHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32
    allow_reuse_address = True

    def __init__(self, address, cache, token, tls_context=None, max_clients=16, limiter=None):
        if ":" in address[0]:
            self.address_family = socket.AF_INET6
        self.cache, self.token, self.tls_context = cache, token.encode("ascii"), tls_context
        self.limiter = limiter or RateLimiter()
        self._slots = threading.BoundedSemaphore(max_clients)
        super().__init__(address, QuotaHandler)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(8)
            if self.tls_context is not None:
                request = self.tls_context.wrap_socket(request, server_side=True)
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError):
            self.shutdown_request(request)
        finally:
            self._slots.release()

    def handle_error(self, request, client_address):
        # Do not log headers, credentials, URLs, or upstream exception text.
        pass


class QuotaHandler(BaseHTTPRequestHandler):
    server_version = "Quotile"
    sys_version = ""
    protocol_version = "HTTP/1.0"

    def log_message(self, format, *args):
        pass

    def send_error(self, code, message=None, explain=None):
        self._json(code, {"error": "invalid_request"})

    def _json(self, status, payload):
        body = json.dumps(payload, separators=(",", ":"), allow_nan=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        if status == 401:
            self.send_header("WWW-Authenticate", 'Bearer realm="Quotile"')
        if status == 429:
            self.send_header("Retry-After", "5")
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        if not self.server.limiter.allow(self.client_address[0]):
            self._json(429, {"error": "rate_limited"})
            return
        if self.path == "/healthz":
            self._json(200, {"ok": True})
            return
        if self.path != "/v1/quota":
            self._json(404, {"error": "not_found"})
            return
        values = self.headers.get_all("Authorization", [])
        header = values[0] if len(values) == 1 else ""
        scheme, separator, supplied = header.partition(" ")
        try:
            candidate = supplied.encode("ascii")
        except UnicodeError:
            candidate = b""
        valid = hmac.compare_digest(candidate, self.server.token)
        if scheme.lower() != "bearer" or not separator or not valid:
            self._json(401, {"error": "unauthorized"})
            return
        self._json(200, self.server.cache.get())


DEFAULT_CONFIG = Path.home() / ".config" / "quotile" / "config.json"


def init_config(path):
    path = Path(path).expanduser()
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump({"token": secrets.token_urlsafe(32), "listen": "127.0.0.1", "port": 8765,
                   "limit_id": "codex", "codex_binary": "codex", "rpc_timeout": 4.0,
                   "tls_cert": None, "tls_key": None}, handle, indent=2)
        handle.write("\n")
    return path.resolve()


def load_config(path):
    path = Path(path).expanduser()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "r", encoding="utf-8") as handle:
        meta = os.fstat(handle.fileno())
        if not stat.S_ISREG(meta.st_mode) or meta.st_size > 16384:
            raise ValueError("配置文件无效")
        if os.name == "posix" and (stat.S_IMODE(meta.st_mode) & 0o077 or meta.st_uid != os.getuid()):
            raise ValueError("配置文件必须由当前用户拥有，且权限为 600")
        config = json.load(handle)
    if not isinstance(config, dict):
        raise ValueError("配置文件无效")
    token = config.get("token")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
        raise ValueError("配对码格式无效，请重新初始化配置")
    host = config.get("listen", "127.0.0.1")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        raise ValueError("listen 必须是明确的 IPv4 或 IPv6 地址") from None
    cert, key = config.get("tls_cert"), config.get("tls_key")
    if bool(cert) != bool(key) or (not address.is_loopback and not (cert and key)):
        raise ValueError("非本机监听必须配置 tls_cert 和 tls_key；反向代理请使用 127.0.0.1")
    port = config.get("port", 8765)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("端口无效")
    timeout = config.get("rpc_timeout", 4.0)
    if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0.1 <= timeout <= 5.0:
        raise ValueError("rpc_timeout 应为 0.1 到 5 秒")
    limit_id = config.get("limit_id", "codex")
    binary = config.get("codex_binary", "codex")
    if not isinstance(limit_id, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", limit_id):
        raise ValueError("limit_id 无效")
    if not isinstance(binary, str) or not binary or len(binary) > 4096 or "\0" in binary:
        raise ValueError("codex_binary 无效")
    return {**config, "listen": host, "port": port, "rpc_timeout": timeout,
            "limit_id": limit_id, "codex_binary": binary}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Quotile 只读额度桥接服务")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="配置文件路径")
    parser.add_argument("--init", action="store_true", help="创建私有配置；只输出路径，不输出配对码")
    args = parser.parse_args(argv)
    rpc = server = None
    try:
        if args.init:
            print(init_config(args.config))
            return 0
        config = load_config(args.config)
        tls_context = None
        if config.get("tls_cert"):
            tls_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls_context.minimum_version = ssl.TLSVersion.TLSv1_2
            tls_context.load_cert_chain(config["tls_cert"], config["tls_key"])
        rpc = CodexRPC([config["codex_binary"], "app-server"], config["rpc_timeout"])
        cache = QuotaCache(rpc, config["limit_id"])
        server = QuotaHTTPServer((config["listen"], config["port"]), cache, config["token"], tls_context)
        def stop(signum, frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, stop)
        print("Quotile 桥接服务已启动。", flush=True)
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    except FileExistsError:
        print("配置已存在，未覆盖。", file=sys.stderr)
        return 2
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        print("启动失败：请检查配置路径、文件权限、Codex 路径和 TLS 证书。", file=sys.stderr)
        return 2
    finally:
        if server:
            server.server_close()
        if rpc:
            rpc.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
