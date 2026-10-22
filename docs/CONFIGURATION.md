# Item configuration reference

[Documentation home](../README.md) · [Staff walkthrough](STAFF_GUIDE.md) ·
[Language reference](LANGUAGE.md)

## Files and catalog structure

Definitions load recursively from `plugins/PlanetryItems/items/`. File names
and subfolders organize your work; they do not become part of the item ID.
Both `.yml` and `.yaml` are accepted, case-insensitively. Files load in relative
path order, with entries in each file retaining YAML order.

Every file must contain an `items:` mapping and may contain `schema-version: 1`.
For a file with no definitions, use `items: {}`. A blank file is invalid. An
existing empty directory intentionally defines no items; it is not repopulated
with bundled examples. A missing directory rejects reload. At startup, a
missing directory is initialized from legacy `items.yml` or bundled examples.
Do not delete `items/` as a recovery shortcut: restarting could activate a
different catalog and remove carried custom IDs.

IDs must match `[a-z0-9][a-z0-9_-]{0,63}`: 1–64 lowercase letters/digits,
underscores or hyphens, starting with a letter/digit. IDs are unique across
the whole directory. Duplicate YAML keys, unknown item fields, duplicate IDs,
collection aliases and symbolic links are rejected. Put backups outside the
loaded folder. Use UTF-8 and spaces for indentation.

The entire configuration activates together on `/pitems reload`. One bad file
retains the previous active config, catalog and language. On startup there is
no previous snapshot; invalid configuration disables the plugin. Reload also
queues full inventory migration. See [operations](OPERATIONS.md).

## Every item field

All item fields are optional. An empty definition such as `my_rock: {}` creates
an item using the defaults below. Names in this table are literal YAML keys.

| Field | Default | Accepted value and effect |
| --- | --- | --- |
| `material` | `STONE`, or `PLAYER_HEAD` when Base64 is set | Valid non-air Minecraft item material, for example `IRON_SWORD`; Nexo source material wins when used |
| `display-name` | Item ID | Nonempty MiniMessage string; rarity controls its final color |
| `lore` | No flavour lines | List of strings or a multiline string; MiniMessage supported |
| `custom-model-data` | Unset | Whole number 0–2147483647; overrides copied Nexo model data |
| `nexo-id` | Unset | Nonblank Nexo source item ID; cannot combine with `base64` |
| `base64` | Unset | Base64 texture property, maximum 16384 characters; requires `PLAYER_HEAD`; cannot combine with `nexo-id` |
| `rarity` | `COMMON` | One of the six rarities below |
| `type` | `ITEM` | One of the eight types below |
| `equip-slot` | `HEAD` for wearable types, otherwise unset | `HEAD`, `CHEST`, `LEGS`, `FEET`, `OFF_HAND`; wearable types only |
| `max-stack-size` | `1` | Whole number 1–99; wearables and breakable items must use 1 |
| `max-durability` | `100` | Whole number 0–2147483647; 0 disables the custom use counter |
| `unbreakable` | `false` | Boolean `true` or `false`; true never spends durability and hides the counter |
| `canplace` | `true` | Boolean policy flag; false silently prevents world placement |
| `soulbound` | `false` | Boolean policy flag; true keeps the item in the player's inventory or own ender chest and retains it on death |
| `attributes` | Empty | Minecraft attribute map; numeric or structured modifiers |
| `bonuses` | Empty | AuraSkills bonuses, oxygen metadata, legacy Minecraft attribute aliases, or either policy flag |
| `requirements` | Empty | Minimum Core/AuraSkills levels |

Numbers must be YAML numbers, not quoted strings. NaN and infinity are invalid.
Boolean values must be booleans, not strings such as `'true'`. Material, type,
rarity, equipment slot, attribute operation and slot-group values accept
case-insensitive names; use the uppercase examples for consistency.

Missing/null `attributes`, `bonuses` and `requirements` sections add nothing.
Within those maps, missing/null/blank numeric values omit an entry. Present
policy flags must be booleans, including when placed under `bonuses`. Unknown bonus or
requirement keys still reject the file even when blank. A numeric `0` is an
explicit value; a zero requirement imposes no restriction. Other fields have
their own validation: a blank display name is invalid. Blank/null `nexo-id`
and `base64` disable those sources; null `custom-model-data` leaves it unset.

## Placement and soulbound policies

