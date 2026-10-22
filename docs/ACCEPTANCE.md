# Validation

[Documentation home](../README.md) · [Staff guide](STAFF_GUIDE.md) ·
[Build and API reference](DEVELOPMENT.md)

Build on Java25, then test the exact packaged JAR:

```sh
mvn -B -ntp clean verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
python3 scripts/smoke.py target/planetry-items-1.0.0.jar
python3 scripts/smoke.py target/planetry-items-1.0.0.jar --without-hooks
```

`JAVA_HOME` must identify the JDK. The smoke runner verifies a pinned Paper26.1.2
build74 SHA256, creates a disposable loopback server, accepts the EULA for that
server, compiles test-only plugins, and stops only its own process. It uses no
production configuration, database or player data. Test fixtures never enter the
released plugin JAR. Optional API fixtures model Core's progress cache,
AuraSkills2.4.0, Nexo's items/glyphs and Inventory Sync readiness.

Unit tests validate complete catalogs, duplicate/invalid keys, blank and absent
values, numeric bounds, slots, model/texture exclusivity, immutable definitions,
lore ordering/spacing, requirement visibility, formatting, glyph fallback,
native glyph option forwarding, fonts, shift tags, current/global and older
player-aware resolver APIs, disabled/incompatible/throwing Nexo hooks, literal
dynamic values and nonrecursive fallback text,
low-durability colouring and optional integration failure/cleanup. Recursive folder
ordering, cross-file duplicate rejection, invalid nested definitions, safe one-time
legacy import, empty directories, symbolic links, rarity name coloring and clean
requirement messages have regression coverage.

`DocumentationExamplesTest` loads the six-item catalog in
`docs/examples/items/` through the production catalog reader, checks the
first-item walkthrough matches that catalog, and loads/renders the copied
partial language override. It validates configuration and template behavior;
the placeholder Nexo source still requires a real loaded ID, and these tests
do not prove client rendering or grant every documented example in-game.
The reference's policy/attribute/bonus/requirement snippets also load through
the production parser. The complete attribute table is compared with all 35
constants in the pinned Paper API, so omissions or spelling changes fail tests.
Policy tests cover short and legacy names in both locations, invalid values,
equivalent definitions and duplicate spellings even when their values agree.

Real Paper probes validate actual ItemStack components and serialization, empty
vanilla attributes, registry attributes, custom equipment, head texture properties,
oxygen PDC, use and equipment restrictions, custom durability, commands, full
inventory grant atomicity, reload retention and the optional bridges. Issue11 probes
cover holding restricted items without native bonuses, full info/permission gates,
public migration/cooldown, readiness-delayed join migration, definition removal and
rarity/unbreakable output. Stateful shulker/bundle/book/stored-enchantment data,
partial-stack merge quantity conservation, safe failed splits, stale-attribute cursor
repair, quick same-UUID reconnects and delayed Nexo recovery have focused probes.
Glyph probes cover configured display names/lore and language messages, bitmap/
colorable/shadow arguments, shifts, font preservation through real item serialization,
MiniMessage target/identity forwarding, per-recipient permission isolation,
unavailable/empty/throwing glyph fallbacks and literal values.
The join/quit sequence models a backend switch; it is not
a connected-client transfer. They use
controlled Player implementations and Bukkit event dispatch. The command tests
briefly substitute a forwarding server lookup so controlled players can be found;
the original server is restored in a finally block. These are not network clients.
Policy probes cover strict flag loading, serialized old/new items, both-hand silent
placement, incoming/outgoing inventory routes, nested carriers and bundle unload
vetoes, exact drop confirmations and cursor recovery. Recovery includes a queue
larger than storage, incremental capacity, readiness delays and corrupt old data
with an independently saved new cursor.
Soulbound label probes check the same-line suffix, exact purple label colour,
ordinary/placement-only controls, serialization, old-item migration, repeated
refresh, flag changes in both directions and custom type/suffix translations.
Short-name probes exercise new items, serialization and existing-item migration;
legacy-name reloads preserve protections. Mixed alias reloads retain the active
catalog. Info probes check the short detail labels and both old/new template
substitutions in an existing custom header.

