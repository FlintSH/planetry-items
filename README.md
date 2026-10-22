# PlanetryItems

Configurable custom items for [Planetry](https://github.com/FlintSH/planetry).
Runs on **Paper/Purpur 26.1.2 with Java 25**, on each gameplay backend.
No database or proxy plugin is required. Spigot and Folia are not supported.

Download a JAR and `SHA256SUMS` from [Releases](https://github.com/FlintSH/planetry-items/releases).
Main CI tests the packaged JAR on pinned Paper before publishing those exact bytes.
Releases remain prereleases while the full production plugin combination awaits staging.

## Documentation

| I want to… | Read |
| --- | --- |
| Create, edit, test and give items as staff | [Staff guide](docs/STAFF_GUIDE.md) |
| Look up every item field, default, attribute, bonus and requirement | [Configuration reference](docs/CONFIGURATION.md) |
| Change names, lore, messages, rarity glyphs or Nexo text | [Language and appearance](docs/LANGUAGE.md) |
| Install, upgrade, reload, migrate or diagnose a problem | [Operations and troubleshooting](docs/OPERATIONS.md) |
| Build the plugin or use its API | [Developer guide](docs/DEVELOPMENT.md) |
| Copy complete, validated item definitions | [Example catalog](docs/examples/items/staff-items.yml) |
| Check automated coverage and staging requirements | [Acceptance checks](docs/ACCEPTANCE.md) |

Staff edit YAML files with a text editor; there is no in-game creation GUI.
**Keep existing IDs stable and catalogs identical on every backend sharing
inventories.** Removing or renaming an ID removes existing stacks when player
inventories update. Read the staff guide before editing a live catalog.

## Install and configure

1. Stop the backend, add the JAR to its `plugins` folder and start it.
2. Edit YAML files in `plugins/PlanetryItems/items/` to define items and `lang.yml` to change
   names, prefixes, messages, types, rarity glyphs and durability text.
3. Run `/planetryitems reload`. Invalid files retain the previous active catalog.
   Plugin installation or changes to optional plugins require a server restart.
4. Keep matching item definitions and integrations on every backend that shares
   inventories. Released code does not install itself on the live network.

Optional integrations:

| Plugin | Used for | When unavailable |
| --- | --- | --- |
| [Nexo](https://docs.nexomc.com/version-support), 1.22+ for Minecraft 26.1.2 | Item appearance and rarity glyphs | Nexo item creation fails until its item is loaded; rarity uses editable text fallbacks |
| [AuraSkills 2.4.0](https://github.com/Archy-X/AuraSkills/releases/tag/2.4.0) | Skill levels and equipment stat bonuses | Positive skill requirements deny use; AuraSkills bonuses are inactive |
| [PlanetryCore](https://github.com/FlintSH/planetry-core) | Cached game level | Positive game-level requirements deny use until progress is loaded |
| [NetworkInventorySync](https://github.com/FlintSH/network-inventory-sync) | Inventory readiness | If installed, disabled/incompatible/unready sessions cannot receive items or have their inventory refreshed |

Nexo loads items asynchronously. A missing/loading item can be given again after
Nexo finishes loading. PlanetryItems copies the source item's material and visual
components (item model, custom model data, head profile, dye, trim and equipment
appearance). It does not import its identity, updater, enchantments, attributes,
name, lore or custom gameplay. This keeps Nexo updates from replacing Planetry's
metadata. Visual changes to a source Nexo item apply on creation and inventory migration.
See [Nexo's API documentation](https://docs.nexomc.com/community-guides/api).

## Commands and permission

All management commands require `planetry.admin.items` (operators by default).
The old `planetryitems.admin` permission no longer grants access.
Players do not need this permission to use items or meet their requirements.
There is no requirements bypass permission.

| Command | Result |
| --- | --- |
| `/planetryitems list` | List configured IDs |
| `/planetryitems give <online-player> <id> [amount]` | Give 1–2304 items; a full inventory receives no partial grant |
| `/planetryitems info` | Show all held-item settings, effective model/material, attributes, bonuses and requirements |
| `/planetryitems soulboundbypass` | Staff player only; toggle the confirmed soulbound drop override, off by default |
| `/migrate` | Public, player-only inventory update; no permission; two-hour cooldown |
| `/planetryitems reload` | Validate and reload all item and language files together |

`/pitems` is an alias. The soulbound drop override uses the same
`planetry.admin.items` permission and resets on session changes, reload and
death. While enabled, the first explicit drop warns; a second drop of the
identical item and amount within two seconds permits that drop once. It
does not allow external storage, placement or death drops, or bypass item
requirements. See [the warning and command details](docs/OPERATIONS.md#commands-and-permissions).

Back up the item definitions before changing IDs or durability.
Migration rebuilds definition-owned material, texture, model, name, lore, equipment,
attributes and bonuses while preserving quantity, enchantments and unrelated persistent
data, container/bundle contents, book text and stored enchantments. Existing items keep their remaining durability, capped to a new maximum;
migration never repairs them. Player storage, armour, offhand and cursor are scanned;
ender chests and unopened containers are outside this command's scope.

Joining each backend (including a server switch) automatically migrates the inventory
after Inventory Sync reports ready. Reload queues the same update for online players.
Automatic updates do not consume the manual command's cooldown. `/migrate` stores its
last successful use in player persistent data, surviving reconnects/restarts on that
backend. The cooldown is per backend, not a shared network database. An unavailable
inventory does not consume it. A partial update also leaves the command available.

Removed IDs are deleted during migration/reconciliation. Each removed stack sends
its saved display name and asks the player to contact staff if removal is a mistake.
Keep matching catalogs on every backend: moving to a backend missing an ID removes
that item. Back up definitions and player data before intentionally deleting IDs.
If a changed stack size needs more storage, migration splits it only when all extra
stacks fit. Otherwise the stack stays intact and the result reports an incomplete
update. Missing Nexo sources also retain the original item. Automatic session updates retry
every five seconds until the missing source or storage space becomes available.

## Item definitions

Fresh installs create `items/examples.yml` with a stackable crystal, sword, paper
spacesuit helmet and unbreakable cosmetic head. YAML files (`.yml` or `.yaml`) are
loaded recursively, so `items/weapons/swords.yml` and `items/spacesuits/helmets.yml`
can organize definitions. Each file uses the same `items:` structure below.
IDs must be unique across the entire directory; one invalid file rejects the reload.
An empty folder intentionally defines no items. Symbolic links are rejected.

On the first upgrade, an existing root `items.yml` is copied to `items/imported.yml`.
The root file remains as a backup and is ignored after the directory exists. Edit the
imported file or move its entries into subfolders, rather than editing the old backup.
Existing directories are never repopulated with examples or imported again.

Add entries under `items`:

```yaml
schema-version: 1
items:
  explorer_helmet:
    material: PAPER
    display-name: '<#ADD8E6>Explorer Helmet'
    lore:
      - '<gray>Made for the next expedition.'
      - '<gray>Keep an eye on your oxygen.'
    custom-model-data: 1002
    # nexo-id: your_nexo_item_id
    # base64: '...' # PLAYER_HEAD only; choose either Nexo or Base64
    rarity: LEGENDARY
    type: SPACESUIT
    equip-slot: HEAD
    max-stack-size: 1
    max-durability: 500
    unbreakable: false
    attributes:
      minecraft:armor: 3
      minecraft:scale:
        amount: 0.1
        operation: ADD_SCALAR
        slot: HEAD
    bonuses:
      planetry-oxygen: 100
      auraskills-additional-wisdom: 2
    requirements:
      planetry-gamelevel: 5
      auraskills-required-mining: 10
```

`material` defaults to STONE (PLAYER_HEAD when Base64 is configured). Nexo's
source material wins when `nexo-id` is used. Explicit custom model data overrides
copied Nexo custom model data. Names and lore support MiniMessage; rarity overrides every name color, including
explicit color tags. Common is gray, Uncommon green, Rare aqua, Epic `#9554ea`,
Legendary `#FFBF00` and Mythical red. Other name formatting is retained. `lore` accepts
a list or a multiline YAML string. Duplicate keys, unsupported settings, invalid
numbers and unknown registered Minecraft attributes reject the whole reload.

Rarities: `COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY`, `MYTHICAL`.
Their default Nexo glyphs are `tag_common`, `tag_uncommon`, `tag_rare`, `tag_epic`,
`tag_legendary` and `tag_mythical`. Edit their templates and text fallbacks in
`lang.yml`. The native Nexo resolver preserves the glyph's configured font.

Nexo tags work in item `display-name`, flavour lore and all `lang.yml` templates,
including type/attribute/requirement prefixes, rarities and command messages.
Use `<glyph:heart>` with your actual Nexo glyph ID rather than copying its raw
Unicode. Native options are forwarded to Nexo, for example
`<glyph:heart:2..3:colorable:shadow:#80ffffff>`, and `<shift:-8>` adjusts spacing.
See [Nexo's glyph documentation](https://docs.nexomc.com/configuration/glyphs).
The item viewer or message recipient is passed as MiniMessage's target, so
current Nexo can check glyph/shift permissions. Older player-aware resolver APIs
are supported too; console output uses the global resolver. Display names still
use PlanetryItems' rarity color, including glyphs.

If Nexo is missing, disabled, incompatible, throws or returns an empty glyph,
`glyph-fallbacks.<id>` in `lang.yml` supplies ordinary MiniMessage text. Without a
fallback, the glyph produces no text; unavailable shift tags are also empty.
With a working hook, Nexo owns unknown-ID and permission behavior, including its
`required` glyph or literal denied tag. Fallback text does not expand
further glyph/shift tags. Dynamic `{values}` remain literal and cannot inject tags.
After editing templates, run `/pitems reload`; use `/migrate` to refresh existing
items subject to its normal cooldown. Installing or updating Nexo requires a backend restart.
This integration applies to text rendered by PlanetryItems. Other plugins manage
their own tablist, scoreboard and chat formatting.

Types: `ITEM`, `TOOL`, `WEAPON`, `ARMOUR`, `FOOD`, `COSMETIC`, `ADDON`, `SPACESUIT`.
Wearable types support `HEAD`, `CHEST`, `LEGS`, `FEET` or `OFF_HAND`, defaulting to
`HEAD`. Paper's native equippable component lets materials such as PAPER occupy
the configured slot. A worn visual model still needs an appropriate resource pack.
Only `ARMOUR`, `COSMETIC` and `SPACESUIT` items can occupy worn armour slots,
and only their configured slot. Other custom items, including player heads and
items based on vanilla armour, remain holdable but cannot be worn.

All registered Minecraft attributes can be configured, including newer attributes.
A number means `ADD_NUMBER`; structured entries support `ADD_NUMBER`, `ADD_SCALAR`
and `MULTIPLY_SCALAR_1`. For scalar operations `0.1` means 10%. Attribute slot groups
are `MAINHAND`, `OFFHAND`, `HAND`, `HEAD`, `CHEST`, `LEGS`, `FEET`, `ARMOR`, `BODY`
and `ANY`; the default is the wearable slot or `MAINHAND`. The attributes apply
only while the item occupies a matching slot. Material/Nexo attribute defaults are
explicitly removed, including when `attributes` is empty. A blank entry is omitted.

The legacy-style bonus alias `minecraft-armor: 3` under `bonuses` means
`minecraft:armor: 3` under `attributes`; other `minecraft-*` attribute names work
the same way. Do not configure the same normalized attribute twice.

`canplace` (default `true`) and `soulbound` (default `false`)
are boolean policy flags. Use unquoted `true` or `false` at the item root or
under `bonuses`, once per flag; duplicates across both locations reject reload.
Old `planetry-canplace` and `planetry-soulbound` remain compatible aliases;
replace the old entry instead of adding a second spelling. Upgrade the JARs
before using short names. See [upgrade steps](docs/CONFIGURATION.md#upgrade-existing-flags).
They do not create numeric stats. Soulbound adds the configurable type-line
indicator described [below](#lore-and-durability); canplace adds no lore.
`canplace: false` silently blocks world placement from either hand,
including blocks, entities, hanging items and buckets, without consuming the
item or durability. Soulbound items also cannot be placed, dropped or written
to external inventories; own storage, equipment, cursor and ender chest remain
available, as does safe retrieval from external inventories. Nested bundles
and container carriers receive the same protection. Death retains only the
soulbound items and protected carriers; ordinary items follow normal death
rules. Ordinary food/tool use remains valid, but soulbound tridents cannot be
thrown. See [the full policy reference](docs/CONFIGURATION.md#placement-and-soulbound-policies).

AuraSkills bonuses: `auraskills-additional-wisdom`, `-health`, `-regeneration`,
`-toughness`, `-strength`. Only listed values apply, while held (nonwearables) or
worn in the configured slot and usable by that player. They add once per active
item, not per stack count. Modifiers use only PlanetryItems' own names and are
nonpersistent, so they cannot remain in AuraSkills storage after a crash.
They are removed on unequip, loss of requirements, logout or plugin disable.

`planetry-oxygen` is metadata for future use. It is stored as a DOUBLE in item
persistent data at `planetryitems:oxygen`; this release does not consume oxygen.
Item identity is STRING `planetryitems:item_id`, remaining durability is INTEGER
`planetryitems:durability`. Normal Paper item serialization and Inventory Sync
preserve these values. This is persistent item NBT, not a separate database.

Requirements accept `planetry-gamelevel` and `auraskills-required-farming`,
`-foraging`, `-mining`, `-fishing`, `-fighting`. Missing/blank values impose no
requirement; 0 imposes none. Core game level comes from its progress cache,
not vanilla XP. Missing, disabled, incompatible or unloaded level sources deny
positive requirements. Denied use says `You cannot use this item: <requirements>!`; decorative prefixes
remain in lore only. Requirement labels and lore have separate language templates.
Players may hold restricted items in either hand and move them through storage
within their soulbound policy, if configured.
Their native attributes and AuraSkills bonuses remain inactive while unusable. If
a cursor still carries old native bonuses after requirements change, its first drag
is safely cancelled while the cursor is refreshed; the next drag can hold it normally.
Requirements
block activation, attacks, mining, consumption, projectiles and armour equipment routes
including shift-click, number keys, drag, hand swap and dispensers. Restored
inventories are reconciled when ready; unusable worn items move to storage, or
drop beside the player if storage is full. Soulbound equipment instead stays
worn without native attributes or AuraSkills bonuses until storage space opens.
A periodic refresh updates level changes
and holder-specific requirement lore (`refresh-interval-ticks`, default 20).
Meet a requirement and its lore line disappears for the holder.

## Lore and durability

Lore is ordered as type, flavour text, listed attributes, unmet requirements,
rarity, durability. Nonempty sections have one separating blank line; an item
with no optional sections has only one blank between type and rarity. All types,
attribute prefixes, requirement lines, rarity output and durability messages are
editable in `lang.yml`. Values appear on separate lines, with white signed values
by default. Dynamic message substitutions cannot inject MiniMessage formatting.

Soulbound items show ` • ѕᴏᴜʟʙᴏᴜɴᴅ` after the type on the same line, with the
label in `#B589D6`. Ordinary items omit the suffix and separator. The suffix is
editable through `lore.soulbound` in `lang.yml`; existing language files receive
the default automatically. See [language settings](docs/LANGUAGE.md).

Items default to 100 durability and stack size 1. Set `max-durability: 0` for no
counter, or `unbreakable: true` to retain an item without spending durability. Unbreakable
items show neither a durability counter nor an Unbreakable lore/vanilla tooltip line.
Breakable items require stack size 1; nondamageable/unbreakable items can stack
up to 99 (wearables remain size 1). Durability is tracked independently of the vanilla material. Native
damage and repair cannot silently overwrite it.

One point is spent for each accepted melee hit, block break, completed tool
action (such as shearing, tilling or stripping), projectile use or wearing
damage event, as applicable. Clicking air, blocks or entities alone spends nothing.
Gliding spends one point per second on a managed chest item; a shield also spends
a point on fully blocked hits.
Food with positive durability returns the same item with one fewer use; food at
zero durability is a normal single-use consumable. Unbreakable food is reusable.
Durability is displayed as `Durability: current/max` and through Minecraft's
normal durability bar, using the same custom maximum and remaining value on
ordinary breakable items, including paper-based ones. Bundles retain Minecraft's
fullness bar; their custom durability remains in lore. The final 30% (at least
the last point) is red.
Exhaustion removes the item and sends the normal equipment
break animation/particles and sound. A break on projectile launch stops that
projectile from carrying a destroyed item. This is a use counter, not vanilla
Unbreaking/Mending durability. Protected/cancelled actions do not spend a point.
Pig/strider control sticks follow vanilla break conversion into an ordinary
fishing rod, with no custom item identity carried into that replacement.

Player crafting, anvil, smithing, grindstone and stonecutter result clicks using
managed items are blocked to preserve identity. This is not an automation or
recipe system. Configure separate ordinary materials as recipe ingredients.

## Development and integration

Java 25:

```sh
mvn -B -ntp clean verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
python3 scripts/smoke.py target/planetry-items-1.0.0.jar
python3 scripts/smoke.py target/planetry-items-1.0.0.jar --without-hooks
```

The plugin has no bundled third-party libraries. Paper supplies Adventure,
SnakeYAML and Guava. Optional APIs are accessed through compatibility-checked
bridges, without bundling other plugins' implementation classes.

Future Planetry plugins can obtain `PlanetryItems` from Bukkit's plugin manager
and use `items().create(id, player)`, `definition(stack)`, `unmet(player, def)`,
`usable(player, stack)`, `refresh(stack, player)`, `migrate(stack, player)` and
`damage(player, stack, slot)`. `guard().migrate(player)` applies a complete inventory
scan and returns updated/removed/failed stack counts; callers must check readiness.
Declare a dependency/soft dependency and make these calls on the server thread.
Check Inventory Sync readiness immediately before modifying player inventories.
`create` builds an item; it does not grant it or bypass requirements.

See [acceptance checks](docs/ACCEPTANCE.md) for what automated probes prove and
what still needs real-client staging. Feature requests belong in GitHub issues;
only the account authorized by Planetry's central numeric identity policy can
trigger automated development.
