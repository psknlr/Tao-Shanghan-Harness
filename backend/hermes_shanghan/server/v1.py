"""API v1 契約層（Android/原生客戶端遷移 Phase 1）。

設計約束（見 docs/ANDROID.md）：
- **不改業務邏輯**：v1 只是傳輸層合同——路徑版本化 + 統一響應信封 +
  固定錯誤碼；所有業務結果仍由 ServiceContext 產生、仍走 policy.py 的
  角色裁定與患者投影。
- **路徑映射**：``/api/v1/<rest>`` 直接映射到既有 ``/api/<rest>`` 路由表；
  不重命名端點（重命名只會製造雙份維護，對客戶端無收益）。
- **信封只裝真話**：信封層不偽造 evidence/safety 狀態——證據核驗結果在
  各業務端點的 data 內部（citation_report / evidence_clause_ids 等），
  傳輸層無從「驗證」，故不在信封層編造 ``evidence.status`` 一類字段。
  信封 meta 只回傳輸層可信事實：生效角色、角色上限、LLM 後端、時間戳。
- 舊 ``/api/*`` 響應**逐字節不變**（Web 控制台/MCP/CLI 不受影響）。

新增領域清單與內容包端點（domains / content manifest / package）註冊在
http_server.py 的路由表上，v1 與舊路徑均可訪問。
"""
from __future__ import annotations

import hashlib
import io
import json
import threading
import time
import uuid
import zipfile
from typing import Any, Dict, List, Optional, Tuple

from .. import config
from .. import domains as domains_mod

API_VERSION = "v1"
PREFIX = "/api/v1"

# 固定錯誤碼表：客戶端只依賴這些碼做分支，不解析中文 message。
# （RUN_REVIEW_REQUIRED / RUN_FAILED_CLOSED / SAFETY_BLOCKED 等業務態
#   不是傳輸層錯誤——它們以 200 + data.status 形式返回，客戶端按
#   data 內容渲染；只有真正的 HTTP 失敗才進 error 信封。）
ERROR_CODE_BY_STATUS = {
    400: "INVALID_ARGUMENT",
    401: "UNAUTHENTICATED",
    403: "POLICY_DENIED",
    404: "NOT_FOUND",
    413: "INVALID_ARGUMENT",
    429: "RATE_LIMITED",
    500: "INTERNAL_ERROR",
    503: "NOT_READY",
}
RETRYABLE_CODES = frozenset({"RATE_LIMITED", "NOT_READY"})


def rewrite_path(path: str) -> Tuple[bool, str]:
    """``/api/v1/x`` → ``(True, "/api/x")``；其餘原樣返回 ``(False, path)``。"""
    if path == PREFIX:
        return True, "/api"
    if path.startswith(PREFIX + "/"):
        return True, "/api" + path[len(PREFIX):]
    return False, path


def _utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def envelope(status: int, payload: Any, request_id: str = "",
             meta: Optional[Dict] = None) -> Dict:
    """把任意舊格式響應包進 v1 信封。

    成功（<400）：payload → data，error=null。
    失敗（≥400）：payload 中的 ``error`` 字符串成為 message，狀態碼映射
    固定錯誤碼；payload 其餘字段（required_role / trace_id / hint …）
    進 error.details，不丟信息。
    """
    env: Dict[str, Any] = {
        "request_id": request_id or uuid.uuid4().hex[:12],
        "api_version": API_VERSION,
        "data": None,
        "error": None,
        "meta": dict(meta or {}),
    }
    env["meta"].setdefault("generated_at", _utcnow())
    if status < 400:
        env["data"] = payload
        return env
    code = ERROR_CODE_BY_STATUS.get(status, "INTERNAL_ERROR")
    message, details = code, {}
    if isinstance(payload, dict):
        message = str(payload.get("error") or code)
        details = {k: v for k, v in payload.items() if k != "error"}
    elif payload:
        message = str(payload)
    err: Dict[str, Any] = {"code": code, "message": message,
                           "retryable": code in RETRYABLE_CODES}
    if details:
        err["details"] = details
    env["error"] = err
    return env


# ---------------------------------------------------------------------------
# GET /api/v1/domains — 領域清單（Android 動態掛載模塊的靜態依據）
# ---------------------------------------------------------------------------
# 能力清單按「端點實際存在」列出，不是願望字段；planned 插件能力為空。
_DOMAIN_CAPABILITIES: Dict[str, List[str]] = {
    "shanghan": ["search", "clause", "formula", "formula_match",
                 "differential", "six_channel_teaching", "mistreatment",
                 "teaching_case", "quiz", "trace", "intake", "adjudicate",
                 "agent", "council", "chat", "runs"],
    "classics": ["library_search", "library_read", "passage_read",
                 "mentions", "term_passages", "citation_trace"],
}
_DOMAIN_EVIDENCE_LEVELS: Dict[str, List[str]] = {
    "shanghan": ["A", "B", "C", "D", "E"],
    "classics": ["P"],
}