Issue18 probes keep counters unchanged for general air/block/entity clicks in
both hands, including cancelled inputs. Combat, mining and other completion
events retain positive and cancelled controls. Native damage components mirror
the custom counter through use, repeated refresh, serialization and migration,
including actual native bar visibility and width, final usable glider/trident
points, single/large maxima and cancelled Mending. Native bundle fullness bars
remain outside the durability-bar assertion;
the low-lore boundary is checked at 30% and the final point. Equipment probes
include nonwearable heads, pumpkins and native armour, stale equipment metadata,
valid wearable controls and inventory/dispenser/restored-inventory routes.
Special-tool controls cover both hands and positive/cancelled/zero native wear.
The actual native empty-stack conversion of each exhausted control stick must
return an ordinary fishing rod without reviving custom identity or metadata.

Network API regressions validate the reflected instance-method signatures,
bounded immutable sorted completions, prefix/ID limits and reload snapshots.
The same packaged Paper probes in both optional-hook modes grant actual
ItemStacks through the API, including 1/2304 limits, exact split quantities,
recipient notices, retained requirements and soulbound/placement policies.
They reject malformed requests, offline/dead/invalid and replaced sessions,
off-thread calls, unavailable Nexo sources and full inventories. A partial merge
followed by overflow must preserve every live stack, armour/offhand and cursor.
Controlled Inventory Sync callbacks revoke readiness, replace the UUID session
or change storage during preparation; the write must refuse each case.
Installed disabled Inventory Sync also refuses API grants.
Injected reconciliation/notification failures retain one successful grant;
an inventory write that applies then throws must propagate an uncertain outcome
without a definite failure status or retry. Unit tests cover the same failure
boundaries without claiming real ItemStack behavior.

Before live installation, the operator should test on staging with the same
Paper/Purpur version, actual Nexo1.22+/resource pack, AuraSkills2.4.0, PlanetryCore,
Inventory Sync and protection plugins used by the network:

1. Give every configured item to two players with different game/skill levels.
   Check glyph fonts, models, head textures, small type labels, attribute colours,
   one blank between sections, hidden met requirements and red durability at 30%.
   Confirm the normal durability bar matches the custom counter and refresh does
   not repair it. Full, unbreakable and counter-disabled items show no spent
   durability bar; bundles keep Minecraft's fullness bar.
   Soulbound items must show ` • ѕᴏᴜʟʙᴏᴜɴᴅ` after the type on the same line,
   with the label in `#B589D6`; ordinary items must have no suffix or separator.
2. Try both hands, shift/number/drag equipment moves, right-click equip, dispensers,
   reconnects, death/respawn and full storage. Confirm denied items give no passive
   modifiers and cannot be used; losing a requirement moves worn items to storage.
   With storage full, ordinary equipment may drop; soulbound equipment must stay
   worn without native attributes or AuraSkills bonuses until space opens.
3. Exercise melee/mining/protected blocks, food, bows/tridents, shields, wearable
   damage and item break effects. Confirm one use consumed, cancellations do not
   spend one, and AuraSkills stats return to their previous value after removal.
   Click air, blocks and entities with a sword/tool in each hand and confirm no
   wear until an action completes. Check actual shearing, tilling, stripping and
   path creation, including protection-plugin cancellation. Custom nonwearable
   heads, pumpkins and native armour must stay holdable but never equip; valid
   ARMOUR/COSMETIC/SPACESUIT items must still equip in their configured slot.
4. Edit definitions in nested `items/` folders, reload, and try `/migrate` as a
   non-admin. Check current material/model, all info fields, the two-hour per-backend
   cooldown, durability preservation and safe stack splitting when space is limited.
   Delete a test definition, then rejoin and verify removal names that item and asks
   the player to contact staff. Use disposable test items and matching catalogs.
5. Transfer items between backends with matching definitions, restart them, and
   verify identity, oxygen and remaining durability survive. Ensure Nexo reloads
   do not replace Planetry metadata, and verify missing/loading integration data
   denies only the operations that depend on it.
6. Check omitted policy flags behave as `canplace: true` and
   `soulbound: false`. Test unquoted `true`/`false` at the item root and
   under `bonuses`; reject quoted strings, numbers, blank/null values and duplicate
   declarations across both locations without changing the active catalog. Check
   `/pitems info` shows both effective flags. Soulbound adds only its configured
   type-line suffix; canplace adds no lore, and neither creates numeric stats.
   Keep the six example IDs unchanged when copying the staff catalog.
