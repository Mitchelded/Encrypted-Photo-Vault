#!/usr/bin/env python3
"""EPV USB classifier: accepts only in-memory thumbnails, never original files.

The Android app connects to 127.0.0.1:8765 through:
    adb reverse tcp:8765 tcp:8765
The server binds to PC loopback ONLY. The auth token is unique per run.
Model: GantMan nsfw_model MobileNetV2 TFLite, SHA-256 pinned.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import urlopen

MODEL_SHA256 = "380f98f7685f9d8a386f8cc595b6dfcb972989aae3d1b8b270d3a4a5b96fab40"
MODEL_URLS = (
    "https://media.githubusercontent.com/media/getknit/knit/main/app/src/main/assets/moderation/nsfw.tflite",
    "https://github.com/getknit/knit/raw/refs/heads/main/app/src/main/assets/moderation/nsfw.tflite",
)
MODEL_PATH = Path(__file__).resolve().parent / "nsfw.tflite"
MAX_REQUEST = 8 * 1024 * 1024
MAX_IMAGES = 16
MAX_FRAMES = 3
MAX_FRAME_BYTES = 180_000
state = threading.local()


def ensure_model(path: Path, download: bool = True) -> Path:
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == MODEL_SHA256:
        return path
    if not download:
        raise RuntimeError("Model absent or SHA-256 mismatch: " + str(path))
    if path.exists():
        raise RuntimeError("Model SHA-256 mismatch. Delete this file manually before retrying: " + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    for url in MODEL_URLS:
        tmp = path.with_suffix(".tmp")
        try:
            with urlopen(url, timeout=90) as stream, tmp.open("wb") as output:
                while part := stream.read(256 * 1024):
                    output.write(part)
                    if output.tell() > 25_000_000:
                        raise RuntimeError("Model exceeds expected size")
            if hashlib.sha256(tmp.read_bytes()).hexdigest() != MODEL_SHA256:
                raise RuntimeError("Model failed SHA-256 verification")
            tmp.replace(path)
            return path
        except Exception as exc:
            print("Model mirror unavailable: " + str(exc), file=sys.stderr)
            tmp.unlink(missing_ok=True)
    raise RuntimeError("Unable to download verified model. Provide --model PATH")


def worker_interpreter(path: Path):
    if not hasattr(state, "interpreter"):
        from ai_edge_litert.interpreter import Interpreter

        interpreter = Interpreter(model_path=str(path), num_threads=1)
        interpreter.allocate_tensors()
        inp = interpreter.get_input_details()[0]
        out = interpreter.get_output_details()[0]
        if list(inp["shape"]) != [1, 224, 224, 3] or list(out["shape"]) != [1, 5]:
            raise RuntimeError("Unexpected model tensors; expecting RGB 224x224, 5 classes")
        state.interpreter = interpreter
        state.inp = inp["index"]
        state.out = out["index"]
    return state.interpreter


def classify_one(frames: list[str], model: Path) -> float | None:
    from PIL import Image, ImageOps
    import numpy as np

    if not frames:
        return None
    interpreter = worker_interpreter(model)
    highest: float | None = None
    for encoded in frames:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > MAX_FRAME_BYTES:
            raise ValueError("Thumbnail exceeds 180 KB")
        with Image.open(io.BytesIO(raw)) as opened:
            # Only thumbnails are decoded. Decompression bombs are blocked.
            if opened.width * opened.height > 1024 * 1024:
                raise ValueError("Thumbnail dimensions too large")
            rgb = ImageOps.exif_transpose(opened).convert("RGB").resize((224, 224))
            data = np.asarray(rgb, dtype=np.float32)[None, ...] / np.float32(255.0)
        interpreter.set_tensor(state.inp, data)
        interpreter.invoke()
        v = interpreter.get_tensor(state.out)[0]
        score = float(v[1] + v[3] + v[4])  # hentai, porn, sexy
        if not np.isfinite(score):
            raise ValueError("Non-finite model score")
        highest = max(highest or 0.0, min(1.0, max(0.0, score)))
    return highest


def make_handler(token: str, model: Path, pool: ThreadPoolExecutor):
    class Handler(BaseHTTPRequestHandler):
        server_version = "EPV-USB/1.0"
        sys_version = ""

        def send_json(self, status: int, obj: dict) -> None:
            content = json.dumps(obj, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)

        def do_GET(self):
            if self.path != "/v1/health":
                self.send_json(404, {"error": "Not found"})
                return
            if not secrets.compare_digest(self.headers.get("X-EPV-Token", ""), token):
                self.send_json(403, {"error": "Token invalid"})
                return
            self.send_json(200, {"ok": True, "version": 1})

        def do_POST(self):
            if self.path != "/v1/classify":
                self.send_json(404, {"error": "Not found"})
                return
            if not secrets.compare_digest(self.headers.get("X-EPV-Token", ""), token):
                self.send_json(403, {"error": "Token invalid"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAX_REQUEST:
                    self.send_json(413, {"error": "Request too large"})
                    return
                request = json.loads(self.rfile.read(length))
                items = request["items"]
                if not isinstance(items, list) or not 1 <= len(items) <= MAX_IMAGES:
                    raise ValueError("Provide 1..16 items")
                frames = []
                for item in items:
                    f = item["frames"]
                    if not isinstance(f, list) or len(f) > MAX_FRAMES:
                        raise ValueError("Max 3 thumbnail frames per item")
                    if any(not isinstance(x, str) or len(x) > 250_000 for x in f):
                        raise ValueError("Invalid thumbnail payload")
                    frames.append(f)
                # Each worker owns its own LiteRT Interpreter; never concurrently
                # invoke the same interpreter. results maintain the input order.
                def safe_score(f):
                    try:
                        return classify_one(f, model)
                    except (ValueError, OSError):
                        return None
                scores = list(pool.map(safe_score, frames))
                self.send_json(200, {"scores": scores})
            except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
                self.send_json(400, {"error": str(exc)[:180]})
            except Exception as exc:
                print("Inference error:", repr(exc), file=sys.stderr)
                self.send_json(500, {"error": "Inference failed"})

        def log_message(self, fmt, *args):
            # Never log filenames, image bytes, or paths.
            if self.path == "/v1/health":
                return
            print("EPV request:", self.command, self.path, "%s" % (args[1] if len(args) > 1 else ""))

    return Handler


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="EPV PC classifier over ADB reverse (no disk image copies)")
    parser.add_argument("--workers", type=int, default=4, help="1-8 independent CPU inference workers")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--model", type=Path, default=MODEL_PATH)
    parser.add_argument("--token", help="Optional fixed auth token, 12+ characters")
    args = parser.parse_args(argv)
    if not 1 <= args.workers <= 8:
        parser.error("--workers must be between 1 and 8")
    if args.token is not None and len(args.token) < 12:
        parser.error("--token must be at least 12 characters")

    try:
        model = ensure_model(args.model)
    except Exception as exc:
        print("Model error:", exc, file=sys.stderr)
        return 1
    token = args.token or secrets.token_hex(8)
    print("\nEPV USB/ADB server ready")
    print("Server address: http://127.0.0.1:%d" % args.port)
    print("Code for phone (enter exactly): " + token)
    print("CPU workers: " + str(args.workers))
    print("Run in another Windows terminal: adb reverse tcp:%d tcp:%d" % (args.port, args.port))
    print("Nothing is saved from your photos. Only the ML model is saved.", flush=True)
    with ThreadPoolExecutor(max_workers=args.workers, thread_name_prefix="EPV-ML") as pool:
        httpd = ThreadingHTTPServer(("127.0.0.1", args.port),
                                   make_handler(token, model, pool))
        try:
            httpd.serve_forever(poll_interval=0.3)
        except KeyboardInterrupt:
            pass
        finally:
            httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
