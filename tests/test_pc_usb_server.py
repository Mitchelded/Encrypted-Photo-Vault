"""Protocol regression tests: server never needs a real model for transport tests."""
import importlib.util
import io
import json
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

path = Path(__file__).resolve().parents[1] / "pc_nsfw" / "epv_server.py"
spec = importlib.util.spec_from_file_location("epv_usb_server", path)
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def test_usb_loopback_authenticated_protocol():
    secret = "private_test_code_123456"
    with ThreadPoolExecutor(max_workers=4) as workers:
        handler = server.make_handler(secret, Path("/does/not/exist"), workers)
        http = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = Thread(target=http.serve_forever, daemon=True)
        thread.start()
        url = "http://127.0.0.1:%s" % http.server_port
        try:
            try:
                urlopen(url + "/v1/health", timeout=2)
                assert False, "Unauthenticated access must be denied"
            except HTTPError as e:
                assert e.code == 403

            health = Request(url + "/v1/health", headers={"X-EPV-Token": secret})
            with urlopen(health, timeout=2) as response:
                assert json.load(response) == {"ok": True, "version": 1}

            data = json.dumps({"items": [
                {"frames": ["ZGF0YQ=="]},
                {"frames": ["Zm9v", "YmFy"]}
            ]}).encode()
            req = Request(url + "/v1/classify", data=data, method="POST",
                          headers={"X-EPV-Token": secret})
            with patch.object(server, "classify_one", side_effect=lambda frames, model: float(len(frames)) / 10):
                with urlopen(req, timeout=2) as response:
                    assert json.load(response) == {"scores": [0.1, 0.2]}

            req = Request(url + "/v1/classify", data=b'{"items":[]}', method="POST",
                          headers={"X-EPV-Token": secret})
            try:
                urlopen(req, timeout=2)
                assert False, "Empty batches must be rejected"
            except HTTPError as e:
                assert e.code == 400
        finally:
            http.shutdown()
            http.server_close()
            thread.join(timeout=2)


def test_model_hash_is_pinned_and_missing_file_is_not_accepted(tmp_path):
    path = tmp_path / "nsfw.tflite"
    path.write_bytes(b"not the vetted model")
    try:
        server.ensure_model(path, download=False)
        assert False, "Invalid model must be rejected"
    except RuntimeError:
        pass