7. With `canplace: false`, test both hands for block placement, entity
   placement, hanging items and bucket contents. Every blocked placement must be
   silent and spend neither the item nor custom durability. Repeat with ordinary
   items and `canplace: true` as positive placement controls.
8. Test soulbound moves by left/right click, shift-click, number/offhand swaps,
   drag and double-click collection. Own storage/hotbar, equipment, cursor and
   own ender chest must work; chest/workstation/entity inventory writes, world
   deposits and placement must fail. Check safe retrieval from external storage
   and ordinary-item transfers still work. Repeat with bundles and container
   carriers, including nested soulbound contents. Verify ordinary food consumption
   and tool use still work when requirements are met, while soulbound trident
   throws fail without spending the item or durability. With normal death drops
   enabled, check only protected items/carriers are retained and ordinary items
   still drop, including when the staff drop override is on.
9. As a staff player with `planetry.admin.items`, verify `/pitems soulboundbypass`
   starts off, toggles on/off, and resets after reconnect/session changes, reload
   and death. Check non-admin and console rejection. While on, the first explicit
   soulbound drop must cancel and show exactly:

   `This item is Soulbound, are you sure you want to drop it? Toggle this bypass with /planetryitems soulboundbypass`

   An identical item and amount dropped again within two seconds must be allowed
   once. Changed items/amounts, an expired confirmation and the next drop must
   require a new warning. Check chest writes, world deposits, placement and
   soulbound trident throws remain blocked, and unmet requirements still deny use.

Automated probes do not prove actual Nexo/AuraSkills binary compatibility,
client resource-pack rendering, arbitrary plugin event ordering, Purpur or
cross-backend transfers. They also do not authorize production installation.

Main CI uploads the tested JAR with `SHA256SUMS`. Release downloads must match
those tested artifact bytes, and the release tag must resolve to the tested full
commit SHA. No rebuild occurs in the release job.

10. With a protected stack on the cursor and full storage, close the menu,
    disconnect and die with keep-inventory on and off. Confirm no world drop or
    item loss. Free a slot and verify exact quantity/metadata returns once on
    the same backend, including after restart and a readiness delay. Pending
    cursor recovery is backend player data, outside the shared inventory profile;
    check server switches by returning to the originating backend.

11. Test protected arrows in bows and crossbows, including a crossbow loaded
    before changing a definition to soulbound. Refused loading/firing must retain
    ammunition. A soulbound bow/crossbow with ordinary ammunition must still work.

## Proxy-owned definitions and language

Run `python3 scripts/sync-smoke.py target/planetry-items-VERSION.jar` with Java25
and the Maven test classpath. Two real Paper26.1.2 processes check cold proxy
mode without a cache and a restart with the accepted pair. Controlled players
and a seeded pending request enter the registered production Messenger listener.
The fixture covers multipart updates above32KiB, exact paired persistence and
acknowledgements, bad MAC/carrier/nonce/deadline/replay rejection, invalid material
and language rejection, no local fallback, no-cache inventory/cursor retention,
unknown transferred-ID preservation and later metadata/durability migration.
Unit tests cover nonce generation/polling, bounded parsing, authentication,
assembly and atomic-cache failure paths.

Reload regressions cover a manual request before the normal five-second poll,
the proxy's one-second rate limit, an in-flight poll, bounded repeated commands,
confirmed update/current results, rejection/cache-write failure, no carrier,
disconnect, timeout, shutdown and lost ACK retention. Real Paper command probes
check backend permission denial, missing-carrier feedback, pending/busy replies,
paired activation confirmation and current/rejected results. Transport requests
remain seeded in this fixture; request generation and delayed sends use unit
tests. They do not prove connected-client Velocity transport.

Also run both original smoke.py modes so existing gameplay, readiness and the
issue19 network grant API remain covered. These fixtures are not connected-client
transport tests. Stage actual proxy/Purpur, Nexo/AuraSkills/resource packs,
first connection to an empty backend and real transfers between two backends.
Check /piv status for matching current digests before exercising new items.

Ordered-generation regressions also verify that future versions of an existing
ID cannot be refreshed, migrated or damaged with stale definitions. Full-storage
unknown/future equipment stays in place with bonuses suspended. A durable proxy
generation ledger fences rollback and conflicting content at the same generation;
source rollback is published as a fresh higher generation. Back up the proxy's
generation.dat and generation.initialized with its configuration.
