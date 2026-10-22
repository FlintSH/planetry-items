#!/usr/bin/env python3
"""Run the packaged PlanetryItems JAR on a disposable Paper 26.1.2 server.

Usage: python3 scripts/smoke.py target/planetry-items-VERSION.jar [--without-hooks]
Requires JAVA_HOME (JDK 25) and target/test-classpath.txt from Maven.
The runner accepts Mojang's EULA only for its temporary test server, binds it
to loopback, and stops only its own process. It never reads a live server's
configuration. Player input and optional plugin APIs are controlled fixtures;
this is real Paper ItemStack/event coverage, not real-client acceptance.
"""

import argparse
import base64
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


def paper_bytes():
    """Verify both cached and freshly downloaded bytes against the pinned SHA256."""
    cache = pathlib.Path(os.environ.get("PLANETRY_SMOKE_CACHE", pathlib.Path.home() / ".cache/planetry-smoke"))
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"paper-{PAPER_SHA}.jar"
    if target.is_file():
        data = target.read_bytes()
        if hashlib.sha256(data).hexdigest() == PAPER_SHA:
            return data
    request = urllib.request.Request(PAPER_URL, headers={
        "User-Agent": "PlanetryItems-smoke/1 (https://github.com/FlintSH/planetry-items)"
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


def build_probes(java, classpath, work, hooks):
    """Compile fixture sources separately from the release JAR and package test plugins."""
    classes = work / "probe-classes"
    classes.mkdir()
    subprocess.run([
        str(java.with_name("javac")), "-cp", classpath, "-d", str(classes),
        *map(str, sorted((ROOT / "scripts/probe").rglob("*.java"))),
    ], check=True, timeout=60)
    plugins = [(
        "ItemsProbe", "dev.flint.planetry.items.probe.ItemsProbe",
        ["dev/flint/planetry/items/probe/ItemsProbe"],
        ["PlanetryItems", "PlanetryCore", "AuraSkills", "Nexo", "NetworkInventorySync"] if hooks else ["PlanetryItems"],
    )]
    if hooks:
        plugins += [
            ("PlanetryCore", "dev.flint.planetry.items.probe.CoreFixture", ["dev/flint/planetry/items/probe/CoreFixture"], []),
            ("NetworkInventorySync", "dev.flint.planetry.items.probe.InventoryFixture", ["dev/flint/planetry/items/probe/InventoryFixture"], []),
            ("Nexo", "dev.flint.planetry.items.probe.NexoFixture", ["dev/flint/planetry/items/probe/NexoFixture", "com/nexomc/"], []),
            ("AuraSkills", "dev.flint.planetry.items.probe.AuraFixture", ["dev/flint/planetry/items/probe/AuraFixture", "dev/aurelium/"], []),
        ]
    for name, main, prefixes, dependencies in plugins:
        with zipfile.ZipFile(work / "plugins" / f"{name}.jar", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob("*.class")):
                relative = path.relative_to(classes).as_posix()
                if any(relative.startswith(prefix) for prefix in prefixes):
                    archive.write(path, relative)
            descriptor = f"name: {name}\nversion: 'fixture-1'\nmain: {main}\napi-version: '26.1.2'\n"
            if dependencies:
                descriptor += "depend: [" + ", ".join(dependencies) + "]\n"
            archive.writestr("plugin.yml", descriptor)


def test_items():
    # A synthetic texture property exercises local profile serialization, without HTTP.
    texture = base64.b64encode(
        b'{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}}}'
    ).decode("ascii")
    return f"""items:
  bare:
    material: DIAMOND_SWORD
    display-name: '<white>Probe Blade'
    type: WEAPON
    rarity: COMMON
    max-stack-size: 1
    max-durability: 0
  glyph_probe:
    material: PAPER
    display-name: '<glyph:probe_heart:c> Probe'
    lore: ['<glyph:probe_heart:2..3:colorable:shadow:#80ffffff><shift:-8>', '<glyph:probe_missing>', '<glyph:probe_empty>', '<glyph:probe_broken>']
    rarity: RARE
    max-durability: 0
  stacked_sword:
    material: DIAMOND_SWORD
    type: WEAPON
    rarity: COMMON
    max-stack-size: 16
    max-durability: 0
  eternal_tool:
    material: DIAMOND_PICKAXE
    type: TOOL
    rarity: LEGENDARY
    max-stack-size: 16
    max-durability: 3
    unbreakable: true
  glider:
    material: ELYTRA
    type: ARMOUR
    rarity: RARE
    equip-slot: CHEST
    max-stack-size: 1
    max-durability: 3
  single_glider:
    material: ELYTRA
    type: ARMOUR
    equip-slot: CHEST
    max-durability: 1
  huge_trident:
    material: TRIDENT
    type: WEAPON
    max-durability: 2147483647
  restricted_glider:
    material: ELYTRA
    type: ARMOUR
    rarity: RARE
    equip-slot: CHEST
    max-stack-size: 1
    max-durability: 3
    requirements:
      planetry-gamelevel: 5
  described:
    material: PAPER
    display-name: '<aqua>Probe Notes'
    lore: ['<gray>First probe line', '<gray>Second probe line']
    custom-model-data: 812
    type: ITEM
    rarity: RARE
    max-stack-size: 16
    max-durability: 0
  tool:
    material: PAPER
    display-name: '<white>Probe Tool'
    type: TOOL
    rarity: UNCOMMON
    max-stack-size: 1
    max-durability: 3
  brush:
    material: BRUSH
    type: TOOL
    max-durability: 3
  pig_control:
    material: CARROT_ON_A_STICK
    type: TOOL
    max-durability: 3
  strider_control:
    material: WARPED_FUNGUS_ON_A_STICK
    type: TOOL
    max-durability: 3
  durability_sword:
    material: DIAMOND_SWORD
    type: WEAPON
    rarity: UNCOMMON
    max-stack-size: 1
    max-durability: 100
  shears:
    material: SHEARS
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  axe:
    material: DIAMOND_AXE
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  hoe:
    material: DIAMOND_HOE
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  shovel:
    material: DIAMOND_SHOVEL
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  flint:
    material: FLINT_AND_STEEL
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  fishing_rod:
    material: FISHING_ROD
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 10
  restricted_fishing_rod:
    material: FISHING_ROD
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 10
    requirements:
      planetry-gamelevel: 5
  eternal:
    material: STICK
    type: TOOL
    rarity: LEGENDARY
    max-stack-size: 1
    max-durability: 3
    unbreakable: true
  hat:
    material: PAPER
    type: SPACESUIT
    rarity: EPIC
    equip-slot: HEAD
    max-stack-size: 1
    max-durability: 10
    bonuses:
      planetry-oxygen: 100
    attributes:
      'minecraft:armor': 2
      'minecraft:scale': 0.25
  restricted:
    material: PAPER
    type: COSMETIC
    rarity: MYTHICAL
    equip-slot: HEAD
    max-stack-size: 1
    max-durability: 10
    attributes:
      'minecraft:armor': 4
      'minecraft:attack_damage': {{amount: 6, slot: MAINHAND}}
    bonuses:
      auraskills-additional-strength: 9
    requirements:
      planetry-gamelevel: 5
      auraskills-required-mining: 3
  food:
    material: APPLE
    type: FOOD
    rarity: COMMON
    max-durability: 0
    requirements:
      planetry-gamelevel: 5
  restricted_hand:
    material: PAPER
    type: WEAPON
    rarity: MYTHICAL
    max-stack-size: 1
    max-durability: 10
    attributes:
      'minecraft:attack_damage': 6
    bonuses:
      auraskills-additional-strength: 9
    requirements:
      planetry-gamelevel: 5
  food_plain:
    material: APPLE
    type: FOOD
    rarity: COMMON
    max-durability: 0
  food_durable:
    material: APPLE
    type: FOOD
    rarity: COMMON
    max-stack-size: 1
    max-durability: 2
  food_eternal:
    material: APPLE
    type: FOOD
    rarity: COMMON
    max-stack-size: 16
    max-durability: 2
    unbreakable: true
  trident:
    material: TRIDENT
    type: WEAPON
    rarity: RARE
    max-stack-size: 1
    max-durability: 3
  restricted_trident:
    material: TRIDENT
    type: WEAPON
    rarity: RARE
    max-stack-size: 1
    max-durability: 3
    requirements:
      planetry-gamelevel: 5
  shield:
    material: SHIELD
    type: TOOL
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  throwable:
    material: SNOWBALL
    type: ITEM
    rarity: COMMON
    max-stack-size: 1
    max-durability: 3
  throwable_plain:
    material: SNOWBALL
    type: ITEM
    rarity: COMMON
    max-stack-size: 16
    max-durability: 0
  bow:
    material: BOW
    type: WEAPON
    rarity: COMMON
    max-stack-size: 1
    max-durability: 10
    requirements:
      planetry-gamelevel: 5
  aura:
    material: PAPER
    type: ARMOUR
    rarity: EPIC
    equip-slot: HEAD
    max-stack-size: 1
    bonuses:
      auraskills-additional-wisdom: 2
      auraskills-additional-health: 3
      auraskills-additional-regeneration: 5
      auraskills-additional-toughness: 1
      auraskills-additional-strength: 4
  skull:
    material: PLAYER_HEAD
    type: COSMETIC
    rarity: RARE
    equip-slot: HEAD
    max-stack-size: 1
    base64: '{texture}'
  ordinary_head:
    material: PLAYER_HEAD
    type: ITEM
    rarity: COMMON
    max-stack-size: 1
  ordinary_pumpkin:
    material: CARVED_PUMPKIN
    type: ITEM
    rarity: COMMON
    max-stack-size: 1
  ordinary_helmet:
    material: DIAMOND_HELMET
    type: ITEM
    rarity: COMMON
    max-stack-size: 1
  nexo:
    material: PAPER
    nexo-id: probe_texture
    type: ITEM
    rarity: RARE
    max-stack-size: 1
"""


def nested_items():
    return """items:
  nested_probe:
    material: AMETHYST_SHARD
    display-name: 'Nested Probe'
    rarity: EPIC
    type: ITEM
    max-durability: 0
"""


def run_server(java, work, log_file):
    process = subprocess.Popen(
        [str(java), "-Xms256m", "-Xmx768m", "-jar", "paper.jar", "--nogui"],
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
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                lines.append(output.get(timeout=1))
            except queue.Empty:
                if process.poll() is not None:
                    reader.join(timeout=2)
                    break
        else:
            raise TimeoutError("PlanetryItems Paper probe exceeded 180 seconds")
        while not output.empty():
            lines.append(output.get_nowait())
        log = "".join(lines)
        if "ITEMS_PROBE_OK" not in log or "ITEMS_PROBE_FAILED" in log:
            raise AssertionError("PlanetryItems probe failed; see server output")
        if "Could not pass event" in log or "generated an exception" in log or "Error occurred while enabling PlanetryItems" in log:
            raise AssertionError("Plugin event/lifecycle exception in Paper; see server output")
        if "Fatal error trying to convert" in log:
            raise AssertionError("Paper class remapper fatal error; see server output")
        if process.returncode != 0:
            raise AssertionError(f"Paper exited {process.returncode}")
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
        print(log)
        if log_file:
            log_file.parent.mkdir(parents=True, exist_ok=True)
            log_file.write_text(log, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plugin", type=pathlib.Path)
    parser.add_argument("--without-hooks", action="store_true", help="omit every optional plugin fixture")
    parser.add_argument("--log-file", type=pathlib.Path, help="preserve the disposable server output")
    arguments = parser.parse_args()
    java = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java"
    plugin = arguments.plugin.resolve(strict=True)
    entries = (ROOT / "target/test-classpath.txt").read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join([str(plugin), entries])
    with zipfile.ZipFile(plugin) as archive:
        for required in ("plugin.yml", "items.yml", "lang.yml"):
            if required not in archive.namelist():
                raise RuntimeError(f"Packaged resource is missing: {required}")
        if any("probe/" in name or "Fixture" in name for name in archive.namelist()):
            raise RuntimeError("Test fixtures leaked into the release JAR")
    paper = paper_bytes()
    with tempfile.TemporaryDirectory(prefix="planetry-items-paper-") as temporary:
        work = pathlib.Path(temporary)
        (work / "plugins/PlanetryItems").mkdir(parents=True)
        shutil.copyfile(plugin, work / "plugins/PlanetryItems.jar")
        (work / "plugins/PlanetryItems/items.yml").write_text(test_items(), encoding="utf-8")
        # Keep items/ absent for the startup import, then let the probe add and reload
        # this separate nested file without replacing the imported legacy catalog.
        (work / "plugins/PlanetryItems/nested-fixture.yml").write_text(nested_items(), encoding="utf-8")
        (work / "plugins/PlanetryItems/config.yml").write_text("refresh-interval-ticks: 2\n", encoding="utf-8")
        (work / "plugins/PlanetryItems/lang.yml").write_text(
            "glyph-fallbacks:\n  probe_heart: '[HEART]'\n  probe_missing: '[MISSING]'\n"
            "  probe_empty: '[EMPTY]'\n  probe_broken: '[BROKEN]'\n"
            "messages:\n  glyph-probe: '<glyph:probe_heart><shift:8> {value}'\n", encoding="utf-8")
        (work / "paper.jar").write_bytes(paper)
        (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (work / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n"
            "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
            "enable-status=false\npause-when-empty-seconds=-1\n"
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n',
            encoding="utf-8",
        )
        build_probes(java, classpath, work, not arguments.without_hooks)
        run_server(java, work, arguments.log_file)
    mode = "without optional hooks" if arguments.without_hooks else "with controlled optional API fixtures"
    print(f"PASS: packaged PlanetryItems on Paper 26.1.2 build 74 {mode}; real stacks/events, controlled players")


if __name__ == "__main__":
    main()
