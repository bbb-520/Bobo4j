import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from collect import ApplicationClient


class CollectorHttpTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.csrf_status = 200
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, value, cookie=None):
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if cookie:
                    self.send_header("Set-Cookie", cookie)
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())

            def do_GET(self):
                fixture.calls.append(("GET", self.path, dict(self.headers), b""))
                if self.path == "/api/auth/csrf":
                    self.reply(fixture.csrf_status, {"token": "masked-token", "headerName": "X-XSRF-TOKEN"},
                               "XSRF-TOKEN=unmasked-cookie; Path=/")
                else:
                    self.reply(200, {"status": "READY"})

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                fixture.calls.append(("POST", self.path, dict(self.headers), body))
                valid = (self.headers.get("X-XSRF-TOKEN") == "masked-token"
                         and "test_session=signed-session" in self.headers.get("Cookie", "")
                         and "XSRF-TOKEN=unmasked-cookie" in self.headers.get("Cookie", ""))
                self.reply(200 if valid else 403, {"accepted": valid})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = ApplicationClient(f"http://127.0.0.1:{self.server.server_port}", "test_session", "signed-session")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_writes_obtain_csrf_and_send_both_session_and_repository_cookies(self):
        self.assertEqual(self.client.request("/api/documents", "POST", {"test": True}), {"accepted": True})
        self.assertEqual(self.client.request("/api/agent-executions", "POST", {"requestId": "stable"}), {"accepted": True})
        self.assertEqual([(c[0], c[1]) for c in self.calls], [
            ("GET", "/api/auth/csrf"), ("POST", "/api/documents"), ("POST", "/api/agent-executions")])
        self.assertEqual(json.loads(self.calls[2][3]), {"requestId": "stable"})

    def test_gets_do_not_trigger_csrf_and_raw_upload_keeps_id_and_content_type(self):
        self.assertEqual(self.client.request("/api/documents/one"), {"status": "READY"})
        self.assertEqual(len(self.calls), 1)
        raw = b"--multipart\r\nimage\r\n"
        self.client.request("/api/documents", "POST", raw=raw,
                            content_type="multipart/form-data; boundary=multipart", headers={"X-Request-ID": "stable"})
        self.assertEqual(self.calls[-1][3], raw)
        self.assertEqual(self.calls[-1][2]["X-Request-Id"], "stable")
        self.assertEqual(self.calls[-1][2]["Content-Type"], "multipart/form-data; boundary=multipart")

    def test_failed_csrf_fetch_never_submits_or_retries_a_billable_write(self):
        self.csrf_status = 503
        with self.assertRaisesRegex(RuntimeError, "HTTP503") as error:
            self.client.request("/api/agent-executions", "POST", {"requestId": "stable"})
        self.assertNotIn("signed-session", str(error.exception))
        self.assertEqual([(c[0], c[1]) for c in self.calls], [("GET", "/api/auth/csrf")])


if __name__ == "__main__":
    unittest.main()
