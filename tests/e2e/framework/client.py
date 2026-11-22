"""Pure opaque-box REST HTTP client for ELEPAY Core using Python 3 standard library."""
from dataclasses import dataclass, field
import json
import time
from typing import Any, Dict, List, Optional, Union
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass
class ApiResponse:
    status: int
    body: Any
    headers: Dict[str, str] = field(default_factory=dict)
    raw: bytes = b""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


class OpaqueApiClient:
    """HTTP client communicating with ELEPAY Core strictly over REST without internal classpath coupling."""

    def __init__(self, base_url: str, default_timeout: float = 20.0):
        self.base_url = base_url.rstrip("/")
        self.default_timeout = default_timeout
        self._token_cache: Dict[str, str] = {}

    def mint_token(self, subject: str, roles: Optional[List[str]] = None) -> str:
        """Mint a development bearer JWT via /dev/token with in-memory caching."""
        role_list = sorted(roles or [])
        cache_key = f"{subject}:{'|'.join(role_list)}"
        if cache_key in self._token_cache:
            return self._token_cache[cache_key]

        payload = {"subject": subject, "roles": role_list}
        res = self.call("POST", "/dev/token", body=payload, expected_status=200)
        token = res.body.get("accessToken") if isinstance(res.body, dict) else None
        if not token:
            raise RuntimeError(f"Failed to obtain dev token for {subject}: {res.body}")
        self._token_cache[cache_key] = token
        return token

    def check_health(self) -> bool:
        """Verify server reachability by hitting ledger integrity or actuator endpoint."""
        try:
            req = Request(f"{self.base_url}/v1/ops/ledger/integrity", method="GET")
            with urlopen(req, timeout=3.0) as resp:
                return resp.status in (200, 401, 403)
        except HTTPError as e:
            return e.code in (200, 401, 403)
        except Exception:
            return False

    def call(
        self,
        method: str,
        path: str,
        token: Optional[str] = None,
        body: Any = None,
        idempotency_key: Optional[str] = None,
        headers: Optional[Dict[str, str]] = None,
        expected_status: Optional[Union[int, List[int]]] = None,
        timeout: Optional[float] = None,
    ) -> ApiResponse:
        """Execute an HTTP request against the server and parse JSON response."""
        full_url = f"{self.base_url}{path}"
        req_headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if token:
            req_headers["Authorization"] = f"Bearer {token}"
        if idempotency_key:
            req_headers["Idempotency-Key"] = idempotency_key
        if headers:
            req_headers.update(headers)

        if body is not None:
            if isinstance(body, (dict, list)):
                data = json.dumps(body).encode("utf-8")
            elif isinstance(body, str):
                data = body.encode("utf-8")
            elif isinstance(body, bytes):
                data = body
            else:
                data = json.dumps(body).encode("utf-8")
        else:
            data = None

        request = Request(full_url, data=data, headers=req_headers, method=method)
        req_timeout = timeout or self.default_timeout

        try:
            with urlopen(request, timeout=req_timeout) as response:
                status = response.status
                raw = response.read()
                resp_headers = dict(response.headers.items())
        except HTTPError as error:
            status = error.code
            raw = error.read()
            resp_headers = dict(error.headers.items())
        except URLError as conn_error:
            raise ConnectionError(
                f"Failed to connect to ELEPAY Core at {full_url}: {conn_error}"
            ) from conn_error

        # Parse JSON payload if possible
        if raw:
            try:
                parsed_body = json.loads(raw.decode("utf-8"))
            except Exception:
                try:
                    parsed_body = raw.decode("utf-8")
                except UnicodeDecodeError:
                    parsed_body = raw
        else:
            parsed_body = None

        api_response = ApiResponse(
            status=status, body=parsed_body, headers=resp_headers, raw=raw
        )

        if expected_status is not None:
            expected_list = [expected_status] if isinstance(expected_status, int) else expected_status
            if status not in expected_list:
                raise AssertionError(
                    f"{method} {path} expected HTTP {expected_list}, got {status}: {parsed_body}"
                )

        return api_response

    def get(self, path: str, **kwargs: Any) -> ApiResponse:
        return self.call("GET", path, **kwargs)

    def post(self, path: str, **kwargs: Any) -> ApiResponse:
        return self.call("POST", path, **kwargs)

    def put(self, path: str, **kwargs: Any) -> ApiResponse:
        return self.call("PUT", path, **kwargs)

    def delete(self, path: str, **kwargs: Any) -> ApiResponse:
        return self.call("DELETE", path, **kwargs)
