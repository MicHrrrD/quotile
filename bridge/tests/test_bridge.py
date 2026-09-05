import concurrent.futures
import contextlib
import copy
import http.client
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quotile_bridge import (CodexRPC, QuotaCache, QuotaHTTPServer, RateLimiter,
                            UpstreamError, init_config, load_config, main, normalize)

NOW = 1800000000
TOKEN = "offline_test_pairing_token_01234567890123456789"


def payload():
    return {"rateLimitsByLimitId": {"codex": {
        "limitId": "codex", "planType": "pro",
        "primary": {"usedPercent": 12.5, "windowDurationMins": 300, "resetsAt": NOW + 1200},
        "secondary": {"usedPercent": 70, "windowDurationMins": 10080, "resetsAt": NOW + 86400}
    }}}


class FakeUpstream:
    def __init__(self, delay=0):
        self.calls = 0
        self.delay = delay
        self.failure = None

    def read(self):
        self.calls += 1
        if self.delay:
            time.sleep(self.delay)
        if self.failure:
            raise self.failure
        return payload()


class NormalizeTests(unittest.TestCase):
    def test_remaining_and_plan_are_upstream_values(self):
        result = normalize(payload(), now=NOW)
        self.assertEqual(result["fiveHour"]["remainingPercent"], 87.5)
        self.assertEqual(result["weekly"]["remainingPercent"], 30)
        self.assertEqual(result["plan"], "pro")
        self.assertEqual(result["updatedAt"], NOW)
        self.assertIsNone(result["error"])
        self.assertFalse(result["stale"])

    def test_duration_determines_label_not_primary_secondary_position(self):
        source = payload()
        bucket = source["rateLimitsByLimitId"]["codex"]
        bucket["primary"], bucket["secondary"] = bucket["secondary"], bucket["primary"]
        self.assertEqual(normalize(source, now=NOW)["weekly"]["remainingPercent"], 30)

    def test_authoritative_map_never_falls_back_to_wrong_bucket(self):
        source = payload()
        source["rateLimits"] = source["rateLimitsByLimitId"]["codex"]
        source["rateLimitsByLimitId"] = {}
        result = normalize(source, now=NOW)
        self.assertIsNone(result["weekly"])
        self.assertEqual(result["error"], "bucket_unavailable")

    def test_custom_bucket_and_mismatched_identifier(self):
        source = payload()
        source["rateLimitsByLimitId"]["other"] = copy.deepcopy(source["rateLimitsByLimitId"]["codex"])
        source["rateLimitsByLimitId"]["other"]["limitId"] = "other"
        self.assertIsNotNone(normalize(source, "other", NOW)["weekly"])
        source["rateLimitsByLimitId"]["other"]["limitId"] = "wrong"
        self.assertEqual(normalize(source, "other", NOW)["error"], "bucket_unavailable")

    def test_legacy_fallback(self):
        bucket = payload()["rateLimitsByLimitId"]["codex"]
        self.assertIsNotNone(normalize({"rateLimits": bucket}, now=NOW)["weekly"])
        del bucket["limitId"]
        self.assertIsNotNone(normalize({"rateLimits": bucket}, now=NOW)["weekly"])
        self.assertIsNone(normalize({"rateLimits": bucket}, "other", NOW)["weekly"])

    def test_unknown_duration_not_relabelled_or_filled(self):
        source = payload()
        source["rateLimitsByLimitId"]["codex"]["primary"]["windowDurationMins"] = 15
        result = normalize(source, now=NOW)
        self.assertIsNone(result["fiveHour"])
        self.assertIsNotNone(result["weekly"])
        self.assertIsNone(result["error"])

    def test_only_weekly_is_current_valid_data(self):
        source = payload()
        source["rateLimitsByLimitId"]["codex"]["primary"] = None
        result = normalize(source, now=NOW)
        self.assertIsNone(result["fiveHour"])
        self.assertEqual(result["weekly"]["remainingPercent"], 30)
        self.assertFalse(result["stale"])
        self.assertIsNone(result["error"])

    def test_both_unavailable_are_reported_without_guessing(self):
        source = payload()
        bucket = source["rateLimitsByLimitId"]["codex"]
        bucket["primary"] = bucket["secondary"] = None
        result = normalize(source, now=NOW)
        self.assertIsNone(result["weekly"])
        self.assertIsNone(result["fiveHour"])
        self.assertEqual(result["error"], "quota_window_unavailable")

    def test_invalid_percent_and_reset_values_become_null(self):
        for field, invalid in (("usedPercent", True), ("usedPercent", -1), ("usedPercent", 101),
                               ("usedPercent", "25"), ("usedPercent", float("nan")),
                               ("usedPercent", float("inf")), ("resetsAt", NOW * 1000),
                               ("resetsAt", 0), ("resetsAt", -1), ("resetsAt", True),
                               ("resetsAt", float(NOW)), ("resetsAt", str(NOW))):
            with self.subTest(field=field, invalid=invalid):
                source = payload()
                source["rateLimitsByLimitId"]["codex"]["primary"][field] = invalid
                self.assertIsNone(normalize(source, now=NOW)["fiveHour"])

    def test_duplicate_window_is_ambiguous(self):
        source = payload()
        bucket = source["rateLimitsByLimitId"]["codex"]
        bucket["secondary"] = copy.deepcopy(bucket["primary"])
        self.assertIsNone(normalize(source, now=NOW)["fiveHour"])

    def test_absent_values_remain_null(self):
        self.assertEqual(normalize({}, now=NOW)["error"], "bucket_unavailable")
        result = normalize({"rateLimits": {"primary": None, "secondary": None}}, now=NOW)
        self.assertIsNone(result["plan"])
        self.assertIsNone(result["weekly"])
        self.assertIsNone(result["fiveHour"])

    def test_expired_reset_is_stale(self):
        self.assertTrue(normalize(payload(), now=NOW + 1201)["stale"])

    def test_malformed_result_is_failure(self):
        for value in (None, [], "text", {"rateLimitsByLimitId": []}):
            with self.assertRaises(UpstreamError):
                normalize(value, now=NOW)


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.elapsed = 0
        self.now = NOW
        self.upstream = FakeUpstream()
        self.cache = QuotaCache(self.upstream, clock=lambda: self.elapsed, wall_clock=lambda: self.now)

    def test_cache_ttl(self):
        self.cache.get()
        self.elapsed = 29.9
        self.cache.get()
        self.assertEqual(self.upstream.calls, 1)
        self.elapsed = 30
        self.now += 30
        self.assertEqual(self.cache.get()["updatedAt"], NOW + 30)
        self.assertEqual(self.upstream.calls, 2)

    def test_timeout_preserves_entire_last_snapshot_and_timestamp(self):
        first = self.cache.get()
        self.elapsed = 30
        self.now += 30
        self.upstream.failure = UpstreamError("upstream_timeout")
        result = self.cache.get()
        self.assertEqual(result, {**first, "stale": True, "error": "upstream_timeout"})
        self.cache.get()
        self.assertEqual(self.upstream.calls, 2)

    def test_first_failure_is_unknown_not_zero_quota(self):
        self.upstream.failure = UpstreamError()
        result = self.cache.get()
        self.assertEqual(result["updatedAt"], 0)
        self.assertIsNone(result["weekly"])
        self.assertTrue(result["stale"])

    def test_unexpected_exception_does_not_escape_or_expose_detail(self):
        self.upstream.failure = RuntimeError("SECRET_DETAIL")
        result = self.cache.get()
        self.assertEqual(result["error"], "upstream_unavailable")
        self.assertNotIn("SECRET", json.dumps(result))

    def test_concurrent_reads_coalesce(self):
        self.upstream.delay = 0.05
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
            results = list(executor.map(lambda _: self.cache.get(), range(16)))
        self.assertEqual(self.upstream.calls, 1)
        self.assertTrue(all(value == results[0] for value in results))

    def test_callers_cannot_mutate_snapshot(self):
        first = self.cache.get()
        first["weekly"]["remainingPercent"] = -100
        self.assertEqual(self.cache.get()["weekly"]["remainingPercent"], 30)

    def test_recovery_and_window_disappearance_replace_stale_values(self):
        self.cache.get()
        self.elapsed = 30
        self.upstream.failure = UpstreamError()
        self.assertTrue(self.cache.get()["stale"])
        self.elapsed = 60
        self.now += 60
        self.upstream.failure = None
        self.assertFalse(self.cache.get()["stale"])
        self.elapsed = 90
        self.upstream.read = lambda: {"rateLimitsByLimitId": {}}
        self.assertIsNone(self.cache.get()["weekly"])


class RPCTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.events = Path(self.temp.name) / "events.jsonl"

    def rpc(self, mode, timeout=1.5):
        client = CodexRPC([sys.executable, "-u", str(Path(__file__).with_name("mock_rpc.py")), mode, str(self.events)], timeout)
        self.addCleanup(client.close)
        return client

    def test_handshake_and_persistent_process_only_read_quota(self):
        client = self.rpc("ok")
        first = client.read()
        pid = client._proc.pid
        self.assertEqual(client.read(), first)
        self.assertEqual(client._proc.pid, pid)
        methods = [json.loads(line).get("method") for line in self.events.read_text().splitlines()]
        self.assertEqual(methods, ["initialize", "initialized", "account/rateLimits/read", "account/rateLimits/read"])

    def test_timeout_reaps_process_and_next_request_restarts(self):
        client = self.rpc("hang", timeout=0.2)
        start = time.monotonic()
        with self.assertRaises(UpstreamError) as caught:
            client.read()
        self.assertEqual(caught.exception.code, "upstream_timeout")
        self.assertLess(time.monotonic() - start, 1.5)
        self.assertIsNone(client._proc)
        client.command[-2] = "ok"
        client.timeout = 1.5
        self.assertIn("rateLimitsByLimitId", client.read())

    def test_errors_are_sanitized_and_process_cleaned(self):
        for mode in ("error", "initialize_error", "exit", "malformed", "oversized"):
            with self.subTest(mode=mode):
                client = self.rpc(mode)
                with self.assertRaises(UpstreamError) as caught:
                    client.read()
                self.assertEqual(str(caught.exception), "upstream_unavailable")
                self.assertIsNone(client._proc)

    def test_server_credential_callback_is_rejected(self):
        client = self.rpc("callback")
        self.assertIn("rateLimitsByLimitId", client.read())
        events = [json.loads(line) for line in self.events.read_text().splitlines()]
        self.assertEqual(events[-1], {"id": "callback", "error": {"code": -32601, "message": "Unsupported method"}})

    def test_missing_executable_is_sanitized(self):
        client = CodexRPC([str(Path(self.temp.name) / "missing-binary"), "app-server"])
        self.addCleanup(client.close)
        with self.assertRaises(UpstreamError):
            client.read()


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.upstream = FakeUpstream(delay=0.03)
        self.cache = QuotaCache(self.upstream, wall_clock=lambda: NOW)
        self.server = QuotaHTTPServer(("127.0.0.1", 0), self.cache, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)

    def request(self, path="/v1/quota", token=TOKEN, method="GET"):
        client = http.client.HTTPConnection(*self.server.server_address[:2], timeout=3)
        try:
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            client.request(method, path, headers=headers)
            response = client.getresponse()
            return response.status, dict(response.getheaders()), json.loads(response.read())
        finally:
            client.close()

    def test_auth_required_before_upstream(self):
        for token in (None, "wrong", TOKEN + "x"):
            status, _, body = self.request(token=token)
            self.assertEqual(status, 401)
            self.assertEqual(body, {"error": "unauthorized"})
        self.assertEqual(self.upstream.calls, 0)

    def test_health_reveals_no_quota_and_does_not_fetch(self):
        status, _, body = self.request("/healthz", token=None)
        self.assertEqual((status, body), (200, {"ok": True}))
        self.assertEqual(self.upstream.calls, 0)

    def test_success_and_no_store(self):
        status, headers, body = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertTrue(headers["Content-Type"].startswith("application/json"))
        self.assertEqual(body["schemaVersion"], 1)

    def test_concurrent_http_fetches_coalesce(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            results = list(executor.map(lambda _: self.request(), range(10)))
        self.assertTrue(all(result[0] == 200 for result in results))
        self.assertEqual(self.upstream.calls, 1)

    def test_upstream_failure_is_valid_200_snapshot(self):
        self.upstream.failure = UpstreamError("upstream_timeout")
        status, _, result = self.request()
        self.assertEqual(status, 200)
        self.assertTrue(result["stale"])
        self.assertEqual(result["updatedAt"], 0)

    def test_query_tokens_and_unexpected_paths_are_rejected(self):
        self.assertEqual(self.request("/v1/quota?token=" + TOKEN, token=None)[0], 404)
        self.assertEqual(self.request("/other")[0], 404)
        self.assertEqual(self.upstream.calls, 0)

    def test_duplicate_authorization_is_rejected(self):
        client = http.client.HTTPConnection(*self.server.server_address[:2], timeout=3)
        try:
            client.putrequest("GET", "/v1/quota")
            client.putheader("Authorization", "Bearer " + TOKEN)
            client.putheader("Authorization", "Bearer wrong")
            client.endheaders()
            self.assertEqual(client.getresponse().status, 401)
        finally:
            client.close()

    def test_rate_limiting(self):
        self.server.limiter = RateLimiter(burst=1, per_second=0)
        self.assertEqual(self.request("/healthz")[0], 200)
        self.assertEqual(self.request("/healthz")[0], 429)


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "private" / "config.json"
        init_config(self.path)

    def change(self, **values):
        config = json.loads(self.path.read_text())
        config.update(values)
        self.path.write_text(json.dumps(config))

    def test_token_is_random_and_private(self):
        config = load_config(self.path)
        self.assertRegex(config["token"], r"^[A-Za-z0-9_-]{43}$")
        self.assertEqual(config["listen"], "127.0.0.1")
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        other = self.path.parent / "other.json"
        init_config(other)
        self.assertNotEqual(config["token"], load_config(other)["token"])

    def test_init_never_overwrites_and_prints_no_token(self):
        before = self.path.read_text()
        with self.assertRaises(FileExistsError):
            init_config(self.path)
        self.assertEqual(self.path.read_text(), before)
        path = self.path.parent / "cli.json"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(["--init", "--config", str(path)]), 0)
        self.assertEqual(output.getvalue().strip(), str(path))
        self.assertNotIn(load_config(path)["token"], output.getvalue())

    def test_nonloopback_requires_tls(self):
        self.change(listen="0.0.0.0")
        with self.assertRaises(ValueError):
            load_config(self.path)
        self.change(tls_cert="cert.pem", tls_key="key.pem")
        self.assertEqual(load_config(self.path)["listen"], "0.0.0.0")

    def test_ipv6_loopback_and_reject_hostname(self):
        self.change(listen="::1")
        self.assertEqual(load_config(self.path)["listen"], "::1")
        self.change(listen="localhost")
        with self.assertRaises(ValueError):
            load_config(self.path)

    @unittest.skipUnless(os.name == "posix", "POSIX file modes")
    def test_world_readable_config_rejected(self):
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            load_config(self.path)

    @unittest.skipUnless(hasattr(os, "O_NOFOLLOW"), "NOFOLLOW available")
    def test_symlink_config_rejected(self):
        link = self.path.parent / "link.json"
        link.symlink_to(self.path)
        with self.assertRaises(OSError):
            load_config(link)

    def test_invalid_config_values(self):
        original = self.path.read_text()
        for change in ({"port": True}, {"port": 70000}, {"rpc_timeout": 99}, {"rpc_timeout": float("nan")},
                       {"token": "short"}, {"limit_id": ""}, {"tls_cert": "cert.pem"}):
            with self.subTest(change=change):
                self.path.write_text(original)
                self.change(**change)
                with self.assertRaises(ValueError):
                    load_config(self.path)

    def test_limiter_memory_and_refill(self):
        clock = [0]
        limiter = RateLimiter(burst=1, per_second=1, max_peers=2, clock=lambda: clock[0])
        self.assertTrue(limiter.allow("a"))
        self.assertFalse(limiter.allow("a"))
        clock[0] = 1
        self.assertTrue(limiter.allow("a"))
        limiter.allow("b")
        limiter.allow("c")
        self.assertEqual(len(limiter._peers), 2)


if __name__ == "__main__":
    unittest.main()