Use unquoted YAML `true` or `false` for `canplace` and
`soulbound`. Quoted strings, numbers, blank/null values and other
values are invalid. Each flag may appear at the item's root or under
`bonuses`, once per item. Defining the same flag in both places rejects the
whole reload, even when the values agree. These are policy flags; do not put
them under Minecraft `attributes`. Neither creates numeric stats.
Soulbound adds the [configurable type-line suffix](LANGUAGE.md#formatting-and-lore-layout);
canplace adds no lore.

| Setting | `false` | `true` | Default |
| --- | --- | --- | --- |
| `canplace` | Block world placement silently | Allow normal placement, subject to soulbound and other restrictions | `true` |
| `soulbound` | No soulbound restrictions or suffix | Keep the item in the player's own inventory/ender chest, retain it on death and show the suffix | `false` |

For example, these settings belong inside an existing item definition:

```yaml
canplace: false
bonuses:
  soulbound: true
```

### Upgrade existing flags

Rename `planetry-canplace` to `canplace` and `planetry-soulbound` to
`soulbound`, keeping the same boolean value and indentation. The old names
remain accepted for existing catalogs. Use **one spelling in one location**
for each flag. Old and new spellings together reject the whole reload, even
if their values agree; remove the old entry when adding its replacement.
Other keys such as `planetry-oxygen` and `planetry-gamelevel` keep their names.

Upgrade the JAR on every participating backend **before** adopting the new
YAML names; older plugin versions do not recognize the short names.
Run `/pitems reload` on each backend with matching definitions, then check
`/pitems info`: its detail lines are `canplace: ...` and `soulbound: ...`.
The rename preserves persistent item data and existing policies. Editing an
existing catalog does not require deleting items or changing their IDs.

### What the flags protect

`canplace: false` silently prevents placing the item into the world
from either hand, including blocks, entity placements, hanging items and bucket
contents. A blocked attempt consumes neither the item nor custom durability.
Omitting the flag defaults to `true`; it does not override soulbound protection.

`soulbound: true` prevents external inventory writes, explicit drops,
world deposits and placement, even when `canplace` is true. Players
can move it within their own storage/hotbar, equipment and cursor, and into or
out of their own ender chest. Chests, workstations and other entities cannot
receive it. Ordinary retrieval from an external inventory remains allowed
when the move does not send a soulbound item back. Safe moves of ordinary
items also remain available while carrying soulbound items.
This protects whichever player currently holds it; it does not permanently
bind the item to a player's UUID. It does not make the item unbreakable.

Bundles and container items containing soulbound items receive the same
protection, including nested contents; wrapping an item cannot permit an
external transfer, drop or placement. With normal death drops enabled, only
soulbound items and their protected carriers are retained; ordinary items
follow normal death rules. Soulbound protection does not grant extra uses:
ordinary food consumption and tool use remain valid, subject to requirements
and durability. Throwing a soulbound trident is blocked to avoid losing it.

Staff may enable the limited, confirmed drop override described in
[commands and permissions](OPERATIONS.md#commands-and-permissions). It does
not permit external storage, placement or death drops, and never bypasses
requirements.

## Rarities and types

| Rarity | Name color | Default rarity glyph |
| --- | --- | --- |
| `COMMON` | Gray `#AAAAAA` | `tag_common` |
| `UNCOMMON` | Green `#55FF55` | `tag_uncommon` |
| `RARE` | Aqua `#55FFFF` | `tag_rare` |
| `EPIC` | `#9554EA` | `tag_epic` |
| `LEGENDARY` | `#FFBF00` | `tag_legendary` |
| `MYTHICAL` | Red `#FF5555` | `tag_mythical` |

Rarity owns all display-name colors, including nested text and glyphs. Bold,
italic and font formatting remain. The rarity lore template and its fallback
are editable in `lang.yml`; changing them does not change name-color rules.

Types are `ITEM`, `TOOL`, `WEAPON`, `ARMOUR`, `FOOD`, `COSMETIC`, `ADDON`,
`SPACESUIT`. Spell `ARMOUR` with a U. Only `ARMOUR`, `COSMETIC`, `SPACESUIT`
are wearable types and accept `equip-slot`. BODY is an animal slot and cannot
be used as player `equip-slot`.

The type supplies a lore label and participates in use handling. It does not
create food nutrition, custom abilities, recipes, mining mechanics or oxygen
consumption. Use a material with the required native behavior. Native
equipping allows PAPER in a helmet slot; its worn visual needs a resource pack.

## Minecraft attributes

Only explicitly listed modifiers apply. Native material/Nexo attribute
defaults are cleared, including when `attributes` is omitted or empty. A
modifier adds to the player's base stat and other modifiers; it is not the
final resulting damage, armour or health value.

A numeric value means `ADD_NUMBER`, in the wearable's slot or `MAINHAND` for
nonwearables. Structured values have `amount`, optional `operation` and optional
`slot`. `amount` is required and must be finite; negative values are allowed.

| Operation | Example amount | Meaning |
| --- | --- | --- |
| `ADD_NUMBER` | `3` | Add 3 units to the attribute |
| `ADD_SCALAR` | `0.1` | Add 10% of the base plus all flat `ADD_NUMBER` modifiers |
| `MULTIPLY_SCALAR_1` | `0.1` | Apply a multiplicative factor of 1.1 |

Scalar modifiers display as percentages in lore. The resulting value also
depends on other active modifiers and Minecraft's attribute limits.
Minecraft first adds the flat modifiers, applies the sum of `ADD_SCALAR`
values, then multiplies by each `1 + MULTIPLY_SCALAR_1` amount. For example,
base 10, flat +2, scalar +0.1 and multiplicative +0.2 yield 15.84 before
attribute limits. See the [Paper attribute calculation](https://github.com/PaperMC/Paper/blob/ver/26.1.2/paper-server/patches/sources/net/minecraft/world/entity/ai/attributes/AttributeInstance.java.patch).

Slot groups are `ANY`, `HAND`, `MAINHAND`, `OFFHAND`, `ARMOR`, `HEAD`, `CHEST`,
`LEGS`, `FEET`, `BODY`. `HAND` covers both hands; `ARMOR` covers armour slots.
Equipment `OFF_HAND` defaults to attribute group `OFFHAND`. An attribute applies
only when its item's actual position matches the group.
`ANY` covers matching equipped/held slots; it does not activate an item in
ordinary backpack storage. Unlike AuraSkills bonuses, a nonwearable native
modifier defaults to the main hand only; use `HAND` to cover both hands.

Use registered keys such as `minecraft:attack_damage`, `minecraft:attack_speed`,
`minecraft:armor`, `minecraft:armor_toughness`, `minecraft:max_health`,
`minecraft:movement_speed` or `minecraft:scale`. The running server's attribute
registry is validated, so unknown keys reject reload. Explicit `minecraft:`
keys use registry spelling. Shorthand `attack-speed` becomes
`minecraft:attack_speed`. Future registered attributes can be used without a
new hardcoded list.

Legacy numeric aliases under `bonuses`, such as `minecraft-armor: 3`, become
`minecraft:armor` with `ADD_NUMBER` in the default slot. Do not configure the
same normalized attribute twice, including across `attributes` and `bonuses`.
The [example blade](examples/items/staff-items.yml) shows both flat and scalar
modifiers.

### Available Minecraft attributes

These are all 35 attributes exposed by the pinned
[Paper 26.1.2 build 74 API](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/keys/AttributeKeys.html).
Use the exact key below inside `attributes`. Each accepts a finite numeric
modifier or the structured form shown after the table. The server controls
which entities and mechanics use an attribute: accepting a modifier does not
grant a new ability. Only the `minecraft:` namespace is supported.

| Key under `attributes` | What it changes |
| --- | --- |
| `minecraft:armor` | Armour points used in damage reduction |
| `minecraft:armor_toughness` | Armour damage mitigation against stronger hits; not item durability |
| `minecraft:attack_damage` | Attack damage in health points; 2 points equal one heart |
| `minecraft:attack_knockback` | Knockback strength dealt by attacks |
| `minecraft:attack_speed` | Attack cooldown recovery rate, in attacks per second |
| `minecraft:block_break_speed` | Overall block-breaking speed multiplier |
| `minecraft:block_interaction_range` | Block interaction reach, in blocks |
| `minecraft:burning_time` | Duration multiplier for burning |
| `minecraft:camera_distance` | Third-person camera distance, in blocks before entity scale |
| `minecraft:entity_interaction_range` | Entity interaction reach, in blocks |
| `minecraft:explosion_knockback_resistance` | Resistance to explosion knockback |
| `minecraft:fall_damage_multiplier` | Fall damage multiplier |
| `minecraft:flying_speed` | Flight movement for entities that use this attribute; does not grant player flight |
| `minecraft:follow_range` | Mob AI target-following range; not player reach |
| `minecraft:gravity` | Downward acceleration |
| `minecraft:jump_strength` | Upward jump velocity |
| `minecraft:knockback_resistance` | Resistance to ordinary knockback |
| `minecraft:luck` | Luck used by loot tables that support it; not AuraSkills Luck |
| `minecraft:max_absorption` | Maximum absorption health, in health points; does not grant absorption by itself |
| `minecraft:max_health` | Maximum health, in health points; 2 points equal one heart |
| `minecraft:mining_efficiency` | Additional mining speed with a suitable tool |
| `minecraft:movement_efficiency` | Movement efficiency through slowing terrain |
| `minecraft:movement_speed` | Native movement speed; raw values are not blocks per second |
| `minecraft:oxygen_bonus` | Underwater air-loss reduction; separate from stored `planetry-oxygen` metadata |
| `minecraft:safe_fall_distance` | Fall distance before damage starts, in blocks |
| `minecraft:scale` | Entity size multiplier |
| `minecraft:sneaking_speed` | Movement speed factor while sneaking |
| `minecraft:spawn_reinforcements` | Reinforcement-spawn chance for relevant mobs; not a player summon ability |
| `minecraft:step_height` | Height an entity can step up, in blocks |
| `minecraft:submerged_mining_speed` | Mining speed factor while submerged |
| `minecraft:sweeping_damage_ratio` | Damage ratio for sweeping attacks |
| `minecraft:tempt_range` | Mob AI temptation range; not a player attraction ability |
| `minecraft:water_movement_efficiency` | Movement efficiency through water |
| `minecraft:waypoint_receive_range` | Range at which a player receives locator-bar waypoints |
| `minecraft:waypoint_transmit_range` | Range at which an entity transmits locator-bar waypoints |

For example, inside a weapon definition:

```yaml
attributes:
  minecraft:attack_damage: 7
  minecraft:attack_speed: -2.4
  minecraft:movement_speed:
    amount: 0.1
    operation: ADD_SCALAR
    slot: HAND
```

This gives flat attack modifiers in `MAINHAND` and a +10% movement modifier
in either hand. Base values and other active modifiers still affect the
result. The table describes native attributes, not extra AuraSkills stats.

## Bonuses

| Key under `bonuses` | Effect |
| --- | --- |
| `planetry-oxygen` | Nonnegative finite number stored as oxygen metadata; no consumption behavior |
| `auraskills-additional-wisdom` | Add Wisdom stat points, normally affecting experience gain, mana capacity and anvil costs |
| `auraskills-additional-health` | Add Health stat points; AuraSkills determines the resulting maximum health |
| `auraskills-additional-regeneration` | Add Regeneration stat points, normally affecting health and mana regeneration |
| `auraskills-additional-toughness` | Add Toughness stat points, used by AuraSkills for damage reduction |
| `auraskills-additional-strength` | Add Strength stat points, used by AuraSkills for attack damage |
| `minecraft-<attribute>` | Legacy numeric Minecraft attribute alias described above |
| `canplace`, `soulbound` | Boolean policy flags described above; each may instead be at the item root |

AuraSkills values are finite numbers and may be negative. They add once per
active usable item, not once per piece in a stack. Nonwearables are active in
either hand; wearables only in their configured slot. Only listed bonuses
apply. The plugin uses its own nonpersistent modifier names and clears bonuses
on unequip, loss of requirements, logout or disable. Missing AuraSkills makes
these bonuses inactive.

This is the complete numeric bonus list. AuraSkills Luck, Speed and critical
stats are not supported as `auraskills-additional-*` keys.
These are AuraSkills **stat points**, not raw health points, hearts or a fixed
percentage. AuraSkills' own configuration determines their final effects;
see the [official AuraSkills stat and trait reference](https://wiki.aurelium.dev/auraskills/stats/).
They are separate from native Minecraft attributes; setting one does not
configure the other. Wisdom's experience bonus affects vanilla Minecraft XP,
not PlanetryCore game XP or AuraSkills skill XP. PlanetryCore's default
vanilla-XP/anvil restrictions can prevent those parts of Wisdom taking effect;
mana behavior remains controlled by AuraSkills.
Oxygen stores a number in `planetryitems:oxygen` item data, without defined
seconds/air units, breathing, capacity, consumption or refill logic.

For example, inside an item definition:

```yaml
bonuses:
  planetry-oxygen: 100
  auraskills-additional-wisdom: 2
  auraskills-additional-health: 1
  auraskills-additional-regeneration: 1
  auraskills-additional-toughness: 1
  auraskills-additional-strength: 3
```

## Requirements

| Key under `requirements` | Level source |
| --- | --- |
| `planetry-gamelevel` | Minimum loaded PlanetryCore game level |
| `auraskills-required-farming` | Minimum loaded AuraSkills Farming level |
| `auraskills-required-foraging` | Minimum loaded AuraSkills Foraging level |
| `auraskills-required-mining` | Minimum loaded AuraSkills Mining level |
| `auraskills-required-fishing` | Minimum loaded AuraSkills Fishing level |
| `auraskills-required-fighting` | Minimum loaded AuraSkills Fighting level |

Levels are whole numbers 0–2147483647. Every positive requirement must be met.
Zero, blank and omitted entries impose none. Game level is unrelated to vanilla
XP. Missing, disabled, incompatible or unloaded sources deny positive
requirements; staff permissions provide no bypass.

This is the complete supported requirement list; other AuraSkills skills and
stats cannot be used as requirement keys. Requirements are minimums, so level
10 satisfies a configured value of 10. They do not grant or spend XP/levels.
For example, this item needs **all six** minimums, not any one of them:

```yaml
requirements:
  planetry-gamelevel: 5
  auraskills-required-farming: 10
  auraskills-required-foraging: 10
  auraskills-required-mining: 10
  auraskills-required-fishing: 10
  auraskills-required-fighting: 10
```

Unmet requirements appear in the holder's lore and plain denial message.
Met requirement lines disappear. Players may carry/hold restricted items, but
cannot activate them or gain their attributes/bonuses. Actions and armour
equipment are checked, including shift-click, number keys, drag and dispensers.
Unusable worn items move to storage or drop beside the player if full, except
soulbound equipment: when storage is full it stays worn without native
attributes or AuraSkills bonuses until space becomes available.

## Models and textures

Choose one visual source:

- Material plus optional `custom-model-data`. A number alone does not supply
  a model: clients need a matching resource pack.
- `nexo-id` referring to a loaded source item. The source's material, item
  model, custom model data, head profile, dye, trim and equipment appearance
  are copied. PlanetryItems owns gameplay metadata. It does not import Nexo
  identity/updater data, name, lore, enchantments, attributes or custom gameplay.
  A configured `custom-model-data` overrides copied model data.
- `base64` containing the **texture property's Base64 value**, not a texture
  URL or a full `/give` command. It needs `PLAYER_HEAD` (also the default when
  this field is set). It must decode to nonempty Base64 bytes; syntactic
  acceptance does not guarantee that the client can render the supplied texture.

`base64` and `nexo-id` cannot both be set. Nexo sources are resolved at item
creation/migration time, so a successful reload does not prove the source is
available. Missing/loading sources fail a grant and retain the original stack
during migration. Source visual changes reach existing items on full migration,
not ordinary periodic refresh. See [Nexo operation details](OPERATIONS.md#optional-integrations).

## Durability, stacking and native behavior

Breakable items (`max-durability > 0`, `unbreakable: false`) must stack to 1.
Items with no counter or unbreakable items can stack up to 99; wearables still
require 1. Custom durability is independent of the material's native durability.
Native item damage/repair cannot overwrite it, and vanilla Unbreaking/Mending
does not extend/refill the custom counter.

One point is spent on an accepted melee hit with damage, block break, activated
right-click, entity interaction, projectile use or applicable wearing-damage
event. Gliding spends one point per second on the managed chest item. Shields
also spend a point on fully blocked hits. Cancelled/protected actions do not
spend one; food, equipment and ranged items use their completion-specific paths
instead of spending twice on the initial click. Bow ammunition remains separate.

Food/potions with positive durability return the item with one fewer use;
zero durability uses normal single-use consumption; unbreakable is reusable.
Reusable thrown consumables use their custom counter instead of consuming the
source stack. Non-soulbound tridents transfer the remaining counter to the
thrown item; a break at launch cannot carry the destroyed item. Soulbound
tridents cannot be thrown.

The final 10%, with at least the final point, uses the low-durability template.
At exhaustion the item disappears with normal break animation/particles and
sound. Unbreakable items show neither a custom counter nor a vanilla
Unbreakable tooltip. There is no repair/refill command.

Crafting, anvil, smithing, grindstone and stonecutter result clicks involving
managed items are blocked to preserve identity. This is not a recipe or
automation system. Use ordinary materials as recipe ingredients.

## Runtime setting

`plugins/PlanetryItems/config.yml` currently has one setting:

| Key | Default | Accepted values |
| --- | --- | --- |
| `refresh-interval-ticks` | `20` | Integer 1–1200 |

At normal server speed 20 ticks is one second. This controls periodic
inventory reconciliation, holder-specific lore and active AuraSkills bonuses.
It reloads with `/pitems reload`. Readiness is checked before inventory changes.
Gliding durability has its own once-per-second check. Full automatic migration
failures use a five-second retry delay.

Soulbound arrows cannot be fired or loaded into crossbows because another player
could pick them up. Crossbows already containing protected ammunition cannot
fire or be transferred externally; their stored ammunition stays intact. A
soulbound bow/crossbow with ordinary ammunition remains usable. Protected bundles
cannot unload contents through right-click use; move contents within your own
inventory instead.
