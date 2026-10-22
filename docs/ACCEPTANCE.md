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

Before live installation, the operator should test on staging with the same
Paper/Purpur version, actual Nexo1.22+/resource pack, AuraSkills2.4.0, PlanetryCore,
Inventory Sync and protection plugins used by the network:

1. Give every configured item to two players with different game/skill levels.
   Check glyph fonts, models, head textures, small type labels, attribute colours,
   one blank between sections, hidden met requirements and red final durability.
2. Try both hands, shift/number/drag equipment moves, right-click equip, dispensers,
   reconnects, death/respawn and full storage. Confirm denied items give no passive
   modifiers and cannot be used; losing a requirement moves worn items to storage.
   With storage full, ordinary equipment may drop; soulbound equipment must stay
   worn without native attributes or AuraSkills bonuses until space opens.
3. Exercise melee/mining/protected blocks, food, bows/tridents, shields, wearable
   damage and item break effects. Confirm one use consumed, cancellations do not
   spend one, and AuraSkills stats return to their previous value after removal.
4. Edit definitions in nested `items/` folders, reload, and try `/migrate` as a
   non-admin. Check current material/model, all info fields, the two-hour per-backend
   cooldown, durability preservation and safe stack splitting when space is limited.
   Delete a test definition, then rejoin and verify removal names that item and asks
   the player to contact staff. Use disposable test items and matching catalogs.
5. Transfer items between backends with matching definitions, restart them, and
   verify identity, oxygen and remaining durability survive. Ensure Nexo reloads
   do not replace Planetry metadata, and verify missing/loading integration data
   denies only the operations that depend on it.
6. Check omitted policy flags behave as `planetry-canplace: true` and
   `planetry-soulbound: false`. Test unquoted `true`/`false` at the item root and
   under `bonuses`; reject quoted strings, numbers, blank/null values and duplicate
   declarations across both locations without changing the active catalog. Check
   `/pitems info` shows both effective flags and neither adds automatic lore/stat
   lines. Keep the six example IDs unchanged when copying the staff catalog.
7. With `planetry-canplace: false`, test both hands for block placement, entity
   placement, hanging items and bucket contents. Every blocked placement must be
   silent and spend neither the item nor custom durability. Repeat with ordinary
   items and `planetry-canplace: true` as positive placement controls.
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
