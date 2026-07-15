"""API v1 契約層測試（Android 遷移 Phase 1）。

驗證五個合同不變量：
1. 舊 /api/* 響應逐字節格式不變（無信封字段）；
2. /api/v1/* 統一信封：request_id / api_version / data / error / meta；
3. 錯誤映射固定錯誤碼（UNAUTHENTICATED / POLICY_DENIED / NOT_FOUND /
   INVALID_ARGUMENT），角色裁定語義與舊路徑完全一致；
4. /api/v1/domains 領域清單如實反映插件狀態（active 可執行，planned 空能力）；
5. /api/v1/content/manifest 指紋確定性 + 內容包 zip 可下載可校驗。
"""
import hashlib
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from hermes_shanghan import config
from hermes_shanghan.server import http_server, policy
from hermes_shanghan.server.service import ServiceContext
from hermes_shanghan.server import v1


def _ensure():
    if not (config.RULES_INITIAL_DIR / "initial_rules.jsonl").exists():
        from hermes_shanghan.orchestrator import run_pipeline
        run_pipeline(verbose=False)


class TestEnvelopeUnit(unittest.TestCase):
    def test_rewrite_path(self):
        self.assertEqual(v1.rewrite_path("/api/v1/search"),
                         (True, "/api/search"))
        self.assertEqual(v1.rewrite_path("/api/v1/runs/abc/spans"),
                         (True, "/api/runs/abc/spans"))
        self.assertEqual(v1.rewrite_path("/api/search"),
                         (False, "/api/search"))
        self.assertEqual(v1.rewrite_path("/livez"), (False, "/livez"))

    def test_success_envelope(self):
        env = v1.envelope(200, {"hits": []}, request_id="req1",
                          meta={"backend": "local"})
        self.assertEqual(env["api_version"], "v1")
        self.assertEqual(env["request_id"], "req1")
        self.assertEqual(env["data"], {"hits": []})
        self.assertIsNone(env["error"])
        self.assertEqual(env["meta"]["backend"], "local")
        self.assertIn("generated_at", env["meta"])

    def test_error_envelope_keeps_details(self):
        env = v1.envelope(403, {"error": "policy_denied",
                                "required_role": "student",
                                "your_ceiling": "patient"})
        self.assertIsNone(env["data"])
        self.assertEqual(env["error"]["code"], "POLICY_DENIED")
        self.assertFalse(env["error"]["retryable"])
        self.assertEqual(env["error"]["details"]["required_role"], "student")

    def test_retryable_codes(self):
        self.assertTrue(v1.envelope(429, {"error": "rate limited"})
                        ["error"]["retryable"])
        self.assertTrue(v1.envelope(503, {"error": "not ready"})
                        ["error"]["retryable"])
        self.assertFalse(v1.envelope(500, {"error": "X"})
                         ["error"]["retryable"])


