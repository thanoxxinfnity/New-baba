#!/usr/bin/env python3
"""
Trellis build server — lets the Android app drive a real Android SDK build.

The phone cannot compile an APK (Android's W^X rule blocks executing binaries
written at runtime, and there is no JDK/aapt2/d8 on device). This tiny server
runs on a machine that *does* have the SDK, so the app can send code, run a
real Gradle build, and download a genuine .apk.

Run it:
    python3 trellis_build_server.py --port 8000
    ngrok http 8000

Then paste the ngrok https URL into the app: Settings -> Build server URL.

Endpoints
    GET  /health              capability probe
    POST /exec                {"cmd": "..."}  run one shell command
    POST /build               {"files": {"path": "contents", ...}} start a build
    GET  /build/<job>         build state + log + artifact
    GET  /artifacts           list built files
    GET  /artifacts/<name>    download a built file

Security: binds to 127.0.0.1 by default and requires a token when one is set.
Set TRELLIS_TOKEN in the environment and put the same value in the app.
"""

import argparse
import json
import os
import shutil
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(os.environ.get("TRELLIS_WORKDIR", Path.home() / "trellis-builds")).resolve()
ARTIFACTS = ROOT / "artifacts"
PROJECTS = ROOT / "projects"
TOKEN = os.environ.get("TRELLIS_TOKEN", "").strip()
EXEC_TIMEOUT = int(os.environ.get("TRELLIS_EXEC_TIMEOUT", "300"))
BUILD_TIMEOUT = int(os.environ.get("TRELLIS_BUILD_TIMEOUT", "1800"))

JOBS = {}
JOBS_LOCK = threading.Lock()


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def which(name):
    return shutil.which(name)


def android_home():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(var)
        if v and Path(v).is_dir():
            return v
    for guess in (Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk",
                  Path("/usr/lib/android-sdk"), Path("/opt/android-sdk")):
        if guess.is_dir():
            return str(guess)
    return None


def capabilities():
    sdk = android_home()
    gradle = which("gradle")
    gradle_version = ""
    if gradle:
        try:
            out = subprocess.run([gradle, "--version"], capture_output=True, text=True, timeout=60).stdout
            for line in out.splitlines():
                if line.startswith("Gradle "):
                    gradle_version = line.split()[1]
                    break
        except Exception:
            pass
    return {
        "ok": True,
        "sdk": bool(sdk),
        "sdkPath": sdk or "",
        "java": bool(which("java")),
        "gradle": gradle_version or ("present" if gradle else ""),
        "canBuildApk": bool(sdk and which("java")),
        "workdir": str(ROOT),
    }


def safe_join(base: Path, relative: str) -> Path:
    """Blocks path traversal — a build must stay inside its own directory."""
    target = (base / relative).resolve()
    if base.resolve() not in target.parents and target != base.resolve():
        raise ValueError(f"unsafe path: {relative}")
    return target


