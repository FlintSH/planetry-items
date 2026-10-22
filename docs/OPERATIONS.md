# Operations and troubleshooting

[Documentation home](../README.md) · [Staff guide](STAFF_GUIDE.md) ·
[Configuration reference](CONFIGURATION.md)

## Install and upgrade

PlanetryItems runs on **Paper/Purpur 26.1.2 and Java 25**, on each gameplay
backend. It is not a Velocity, Spigot or Folia plugin. No database is needed.
Installation and live player-data operations belong to the network operator.

1. Download the desired prerelease JAR and `SHA256SUMS` from
   [Releases](https://github.com/FlintSH/planetry-items/releases). Check its
   release notes and verify the checksum before installing.
2. On staging, stop the backend. For upgrades, back up the plugin folder and
   player inventory data. Replace the existing PlanetryItems JAR rather than
   leaving multiple versions in `plugins/`.
3. Start with Java 25 and check the console for successful enablement and the
   expected definition count. Invalid startup configuration disables the plugin.
4. Test the actual optional plugins/resource pack and player flows using
   [acceptance checks](ACCEPTANCE.md). Repeat on the supported Purpur build if
   that is the network's runtime.
5. The operator can then schedule the live rollout. Use matching definitions,
   language files and compatible integration versions across all backends
   sharing inventories. Do not move players through mismatched catalogs.

Generated files:

| Path below `plugins/PlanetryItems/` | Purpose |
| --- | --- |
| `config.yml` | Periodic refresh interval |
| `lang.yml` | Shared messages and lore templates |
| `items/examples.yml` | Four bundled definitions on a fresh install |
| `items/imported.yml` | One-time copy of legacy root `items.yml`, if upgrading |
| `items/` subfolders | Additional staff-created `.yml` / `.yaml` definitions |

An existing `items/` directory is never repopulated or reimported, even when
empty. The legacy root `items.yml` stays untouched as a backup and is ignored
once the directory exists. Move its entries into the active folder; do not
edit the ignored backup expecting a reload to use it.

## Commands and permissions

All `/planetryitems` management subcommands, also available as `/pitems`,
require **`planetry.admin.items`** on the backend. Operators have it by
default. The old `planetryitems.admin` permission no longer grants access.
There is no requirement bypass node.

| Command | Sender and behavior |
| --- | --- |
| `/pitems list` | Staff/console; list active case-sensitive IDs |
| `/pitems give <online-player> <id> [amount]` | Staff/console; exact online player name; amount defaults to 1, allowed 1–2304 |
| `/pitems info` | Staff player; inspect the managed item in the main hand |
| `/pitems soulboundbypass` | Staff player only; toggle the limited soulbound drop override; off by default |
| `/pitems reload` | Staff/console; validate and activate all configuration together |
| `/migrate` | Any player; no permission, no arguments or target; update their carried inventory |

Console commands use the same arguments without the leading slash. Tab
completion for management commands is permission-gated. `/migrate` has no
argument completions. A grant stages all additions against the 36 storage
slots before committing; insufficient space gives no partial grant and no
overflow drops. The local command targets an online player on this backend.
A compatible authenticated network bridge can call the same grant path on the
target's current backend through the [network item API](DEVELOPMENT.md#network-item-api).
The bridge/proxy authorizes network staff requests; the local command retains
`planetry.admin.items`. Offline grants are unavailable in both cases, and the
receiving backend rechecks the live session and Inventory Sync readiness.

`info` shows the ID/current durability, configured and effective material,
model data, name, rarity, type, visual source, equipment slot, quantity,
stack size, unbreakable flag, `canplace`, `soulbound`, attributes, bonuses, requirements, flavour lore
and current usability. It requires a recognized active definition.

`/pitems soulboundbypass` uses the existing `planetry.admin.items` permission;
console cannot use it. Each player's toggle starts **off** and resets on
session changes (including reconnects), reload and death. While it is on, the
first explicit soulbound drop is cancelled and sends this default warning:

> This item is Soulbound, are you sure you want to drop it? Toggle this bypass with /planetryitems soulboundbypass

Repeat the drop with an identical item and amount within **two seconds** to
allow that drop once. A changed item/amount or expired confirmation needs a
new warning; one confirmed drop does not authorize another. The toggle stays
on until disabled or reset. It never permits chest/external storage, world
deposits or placement, death drops or a requirements bypass. It does not make
a soulbound trident throwable. See [language keys](LANGUAGE.md#message-substitutions)
to translate the toggle messages and warning.

## Reload and recover

`/pitems reload` parses `config.yml`, every item file and `lang.yml`, validates
registered attributes and renders templates before replacing the active
snapshot. It also reloads `refresh-interval-ticks` (integer 1–1200, default 20).
On success it queues full migration for online players, including visuals.
Installing/updating PlanetryItems or optional plugins requires a restart.

If reload fails:

1. Leave the running backend up: the previous active catalog, language and
   interval remain in use. The command gives a generic error; the console
   reports the file/field causing rejection.
2. Fix the reported YAML, unknown key, duplicate ID, invalid number, attribute
   or template. Alternatively restore the active files from the backup kept
   outside `items/`.
3. Run `/pitems reload` again. Check the expected IDs with `/pitems list`, then
   inspect an item and test a grant.

Do not restart as a workaround for invalid files. Invalid startup has no old
snapshot to fall back to. A successful reload validates the definition syntax;
it does not prove a configured Nexo item is currently loaded or the player has
available Core/AuraSkills data.

## Inventory migration

Full migration runs automatically after joining each backend, including
server switches, and after a successful reload for online players. It waits
for Inventory Sync readiness. Pending automatic migrations retry failed stacks
with a five-second delay until sources/space become available. Ordinary
periodic reconciliation refreshes lore, requirements, attributes and bonuses;
full migration also rebuilds material and visual sources.

Players may run `/migrate` manually. Complete success, even with no changed
items, records a two-hour cooldown in player persistent data. The cooldown
survives reconnects/restarts on that backend; it is not a shared network
database. Readiness failure and a partial migration do not consume it.
Automatic migrations neither consume nor obey that manual cooldown. A failed
manual call does not by itself schedule automatic retries; try again when
the cause is resolved unless a session migration is already pending.

The scan includes storage/hotbar, armour, offhand and cursor. It excludes
ender chests, world containers and dropped items. Nested container/bundle
contents are preserved, rather than recursively scanned. Items moved out of
containers into carried storage are subject to reconciliation; a full update
of their material/visual source requires migration.

| State | Result |
| --- | --- |
| Definition exists | Rebuild definition-owned material, visuals, name/lore, equipment, attributes, bonuses and limits |
| Existing instance data | Preserve quantity, enchantments, unrelated PDC, container/bundle contents, book text and stored enchantments |
| Durability | Preserve absolute remaining points, capped to the current maximum; never repair |
| Smaller stack limit | Split only when all extra stacks fit storage; otherwise retain that stack and report failure |
| Oversized cursor stack | Retain it and report failure; move it into storage/free space before retrying |
| Missing Nexo source | Retain the original stack and report failure |
| Unknown/removed ID | Remove the stack and send the saved name in a contact-staff message |

Migration is per stack, not an all-inventory transaction. A partial run can
update/remove some stacks while retaining others; counters report stacks,
not individual items. Normal reconciliation can still refresh lore or
requirement state on retained items. Missing IDs are also removed during
ordinary reconciliation, so avoiding `/migrate` does not protect retired items.

Do not rename IDs to change visible names. An accidental ID deletion or
mismatched backend can remove player possessions. Restore the definition to
prevent more removals, then use an operator-reviewed backup/replacement process
for stacks already removed. YAML rollback alone cannot recover them.

## Durability changes

Durability is an absolute persistent use counter. Raising the maximum from
100 to 200 leaves an existing 40-point item at 40/200. Lowering it to 20 caps
that item at 20/20; raising it again does not restore the original 40.

Switching to `max-durability: 0` and migrating stores zero remaining points.
Switching back to positive durability keeps that zero, leaving old instances
unusable. Plan and test redesigns with old items; there is no repair/refill
command. New `/pitems give` instances begin at the new maximum. Unbreakable
mode never spends points and hides the counter, but is not a repair operation.

## Optional integrations

| Integration | Setup / contract | Failure behavior |
| --- | --- | --- |
| Nexo 1.22+ for 26.1.2 | Enabled on each backend needing its sources; matching client resource pack; actual loaded IDs | Missing/loading source prevents creation and retains old stacks on migration; glyphs use configured fallbacks when native resolution is unavailable |
| AuraSkills 2.4.0 | Loaded player data for Farming, Foraging, Mining, Fishing, Fighting and five supported stat bonuses | Positive skill requirements deny use; bonuses inactive when unavailable |
| PlanetryCore | Compatible enabled Core with loaded `cachedProgress(UUID)` | Positive game-level requirements deny use; never substitutes vanilla XP |
| NetworkInventorySync | Compatible enabled `isReady(UUID)` for the current online session | If installed but disabled/incompatible/unready, managed use/grants/refresh/migration are denied or deferred; absence does not block standalone use |

PlanetryItems has no database. Network Inventory Sync serializes persistent
item data, but does not distribute PlanetryItems definitions or language files.
Keep those matched separately. Plugins added or updated need a backend restart.

Nexo loads sources asynchronously. A missing source immediately after startup
may become available later. Check its exact ID and Nexo's own load result;
retry the grant once ready. Nexo source visuals are copied without its updater
identity or custom gameplay, so its updater cannot replace Planetry metadata.
After changing source visuals, migrate existing items. Native glyph behavior
and permissions are covered in the [language guide](LANGUAGE.md#nexo-glyphs-and-spacing).

## Troubleshooting

| Symptom | Check / action |
| --- | --- |
| No permission | Grant backend `planetry.admin.items`; remove reliance on retired `planetryitems.admin` |
| Edited file has no effect | Edit `items/`, not ignored root `items.yml`; reload successfully on the correct backend |
| Reload rejected | Read console file/field; check indentation, duplicate IDs/headings, unknown keys and numeric types |
| Plugin disabled at startup | Restore valid files; check Paper/Purpur 26.1.2 and Java 25; restart after correction |
| Unknown item | Use the exact lowercase ID in `/pitems list`; verify that its reload succeeded |
| Player not found | Target must be online on this backend; use their exact name |
| Grant says inventory full | Free enough storage for the entire amount; no partial grant was made |
| Integration unavailable | Check Inventory Sync session readiness, Nexo source load and console details; do not bypass readiness |
| Cannot use item despite being op | Meet all positive levels; check loaded Core/AuraSkills data and durability; permission is not a bypass |
| Sword has no expected attack bonus | Configure explicit attributes; material defaults are cleared |
| Name color ignores color tag | Rarity owns the name color; edit rarity rather than name tags |
| Glyph missing or wrong font | Check real Nexo ID, permissions and resource pack; use a configured text fallback and native tags |
| Model/texture missing | Check client resource pack, actual Nexo source or Base64 texture property; migrate after source changes |
| Old item still looks different | A partial migration may be waiting for source/space; periodic refresh alone does not rebuild all visuals |
| Migration incomplete | Free storage for split stacks, clear oversized cursor stacks, and check missing Nexo sources; retry |
| Item disappeared after update/server switch | Compare IDs/catalogs on every backend; restore missing definitions to prevent further removals and review inventory backups |
| Equipped item moved or dropped | Player lost requirements or item no longer matches its worn slot/type; check definition, storage capacity and Core/AuraSkills availability; full storage leaves soulbound equipment worn without native attributes or AuraSkills bonuses until space opens |
| Item will not place, with no message | Check `canplace` and `soulbound`; blocked placement spends no item or custom durability |
| Soulbound item will not enter a chest or drop | External writes and world deposits are blocked, including carriers with nested soulbound contents; own ender chest and safe retrieval remain available |
| Staff drop override does not work | Use a player with `planetry.admin.items`, enable `/pitems soulboundbypass`, then repeat an identical item/amount drop within two seconds; reconnects, reload and death reset it |
| Crafting/repair result blocked | Managed items cannot be used in supported crafting/workstation result paths; there is no custom repair command |
| Food lasts many meals | Default durability is 100; use 0 for single-use consumables or intentionally design reusable food |

When reporting a problem, include plugin/runtime versions, affected ID,
reproduction steps, `/pitems info` output and a **redacted** relevant console
error. Do not post secrets, private connection details or whole server configs.
Automated probe results and real-client/staging limits are documented in
[acceptance checks](ACCEPTANCE.md).

## Soulbound cursor recovery

Closing a menu, disconnecting or dying with a protected item on the cursor
saves that exact stack in the player's normal backend data before native cursor
cleanup. It returns automatically once the player is online, alive, Inventory
Sync is ready and storage has room. Items return as space opens; exact remainders stay saved. Free inventory space if
it has not returned yet. No overflow stack is dropped.

Pending recovery belongs to the backend where the menu closed. It is not part
of Inventory Sync's shared inventory profile; return to that backend to receive
it. Keep its normal player-data backups. Do not edit or delete the
`planetryitems:soulbound_recovery` player-data entry to resolve a pending item.
Malformed recovery data is retained and logged for operator review.

## Central definitions from Velocity

[PlanetryItems-Velocity](https://github.com/FlintSH/planetry-items-velocity) owns
items/ and lang.yml when this backend selects definitions-source: proxy. Copy
the active backend items/ directory and customized language to the proxy, load
them with /piv reload, then configure the same private proxy-sync.shared-secret
and restart participating backends. Keep config.yml and optional integrations
on each backend. Source/key changes require a full restart; /planetryitems
reload in proxy mode reloads the local refresh interval and requests a proxy
update without reading local definitions or language.

Each update validates the complete pair, atomically caches proxy-cache.snapshot,
then activates it. Invalid data, interrupted transfers, disk failures and a
missing proxy retain the last accepted pair, including across restarts. With
no accepted cache, item mutations and migration wait for the first snapshot;
local examples are never substituted. A player connection carries updates, so
empty/offline backends catch up on first connection. Check /piv status and wait
for matching current revisions before relying on an edit across servers.

Proxy mode preserves unknown managed IDs, including retired items, as unusable
until their definitions are available. Native attribute bonuses are suspended
while unknown; quantities, identity and instance data remain. This prevents a
new item transferred to a lagging backend from being deleted. To roll back to
local mode, stop the backend, restore its backed-up local items/ and lang.yml,
select definitions-source: local and restart. No automatic file migration occurs.

Items stamped with a newer proxy generation wait even if their ID exists in
an older backend catalog. Their stored durability and instance data remain
unchanged, and full-storage equipment stays suspended in place. Keep the proxy
generation.dat and generation.initialized files in backups; do not reset them
when restoring source YAML. Rollbacks receive a higher generation on reload.
