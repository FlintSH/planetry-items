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
| `attributes` | Empty | Minecraft attribute map; numeric or structured modifiers |
| `bonuses` | Empty | AuraSkills bonuses, oxygen metadata, or legacy Minecraft attribute aliases |
| `requirements` | Empty | Minimum Core/AuraSkills levels |

Numbers must be YAML numbers, not quoted strings. NaN and infinity are invalid.
Boolean values must be booleans, not strings such as `'true'`. Material, type,
rarity, equipment slot, attribute operation and slot-group values accept
case-insensitive names; use the uppercase examples for consistency.

Missing/null `attributes`, `bonuses` and `requirements` sections add nothing.
Within those maps, missing/null/blank values omit an entry. Unknown bonus or
requirement keys still reject the file even when blank. A numeric `0` is an
explicit value; a zero requirement imposes no restriction. Other fields have
their own validation: a blank display name is invalid. Blank/null `nexo-id`
and `base64` disable those sources; null `custom-model-data` leaves it unset.

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
| `ADD_SCALAR` | `0.1` | Add a 10% base-value modifier |
| `MULTIPLY_SCALAR_1` | `0.1` | Apply a multiplicative factor of 1.1 |

Scalar modifiers display as percentages in lore. The resulting value also
depends on other active modifiers and Minecraft's attribute limits.

Slot groups are `ANY`, `HAND`, `MAINHAND`, `OFFHAND`, `ARMOR`, `HEAD`, `CHEST`,
`LEGS`, `FEET`, `BODY`. `HAND` covers both hands; `ARMOR` covers armour slots.
Equipment `OFF_HAND` defaults to attribute group `OFFHAND`. An attribute applies
only when its item's actual position matches the group.

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

## Bonuses

| Key under `bonuses` | Effect |
| --- | --- |
| `planetry-oxygen` | Nonnegative finite number stored as oxygen metadata; no consumption behavior |
| `auraskills-additional-wisdom` | Add AuraSkills Wisdom |
| `auraskills-additional-health` | Add AuraSkills Health |
| `auraskills-additional-regeneration` | Add AuraSkills Regeneration |
| `auraskills-additional-toughness` | Add AuraSkills Toughness |
| `auraskills-additional-strength` | Add AuraSkills Strength |
| `minecraft-<attribute>` | Legacy numeric Minecraft attribute alias described above |

AuraSkills values are finite numbers and may be negative. They add once per
active usable item, not once per piece in a stack. Nonwearables are active in
either hand; wearables only in their configured slot. Only listed bonuses
apply. The plugin uses its own nonpersistent modifier names and clears bonuses
on unequip, loss of requirements, logout or disable. Missing AuraSkills makes
these bonuses inactive.

## Requirements

| Key under `requirements` | Level source |
| --- | --- |
| `planetry-gamelevel` | PlanetryCore cached game level |
| `auraskills-required-farming` | AuraSkills Farming |
| `auraskills-required-foraging` | AuraSkills Foraging |
| `auraskills-required-mining` | AuraSkills Mining |
| `auraskills-required-fishing` | AuraSkills Fishing |
| `auraskills-required-fighting` | AuraSkills Fighting |

Levels are whole numbers 0–2147483647. Every positive requirement must be met.
Zero, blank and omitted entries impose none. Game level is unrelated to vanilla
XP. Missing, disabled, incompatible or unloaded sources deny positive
requirements; staff permissions provide no bypass.

Unmet requirements appear in the holder's lore and plain denial message.
Met requirement lines disappear. Players may carry/hold restricted items, but
cannot activate them or gain their attributes/bonuses. Actions and armour
equipment are checked, including shift-click, number keys, drag and dispensers.
Unusable worn items move to storage or drop beside the player if full.

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
source stack. Tridents transfer the remaining counter to the thrown item;
a break at launch cannot carry the destroyed item.

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