def domains_payload() -> Dict:
    out = []
    for d in domains_mod.DOMAINS.values():
        out.append({
            "domain_id": d.domain_id,
            "display_name": d.name,
            "status": d.status,
            "executable": d.executable(),
            "capabilities": _DOMAIN_CAPABILITIES.get(d.domain_id, []),
            "evidence_levels": _DOMAIN_EVIDENCE_LEVELS.get(d.domain_id, []),
            "canonical_books": list(d.canonical_books),
            "tool_prefix": d.tool_prefix,
            "evidence_policy": d.evidence_policy,
            "ui_manifest": dict(d.ui_manifest),
            "notes": d.notes,
        })
    return {"domains": out}


# ---------------------------------------------------------------------------
# GET /api/v1/content/manifest — 離線內容包清單（Android 同步協議）
# GET /api/v1/content/package/<id> — 內容包下載（zip，確定性構建）
# ---------------------------------------------------------------------------
# shanghan-core：條文 + 關係 + 全部規則庫 + 語料 manifest ——
# 即 Android 離線知識層（Room/FTS 或內存索引）的完整輸入。
_PACKAGE_DIRS: Dict[str, List] = {
    "shanghan-core": [
        config.CLAUSE_DIR, config.RELATION_DIR, config.MANIFEST_DIR,
        config.RULES_INITIAL_DIR, config.RULES_FORMULA_DIR,
        config.RULES_SIX_CHANNEL_DIR, config.RULES_DIFFERENTIAL_DIR,
        config.RULES_MISTREATMENT_DIR, config.RULES_THERAPY_DIR,
        config.RULES_MERGED_DIR, config.RULES_VARIANT_DIR,
        config.RULES_COMMENTARY_DIR,
    ],
}
MINIMUM_APP_VERSION = "1.0.0"
SCHEMA_VERSION = 1

_cache_lock = threading.Lock()
_manifest_cache: Optional[Dict] = None
_package_cache: Dict[str, bytes] = {}


def _package_files(package_id: str) -> List[Tuple[str, Any]]:
    """(倉庫相對路徑, Path) 列表，排序固定 → 指紋與 zip 確定性。"""
    files: List[Tuple[str, Any]] = []
    for d in _PACKAGE_DIRS.get(package_id, []):
        if not d.is_dir():
            continue
        for p in sorted(d.rglob("*")):
            if p.is_file():
                files.append((str(p.relative_to(config.DATA_DIR)), p))
    return sorted(files, key=lambda t: t[0])


def _build_package_zip(package_id: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel, p in _package_files(package_id):
            # 固定時間戳：同樣內容 → 同樣 zip 字節 → sha256 可提前寫進 manifest
            info = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, p.read_bytes())
    return buf.getvalue()


def content_manifest(refresh: bool = False) -> Dict:
    """內容包清單。首次調用構建並緩存（讀全部核心文件算哈希 + 打包）；
    corpus_fingerprint 是逐文件 sha256 的有序聚合，內容不變則指紋不變。"""
    global _manifest_cache
    with _cache_lock:
        if _manifest_cache is not None and not refresh:
            return _manifest_cache
        packages = []
        agg = hashlib.sha256()
        for pkg_id in sorted(_PACKAGE_DIRS):
            files = _package_files(pkg_id)
            total = 0
            for rel, p in files:
                digest = hashlib.sha256(p.read_bytes()).hexdigest()
                agg.update(f"{rel}:{digest}\n".encode("utf-8"))
                total += p.stat().st_size
            blob = _package_cache.get(pkg_id)
            if blob is None or refresh:
                blob = _build_package_zip(pkg_id)
                _package_cache[pkg_id] = blob
            packages.append({
                "id": pkg_id,
                "files": len(files),
                "raw_size": total,
                "size": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
                "url": f"/api/v1/content/package/{pkg_id}",
                "required": pkg_id == "shanghan-core",
                "min_role": "student",
            })
        fp = agg.hexdigest()
        _manifest_cache = {
            "schema_version": SCHEMA_VERSION,
            "content_version": fp[:12],
            "corpus_fingerprint": f"sha256:{fp}",
            "minimum_app_version": MINIMUM_APP_VERSION,
            "generated_at": _utcnow(),
            "packages": packages,
        }
        return _manifest_cache


def package_download(package_id: str) -> Optional[Tuple[str, str, bytes]]:
    """(filename, mime, bytes)；未知包返回 None。"""
    if package_id not in _PACKAGE_DIRS:
        return None
    content_manifest()          # 確保緩存已構建（含 zip）
    blob = _package_cache.get(package_id)
    if blob is None:
        return None
    version = content_manifest()["content_version"]
    return (f"{package_id}-{version}.zip", "application/zip", blob)
