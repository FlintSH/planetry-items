# Developer guide

[Documentation home](../README.md) · [Acceptance checks](ACCEPTANCE.md)

## Build and runtime validation

Use JDK 25 and Maven. `pom.xml` compiles against
`paper-api:26.1.2.build.74-stable`; the plugin targets Paper/Purpur 26.1.2.
Set `JAVA_HOME` to your JDK and include its `bin` directory in `PATH`.

```sh
mvn -B -ntp clean verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
python3 scripts/smoke.py target/planetry-items-1.0.0.jar
python3 scripts/smoke.py target/planetry-items-1.0.0.jar --without-hooks
```

`1.0.0` is the local POM version. Substitute the actual built version when
running a CI-versioned JAR. The smoke script uses a disposable loopback server
with a pinned, checksum-verified Paper build and accepts the EULA for that
temporary server. It requires the compiled test classpath. It never uses
production configuration or player data and stops only its own process.

Tests cover catalog validation, documentation examples, language rendering,
optional hooks and real Paper ItemStack/event behavior. Runtime probes use
controlled player and optional API fixtures, rather than connected clients or
actual Nexo/AuraSkills binaries. See [acceptance checks](ACCEPTANCE.md) for
coverage and the remaining staging work.

The plugin bundles no third-party implementation libraries. Paper supplies
Adventure, SnakeYAML and Guava. Optional integrations are accessed through
compatibility-checked bridges. Test fixture classes belong only to tests and
probe JARs, never the released plugin.

## Using the item service

Use a matching PlanetryItems API at compile time and declare `depend` or
`softdepend: [PlanetryItems]` in your plugin descriptor. Acquire the enabled
`PlanetryItems` instance through Bukkit's plugin manager. With a soft dependency,
handle its absence or disablement. All item/inventory/hook calls run on the
server thread.

| API | Result / caller responsibility |
| --- | --- |
| `plugin.catalog().get(id)` | Active immutable definition, or null |
| `plugin.items().id(stack)` | Persistent managed ID, including a removed ID; null for unmanaged stacks |
| `plugin.items().definition(stack)` | Active definition, or null; distinguish unmanaged from retired via `id` |
| `plugin.items().create(id, viewer)` | Create one new item at full configured durability; may fail for an unavailable Nexo source; does not insert it into an inventory |
| `plugin.items().unmet(player, definition)` | Ordered immutable set of unmet positive requirement keys |
| `plugin.items().usable(player, stack)` | Check active identity, inventory readiness, requirements and remaining durability; unmanaged items return true |
| `plugin.items().refresh(stack, viewer)` | Update current lore/requirements/attributes and other refresh-owned fields in place; does not rebuild material/Nexo source or insert anything |
| `plugin.items().migrate(stack, viewer)` | Return a rebuilt copy preserving instance data and absolute remaining durability; caller handles quantity/splitting and insertion |
| `plugin.guard().migrate(player)` | Apply the carried-inventory scan and return updated/removed/failed stack counts; does not implement the manual command's cooldown |
| `plugin.items().damage(player, stack, slot)` | Spend one point; true means the item broke; do not also charge the plugin's existing event path |

Check `plugin.hooks().inventoryReady(player)` immediately before modifying
player inventories. It is a current-session check, not a reservation valid on
later ticks. Installed but disabled/incompatible Inventory Sync must fail closed.
Creation is not a grant or a requirement bypass: an item can be created for a
player who cannot use it. Stage capacity before granting, preserve full stacks,
and handle unavailable integrations explicitly.

Do not retain catalog/definition references as live mutable configuration:
reload replaces immutable snapshots. Do not mutate player inventory off-thread,
call migrations while unready, or add persistent AuraSkills modifiers under
PlanetryItems' names.

## Persistent data contract

| Scope/key | Bukkit type | Meaning |
| --- | --- | --- |
| Item `planetryitems:item_id` | `STRING` | Stable configured identity |
| Item `planetryitems:durability` | `INTEGER` | Absolute remaining use counter |
| Item `planetryitems:oxygen` | `DOUBLE` | Configured oxygen metadata, absent when not configured |
| Player `planetryitems:last_migrate` | `LONG` | Last completely successful manual migration time in epoch milliseconds, per backend |

Normal Paper serialization and Inventory Sync preserve item PDC. There is no
separate PlanetryItems database. Future plugins may read oxygen, but this
release does not consume it. Prefer the service methods for item changes so
metadata, lore and native components stay consistent. Do not treat a raw ID
alone as proof that its definition remains valid or the item is usable.

## Releases and contributions

The workflow assigns version `1.0.<run-number>`, runs Maven and both Paper
probe modes, then uploads the tested JAR and `SHA256SUMS`. A successful push
to `main` publishes a `build-<run-number>` prerelease from that artifact with
no rebuild. Check the exact commit, release tag and downloaded checksums when
verifying a delivery.

Read `AGENTS.md` and Planetry's live coordination instructions before issue
work. Feature requests belong in GitHub issues; automated development accepts
only the account authorized by the central immutable numeric identity policy.
Publishing a release does not authorize production installation.
