#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


RESPONSES = {
    "/health": {"ok": True, "service": "loadtest-fixture"},
    "/api/v1/public/questions": {
        "limit": 20,
        "offset": 0,
        "questions": [{"id": "fixture-question", "question": "Fixture question"}],
    },
    "/api/v1/studies": {
        "limit": 100,
        "offset": 0,
        "studies": [{"id": "fixture-study", "name": "Fixture study"}],
    },
}


class FixtureHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        body = RESPONSES.get(urlparse(self.path).path)
        if body is None:
            self.send_error(404)
            return
        encoded = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18082)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), FixtureHandler).serve_forever()


if __name__ == "__main__":
    main()
