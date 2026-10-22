#!/usr/bin/env python3
"""Accept the packaged PlanetryItems proxy sync on disposable Paper 26.1.2.

Usage: python3 scripts/sync-smoke.py target/PlanetryItems-VERSION.jar
Requires JAVA_HOME (JDK 25), target/test-classpath.txt, and the shared build guard.
Runs cold/no-proxy and cache-restart phases on the same temporary loopback server.
The test accepts the EULA only for that server and stops only its own process.
Players and the transport request are controlled fixtures; no clients connect.
"""

import argparse
import hashlib
import os
import pathlib
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request
import zipfile


ROOT = pathlib.Path(__file__).resolve().parent.parent
PAPER_SHA = "1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_SHA}/paper-26.1.2-74.jar"
# This disposable fixture key is deliberately unrelated to any server configuration.
FIXTURE_SECRET = "planetry-sync-disposable-fixture-key-2026"
FAIL_MARKER = "ITEMS_SYNC_PROBE_FAILED"


def paper_bytes():
    """Verify cached bytes as well as downloads against the pinned release digest."""
    cache = pathlib.Path(os.environ.get("PLANETRY_SMOKE_CACHE", pathlib.Path.home() / ".cache/planetry-smoke"))
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"paper-{PAPER_SHA}.jar"
    if target.is_file():
        data = target.read_bytes()
        if hashlib.sha256(data).hexdigest() == PAPER_SHA:
            return data
    request = urllib.request.Request(PAPER_URL, headers={
        "User-Agent": "PlanetryItems-sync-smoke/1 (https://github.com/FlintSH/planetry-items)"
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != PAPER_SHA:
        raise RuntimeError("Pinned Paper SHA256 mismatch")
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as handle:
        temporary = pathlib.Path(handle.name)
        handle.write(data)
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    return data


def build_probe(java, classpath, work):
    """Compile only acceptance sources; the release JAR supplies production code."""
    classes = work / "sync-probe-classes"
    classes.mkdir()
    subprocess.run([
        str(java.with_name("javac")), "-cp", classpath, "-d", str(classes),
        *map(str, sorted((ROOT / "scripts/sync-probe").rglob("*.java"))),
    ], check=True, timeout=60)
    with zipfile.ZipFile(work / "plugins/ItemsSyncProbe.jar", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            relative = path.relative_to(classes).as_posix()
            if not relative.startswith("dev/flint/planetry/items/syncprobe/"):
                raise RuntimeError(f"Unexpected acceptance class: {relative}")
            archive.write(path, relative)
        archive.writestr("plugin.yml", (
            "name: ItemsSyncProbe\nversion: 'fixture-1'\n"
            "main: dev.flint.planetry.items.syncprobe.ItemsSyncProbe\n"
            "api-version: '26.1.2'\ndepend: [PlanetryItems]\n"
        ))


def write_local_sentinels(folder, revision):
    """Valid local files must never be used by a proxy-configured backend."""
    (folder / "items").mkdir(exist_ok=True)
    items = ("items:\n  local_only:\n    material: STONE\n"
             f"    display-name: 'LOCAL {revision}'\n    max-durability: 0\n")
    (folder / "items.yml").write_text(items, encoding="utf-8")
    (folder / "items/local.yml").write_text(items, encoding="utf-8")
    (folder / "lang.yml").write_text(
        f"messages:\n  sync-probe: 'LOCAL {revision}'\n", encoding="utf-8")


def run_server(java, work, phase, log_file, timeout):
    groups = {"cold": 18, "restart": 2}[phase]
    marker = f"ITEMS_SYNC_PROBE_OK phase={phase} groups={groups};"
    process = subprocess.Popen(
        [str(java), "-Xms256m", "-Xmx768m", f"-Dplanetry.syncProbe.phase={phase}",
         "-jar", "paper.jar", "--nogui"],
        cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    output = queue.Queue()
    lines = []

    def read_output():
        for line in process.stdout:
            output.put(line)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    try:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                lines.append(output.get(timeout=1))
                if FAIL_MARKER in lines[-1]:
                    raise AssertionError(f"Proxy sync {phase} assertions failed; see server output")
            except queue.Empty:
                if process.poll() is not None:
                    break
        else:
            raise TimeoutError(f"Proxy sync {phase} Paper probe exceeded {timeout} seconds")
        reader.join(timeout=2)
        while not output.empty():
            lines.append(output.get_nowait())
        log = "".join(lines)
        if marker not in log or FAIL_MARKER in log:
            raise AssertionError(f"Missing proxy sync {phase} success marker; see server output")
        if any(error in log for error in (
            "Could not pass event", "generated an exception", "Error occurred while enabling",
            "Error occurred while disabling", "Could not load plugin", "Unknown/missing dependency",
        )):
            raise AssertionError(f"Plugin lifecycle/event error in {phase}; see server output")
        if process.returncode != 0:
            raise AssertionError(f"Paper {phase} exited {process.returncode}")
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=15)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=10)
        reader.join(timeout=2)
        while not output.empty():
            lines.append(output.get_nowait())
        log = "".join(lines)
        print(log, end="", flush=True)
        if log_file:
            log_file.parent.mkdir(parents=True, exist_ok=True)
            log_file.write_text(log, encoding="utf-8")
        if process.stdin:
            process.stdin.close()
        if process.stdout:
            process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plugin", type=pathlib.Path)
    parser.add_argument("--log-file", type=pathlib.Path, help="cold log path; restart adds .restart before its suffix")
    parser.add_argument("--timeout", type=int, default=180, help="maximum seconds per owned Paper process")
    arguments = parser.parse_args()
    if arguments.timeout < 1:
        parser.error("--timeout must be positive")
    java = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java"
    plugin = arguments.plugin.resolve(strict=True)
    entries = (ROOT / "target/test-classpath.txt").read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join([str(plugin), entries])
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        for required in ("plugin.yml", "config.yml", "items.yml", "lang.yml",
                         "dev/flint/planetry/items/sync/ProxyDefinitions.class"):
            if required not in names:
                raise RuntimeError(f"Packaged resource/class is missing: {required}")
        if any("probe/" in name.lower() or "syncprobe/" in name.lower() or "Fixture" in name for name in names):
            raise RuntimeError("Test fixtures leaked into the release JAR")
    paper = paper_bytes()
    with tempfile.TemporaryDirectory(prefix="planetry-items-sync-paper-") as temporary:
        work = pathlib.Path(temporary)
        folder = work / "plugins/PlanetryItems"
        folder.mkdir(parents=True)
        shutil.copyfile(plugin, work / "plugins/PlanetryItems.jar")
        write_local_sentinels(folder, "COLD")
        (folder / "config.yml").write_text(
            "definitions-source: proxy\nrefresh-interval-ticks: 2\n"
            f"proxy-sync:\n  shared-secret: '{FIXTURE_SECRET}'\n", encoding="utf-8")
        (work / "paper.jar").write_bytes(paper)
        (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (work / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n"
            "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
            "enable-status=false\npause-when-empty-seconds=-1\n"
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n',
            encoding="utf-8")
        build_probe(java, classpath, work)
        run_server(java, work, "cold", arguments.log_file, arguments.timeout)
        if not (folder / "proxy-cache.snapshot").is_file():
            raise AssertionError("Cold phase did not persist the proxy cache")
        write_local_sentinels(folder, "RESTART")
        restart_log = None
        if arguments.log_file:
            restart_log = arguments.log_file.with_name(
                arguments.log_file.stem + ".restart" + arguments.log_file.suffix)
        run_server(java, work, "restart", restart_log, arguments.timeout)
    print("PASS: packaged PlanetryItems proxy sync on Paper 26.1.2 build 74; "
          "18 cold + 2 restart groups: cold/no-proxy and full-storage preservation, "
          "signed multipart updates, generation fencing, last-good rejection, cache restart; "
          "real stacks/server lifecycle, controlled players and transport")


if __name__ == "__main__":
    main()