class TestV1HTTP(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _ensure()
        svc = ServiceContext()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0),
                                        http_server.make_handler(svc))
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever,
                                      daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)

    def _req(self, method, path, body=None, token=""):
        url = f"http://127.0.0.1:{self.port}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=data, headers=headers,
                                     method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    # -- 1. 舊接口凍結：無信封 ------------------------------------------
    def test_legacy_untouched(self):
        code, out = self._req("GET", "/api/health")
        self.assertEqual(code, 200)
        self.assertTrue(out["ok"])
        self.assertNotIn("api_version", out)
        self.assertNotIn("data", out)

    # -- 2. v1 信封 ------------------------------------------------------
    def test_v1_health_envelope(self):
        code, out = self._req("GET", "/api/v1/health")
        self.assertEqual(code, 200)
        self.assertEqual(out["api_version"], "v1")
        self.assertTrue(out["data"]["ok"])
        self.assertIsNone(out["error"])
        self.assertTrue(out["request_id"])
        self.assertEqual(out["meta"]["role_ceiling"], "doctor")  # 匿名默認

    def test_v1_search_parity_with_legacy(self):
        q = {"query": "往來寒熱 胸脅苦滿", "top_k": 5}
        _, legacy = self._req("POST", "/api/search", q)
        _, wrapped = self._req("POST", "/api/v1/search", q)
        self.assertEqual(
            [h["clause_id"] for h in legacy["hits"]],
            [h["clause_id"] for h in wrapped["data"]["hits"]])

    def test_v1_clause_get(self):
        code, out = self._req("GET", "/api/v1/clause/12")
        self.assertEqual(code, 200)
        self.assertEqual(out["data"]["clause_id"], "SHL_SONGBEN_0012")

    # -- 3. 錯誤碼映射 ---------------------------------------------------
    def test_v1_not_found(self):
        code, out = self._req("GET", "/api/v1/nonexistent")
        self.assertEqual(code, 404)
        self.assertEqual(out["error"]["code"], "NOT_FOUND")
        self.assertIsNone(out["data"])

    def test_v1_invalid_json(self):
        url = f"http://127.0.0.1:{self.port}/api/v1/search"
        req = urllib.request.Request(
            url, data=b"{not json", method="POST",
            headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=10)
            self.fail("expected 400")
        except urllib.error.HTTPError as e:
            out = json.loads(e.read())
            self.assertEqual(e.code, 400)
            self.assertEqual(out["error"]["code"], "INVALID_ARGUMENT")

    def test_v1_unauthenticated_and_policy_denied(self):
        keys = policy.parse_api_keys("tokP:patient:pt1,tokS:student:st1")
        with patch.object(http_server, "API_KEYS", keys):
            # 無憑證 → 401 UNAUTHENTICATED
            code, out = self._req("POST", "/api/v1/search", {"query": "x"})
            self.assertEqual(code, 401)
            self.assertEqual(out["error"]["code"], "UNAUTHENTICATED")
            # patient 主體調 student 端點 → 403 POLICY_DENIED（不可提權）
            code, out = self._req("POST", "/api/v1/match",
                                  {"symptoms": ["惡寒"]}, token="tokP")
            self.assertEqual(code, 403)
            self.assertEqual(out["error"]["code"], "POLICY_DENIED")
            self.assertEqual(out["error"]["details"]["required_role"],
                             "student")
            # 偽造 role 字段不提權：patient 請求 doctor 角色 → 403
            code, out = self._req("POST", "/api/v1/search",
                                  {"query": "x", "role": "doctor"},
                                  token="tokP")
            self.assertEqual(code, 403)
            self.assertEqual(out["error"]["code"], "POLICY_DENIED")
            # student 主體正常通過，信封回顯服務端裁定角色
            code, out = self._req("POST", "/api/v1/match",
                                  {"symptoms": ["惡寒", "發熱", "無汗",
                                                "身疼痛"],
                                   "pulse": ["浮緊"]}, token="tokS")
            self.assertEqual(code, 200)
            self.assertEqual(out["meta"]["effective_role"], "student")
            self.assertEqual(
                out["data"]["matched_formula_patterns"][0]["formula"],
                "麻黃湯")

    # -- 4. 領域清單 ------------------------------------------------------
    def test_v1_domains(self):
        code, out = self._req("GET", "/api/v1/domains")
        self.assertEqual(code, 200)
        by_id = {d["domain_id"]: d for d in out["data"]["domains"]}
        self.assertEqual(by_id["shanghan"]["status"], "active")
        self.assertTrue(by_id["shanghan"]["executable"])
        self.assertIn("search", by_id["shanghan"]["capabilities"])
        self.assertIn("A", by_id["shanghan"]["evidence_levels"])
        # planned 插件不偽裝能力
        self.assertEqual(by_id["jingui"]["status"], "planned")
        self.assertEqual(by_id["jingui"]["capabilities"], [])
        self.assertFalse(by_id["jingui"]["executable"])

    # -- 5. 內容包協議 ----------------------------------------------------
    def test_v1_content_manifest_and_package(self):
        code, out = self._req("GET", "/api/v1/content/manifest")
        self.assertEqual(code, 200)
        man = out["data"]
        self.assertTrue(man["corpus_fingerprint"].startswith("sha256:"))
        self.assertEqual(man["content_version"],
                         man["corpus_fingerprint"][7:19])
        pkgs = {p["id"]: p for p in man["packages"]}
        core = pkgs["shanghan-core"]
        self.assertGreater(core["files"], 5)
        self.assertGreater(core["raw_size"], 1_000_000)
        # 指紋確定性：重複請求不變
        _, out2 = self._req("GET", "/api/v1/content/manifest")
        self.assertEqual(out2["data"]["corpus_fingerprint"],
                         man["corpus_fingerprint"])
        # 包下載：zip 魔數 + sha256 與 manifest 一致
        url = (f"http://127.0.0.1:{self.port}"
               f"/api/v1/content/package/shanghan-core")
        with urllib.request.urlopen(url, timeout=60) as r:
            blob = r.read()
            self.assertEqual(r.headers.get("Content-Type"),
                             "application/zip")
        self.assertEqual(blob[:2], b"PK")
        self.assertEqual(hashlib.sha256(blob).hexdigest(), core["sha256"])
        self.assertEqual(len(blob), core["size"])
        # 未知包 → 404 NOT_FOUND
        code, out = self._req("GET", "/api/v1/content/package/nope")
        self.assertEqual(code, 404)
        self.assertEqual(out["error"]["code"], "NOT_FOUND")


if __name__ == "__main__":
    unittest.main()