def run_build(job_id: str, files: dict):
    """Writes the project, runs Gradle, records the resulting APK."""
    project = PROJECTS / job_id
    project.mkdir(parents=True, exist_ok=True)

    def log(msg):
        with JOBS_LOCK:
            JOBS[job_id]["log"] += msg

    try:
        for rel, content in files.items():
            path = safe_join(project, rel)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        log(f"Wrote {len(files)} files to {project}\n")

        wrapper = project / "gradlew"
        if wrapper.exists():
            wrapper.chmod(0o755)
            cmd = ["./gradlew", "assembleDebug", "--no-daemon", "--stacktrace"]
        elif which("gradle"):
            cmd = ["gradle", "assembleDebug", "--no-daemon", "--stacktrace"]
        else:
            raise RuntimeError("No gradlew in the project and no gradle on PATH.")

        env = dict(os.environ)
        sdk = android_home()
        if sdk:
            env.setdefault("ANDROID_HOME", sdk)
            env.setdefault("ANDROID_SDK_ROOT", sdk)
            (project / "local.properties").write_text(f"sdk.dir={sdk}\n")

        log(f"$ {' '.join(cmd)}\n")
        proc = subprocess.Popen(
            cmd, cwd=project, env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1,
        )
        start = time.time()
        for line in proc.stdout:
            log(line)
            if time.time() - start > BUILD_TIMEOUT:
                proc.kill()
                raise RuntimeError("Build exceeded the time limit.")
        code = proc.wait()

        apks = sorted(project.rglob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
        if code != 0 or not apks:
            raise RuntimeError(f"Gradle exited with {code} and produced no APK.")

        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        final = ARTIFACTS / f"{job_id[:8]}-{apks[0].name}"
        shutil.copy2(apks[0], final)
        log(f"\nAPK ready: {final.name} ({final.stat().st_size} bytes)\n")

        with JOBS_LOCK:
            JOBS[job_id].update(state="done", artifact=final.name, size=final.stat().st_size)

    except Exception as exc:                                  # noqa: BLE001
        log(f"\nERROR: {exc}\n")
        with JOBS_LOCK:
            JOBS[job_id].update(state="failed", error=str(exc))


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #

class Handler(BaseHTTPRequestHandler):
    server_version = "TrellisBuild/1.0"

    def log_message(self, fmt, *args):
        print(f"[{time.strftime('%H:%M:%S')}] {fmt % args}")

    # -- plumbing ---------------------------------------------------------- #

    def _send(self, code, payload=None, raw=None, content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        body = raw if raw is not None else json.dumps(payload).encode()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self):
        if not TOKEN:
            return True
        supplied = self.headers.get("X-Trellis-Token", "")
        return supplied == TOKEN

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        if not length:
            return {}
        return json.loads(self.rfile.read(length) or b"{}")

    def do_OPTIONS(self):                                     # noqa: N802
        self._send(204, raw=b"")

    # -- routes ------------------------------------------------------------ #

    def do_GET(self):                                         # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            return self._send(200, capabilities())

        if not self._authorised():
            return self._send(401, {"error": "bad token"})

        if path == "/artifacts":
            ARTIFACTS.mkdir(parents=True, exist_ok=True)
            items = [
                {"name": f.name, "size": f.stat().st_size, "url": f"/artifacts/{f.name}"}
                for f in sorted(ARTIFACTS.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True)
                if f.is_file()
            ]
            return self._send(200, items)

        if path.startswith("/artifacts/"):
            name = unquote(path.split("/artifacts/", 1)[1])
            try:
                target = safe_join(ARTIFACTS, name)
            except ValueError:
                return self._send(400, {"error": "bad name"})
            if not target.is_file():
                return self._send(404, {"error": "not found"})
            return self._send(200, raw=target.read_bytes(),
                              content_type="application/vnd.android.package-archive")

        if path.startswith("/build/"):
            job = path.split("/build/", 1)[1]
            with JOBS_LOCK:
                data = JOBS.get(job)
            if not data:
                return self._send(404, {"error": "unknown job"})
            return self._send(200, data)

        return self._send(404, {"error": "no such endpoint"})

    def do_POST(self):                                        # noqa: N802
        path = urlparse(self.path).path
        if not self._authorised():
            return self._send(401, {"error": "bad token"})

        try:
            body = self._body()
        except Exception:                                     # noqa: BLE001
            return self._send(400, {"error": "invalid JSON"})

        if path == "/exec":
            cmd = (body.get("cmd") or "").strip()
            if not cmd:
                return self._send(400, {"error": "empty command"})
            ROOT.mkdir(parents=True, exist_ok=True)
            try:
                proc = subprocess.run(
                    cmd, shell=True, cwd=ROOT, capture_output=True,
                    text=True, timeout=EXEC_TIMEOUT,
                )
                out = (proc.stdout or "") + (proc.stderr or "")
                return self._send(200, {"output": out[-60000:], "exitCode": proc.returncode})
            except subprocess.TimeoutExpired:
                return self._send(200, {"output": f"timed out after {EXEC_TIMEOUT}s", "exitCode": 124})

        if path == "/build":
            files = body.get("files") or {}
            if not isinstance(files, dict) or not files:
                return self._send(400, {"error": "no files"})
            job_id = uuid.uuid4().hex
            with JOBS_LOCK:
                JOBS[job_id] = {"state": "running", "log": "", "artifact": None, "size": 0}
            threading.Thread(target=run_build, args=(job_id, files), daemon=True).start()
            return self._send(200, {"jobId": job_id})

        return self._send(404, {"error": "no such endpoint"})


def main():
    parser = argparse.ArgumentParser(description="Trellis build server")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--host", default="127.0.0.1",
                        help="use 0.0.0.0 only on a trusted network; prefer ngrok")
    args = parser.parse_args()

    ROOT.mkdir(parents=True, exist_ok=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    PROJECTS.mkdir(parents=True, exist_ok=True)

    caps = capabilities()
    print("Trellis build server")
    print(f"  workdir : {ROOT}")
    print(f"  java    : {'yes' if caps['java'] else 'NO — install a JDK'}")
    print(f"  sdk     : {caps['sdkPath'] or 'NOT FOUND — set ANDROID_HOME'}")
    print(f"  gradle  : {caps['gradle'] or 'not on PATH (project gradlew still works)'}")
    print(f"  apk     : {'can build' if caps['canBuildApk'] else 'CANNOT build until java+sdk exist'}")
    print(f"  token   : {'required' if TOKEN else 'none (set TRELLIS_TOKEN to require one)'}")
    print(f"\nListening on http://{args.host}:{args.port}")
    print(f"Expose it with:  ngrok http {args.port}\n")

    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
