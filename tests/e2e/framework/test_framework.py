"""Self-tests verifying E2E test framework components offline without external dependencies."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_minor_unit_integer,
    assert_zero_sum_entries,
    crc16_ccitt,
    sign_fx_partner,
    sign_rail_callback,
    sign_ussd_body,
    sign_webhook,
)
from tests.e2e.framework.client import OpaqueApiClient
from tests.e2e.framework.config import TestConfig
from tests.e2e.framework.fixtures import intl_phone, local_phone, unique_id, unique_phone


class MockElepayHandler(BaseHTTPRequestHandler):
    """Minimal in-memory mock handler for offline framework self-tests."""

    def log_message(self, format, *args):
        pass  # Suppress console logging

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(length) if length > 0 else b"{}"
        body = json.loads(body_bytes.decode("utf-8")) if body_bytes else {}

        if self.path == "/dev/token":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"accessToken": f"mock-token-{body.get('subject')}"}).encode("utf-8"))
        elif self.path == "/v1/test/echo":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"received": body, "idempotency": self.headers.get("Idempotency-Key")}).encode("utf-8"))
        elif self.path == "/v1/test/conflict":
            self.send_response(409)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"code": "conflict", "message": "Resource conflict"}).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        if self.path == "/v1/ops/ledger/integrity":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(
                json.dumps({
                    "accountsWithDrift": 0,
                    "unbalancedTransactions": 0,
                    "netByCurrency": {"GHS": 0, "USD": 0},
                }).encode("utf-8")
            )
        else:
            self.send_response(404)
            self.end_headers()


class FrameworkSelfTest(unittest.TestCase):
    """Test framework components against local in-memory HTTP server."""

    server: HTTPServer
    server_thread: threading.Thread
    port: int

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), MockElepayHandler)
        cls.port = cls.server.server_port
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_client_token_minting_and_calling(self):
        client = OpaqueApiClient(f"http://127.0.0.1:{self.port}")
        token = client.mint_token("test-user", ["ops"])
        self.assertEqual(token, "mock-token-test-user")

        # Test cached token return
        token2 = client.mint_token("test-user", ["ops"])
        self.assertEqual(token, token2)

        # Test POST with Idempotency-Key
        res = client.call(
            "POST",
            "/v1/test/echo",
            token=token,
            body={"key": "value"},
            idempotency_key="key-123",
            expected_status=200,
        )
        self.assertEqual(res.status, 200)
        self.assertEqual(res.body.get("received"), {"key": "value"})
        self.assertEqual(res.body.get("idempotency"), "key-123")

    def test_client_error_handling(self):
        client = OpaqueApiClient(f"http://127.0.0.1:{self.port}")
        res = client.call("POST", "/v1/test/conflict", expected_status=409)
        self.assertEqual(res.status, 409)
        self.assertEqual(res.body.get("code"), "conflict")

    def test_cryptographic_signatures(self):
        ussd_sig = sign_ussd_body("secret", '{"input":"1"}')
        self.assertTrue(len(ussd_sig) == 64)

        rail_sig = sign_rail_callback("rail-secret", '{"status":"SUCCESS"}')
        self.assertTrue(len(rail_sig) == 128)

        fx_sig = sign_fx_partner("fx-secret", '{"reference":"FX1"}')
        self.assertTrue(len(fx_sig) == 128)

        wh_sig = sign_webhook("wh-secret", 1700000000, '{"event":"paid"}')
        self.assertTrue(wh_sig.startswith("t=1700000000,v1="))

    def test_crc16_ccitt(self):
        # EMVCo CRC16 verification
        crc = crc16_ccitt("000201010212")
        self.assertEqual(len(crc), 4)

    def test_fixtures_and_normalization(self):
        ph = unique_phone()
        self.assertTrue(ph.startswith("233"))
        self.assertEqual(len(ph), 12)

        local = local_phone(ph)
        self.assertTrue(local.startswith("0"))
        self.assertEqual(len(local), 10)

        intl = intl_phone(local)
        self.assertTrue(intl.startswith("+233"))

        uid = unique_id("order-")
        self.assertTrue(uid.startswith("order-"))

    def test_assertions(self):
        assert_minor_unit_integer(5000)
        with self.assertRaises(AssertionError):
            assert_minor_unit_integer(50.0)

        legs = [
            {"currency": "GHS", "amount": -1000},
            {"currency": "GHS", "amount": 1000},
        ]
        assert_zero_sum_entries(legs, "GHS")

        with self.assertRaises(AssertionError):
            assert_zero_sum_entries([{"currency": "GHS", "amount": 100}], "GHS")


if __name__ == "__main__":
    unittest.main()
