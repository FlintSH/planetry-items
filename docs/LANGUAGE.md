# Language and appearance

[Documentation home](../README.md) · [Staff guide](STAFF_GUIDE.md) ·
[Item settings](CONFIGURATION.md)

`plugins/PlanetryItems/lang.yml` controls shared labels, messages and lore
templates. Item-specific `display-name` and flavour `lore` belong in the item
definition. One language file is active per backend; there is no per-player
language selection or PlaceholderAPI expansion in PlanetryItems.

The file overrides bundled defaults, so a partial translation is valid.
Omitted keys use bundled text. Templates must be strings, optionally grouped
in mappings; lists and numeric/boolean messages are invalid. An empty quoted
string is allowed. Keep `schema-version: 1` and avoid duplicate keys.

## Formatting and lore layout

Use MiniMessage, for example `<gray>`, `<#9554EA>`, `<bold>` or `<italic>`.
Legacy `&a` color codes are not translated. Quote templates in YAML, especially
when they contain `:`, `#`, braces or leading tags. Text is nonitalic by
default unless you explicitly request italics.

Lore sections appear in this fixed order:

1. Type label.
2. Flavour lore from the item.
3. Configured Minecraft attributes, then bonuses.
4. Unmet requirements.
5. Rarity.
6. Durability, when breakable with a positive counter.

Nonempty sections have one blank line between them. Durability follows rarity
without an extra section gap. Flavour lore trims leading/trailing blank lines
but retains interior blank lines. Met requirements disappear for the holder.
Removing a requirement's text does not remove its gameplay restriction.
`planetry-canplace` and `planetry-soulbound` add no automatic lore or stat
lines, including when configured under `bonuses`. Add explanatory flavour
lore yourself if players should see those policies.

Rarity sets the complete display-name color: COMMON gray, UNCOMMON green,
RARE aqua, EPIC `#9554EA`, LEGENDARY `#FFBF00`, MYTHICAL red. Color tags in
names, including glyph colors, are overridden. Other formatting remains.
Changing `rarities.*` only changes the rarity lore line.

## Template groups

| Key/group | Purpose | Available substitutions |
| --- | --- | --- |
| `types.item`, `.tool`, `.weapon`, `.armour`, `.food`, `.cosmetic`, `.addon`, `.spacesuit` | First lore line | None |
| `attributes.default` | Fallback stat line | `{attribute}`, `{value}`, `{unit}` |
| `attributes.<normalized-key>` | Specific stat line | `{attribute}`, `{value}`, `{unit}` |
| `requirements.default`, `requirements.<key>` | Decorated unmet requirement lore | `{requirement}`, `{value}` |
| `requirement-labels.default`, `requirement-labels.<key>` | Plain labels in denial messages and info | `{requirement}`, `{value}` |
| `rarities.common`, `.uncommon`, `.rare`, `.epic`, `.legendary`, `.mythical` | Rarity lore | None |
| `glyph-fallbacks.<glyph-id>` | Text when no working native glyph is supplied | No dynamic values |
| `lore.durability`, `lore.durability-low` | Normal/final-10% durability | `{current}`, `{max}` |
| `messages.<key>` | Command/event text | See below |

Minecraft attribute language keys replace `:` and `_` with `-`:
`minecraft:attack_damage` uses `attributes.minecraft-attack-damage`. Bonus keys
keep their spelling, for example `attributes.auraskills-additional-strength`
and `attributes.planetry-oxygen`. Requirement keys also keep their spelling,
for example `requirements.auraskills-required-mining`.

Stat `{value}` is signed, such as `+3` or `-2.4`. Scalar modifiers multiply
the displayed value by 100 and use `{unit}` `%`; flat modifiers and bonuses
have an empty unit. `{attribute}` and `{requirement}` are readable names
derived from the key. Requirement `{value}` is the configured minimum, not
the player's current level.

For plain denial labels, use `requirement-labels.*` rather than removing
decorations from lore. Labels are converted to plain text and joined with
commas. `messages.requirements-not-met` is the active denial template. The
older `messages.requirements` key is retained for compatibility. The exact
old bundled denial wording falls back to the new default on load; customized
translations remain.

## Message substitutions

These are keys below `messages:`. Braced values are only available in the
listed message context; they are not global variables.

| Key | Substitutions / meaning |
| --- | --- |
| `usage` | None; management command usage |
| `no-permission` | None |
| `player-only` | None |
| `unknown-item` | `{item}` |
| `player-not-found` | `{player}` |
| `invalid-amount` | None |
| `inventory-full` | `{player}` |
| `given`, `received` | `{player}`, `{item}`, `{amount}` |
| `reloaded` | None |
| `reload-failed` | `{error}`; generic instruction to check the console |
| `requirements-not-met`, legacy `requirements` | `{item}` (ID), `{requirements}` (plain unmet labels) |
| `workstation-blocked` | None |
| `list` | `{items}` (comma-separated IDs) |
| `disabled-item` | None |
| `integration-unavailable` | None |
| `no-item` | None |
| `info` | See detailed info values below |
| `migrate-usage` | None |
| `migrate-success`, `migrate-partial` | `{updated}`, `{removed}`, `{failed}`; counts of stacks, not pieces |
| `migrate-cooldown` | `{remaining}`; rounded-up hours/minutes |
| `removed-item` | `{item}`; saved display name, or ID when unnamed |
| `soulbound-bypass-enabled` | None; staff player's drop override is now on |
| `soulbound-bypass-disabled` | None; staff player's drop override is now off |
| `soulbound-drop-warning` | None; first explicit drop while the override is enabled; repeat the identical item/amount within two seconds to confirm once |

The `info` header can use `{id}`, `{name}`, `{material}`, `{configured-material}`,
`{rarity}`, `{type}`, `{model}`, `{nexo}`, `{base64}`, `{equip}`, `{amount}`,
`{stack}`, `{current}`, `{max}`, `{unbreakable}`, `{planetry-canplace}`,
`{planetry-soulbound}`, `{attributes}`, `{bonuses}`,
`{requirements}`, `{lore}`, `{usable}`. Effective material/model come from the
held stack; most other fields describe its active definition. The command also
prints fixed plain detail lines so an old customized header cannot hide settings.
Those detail lines are not separate language templates.

The bundled `messages.soulbound-drop-warning` is exactly:

> This item is Soulbound, are you sure you want to drop it? Toggle this bypass with /planetryitems soulboundbypass

Existing customized `messages.usage` text should include `soulboundbypass`.
Translating these messages does not change the confirmation window or grant
external storage, placement, death-drop or requirements bypasses.

Dynamic values are inserted as literal components. A player's name or an item
value containing MiniMessage or glyph tags cannot execute them. A token with
no value in that context remains literal. Item name/flavour templates have no
general command-value substitutions.

## Nexo glyphs and spacing

Names, flavour lore and every language template rendered by PlanetryItems
support native Nexo tags when a compatible Nexo is enabled. Use a glyph ID
such as `<glyph:heart>`, not copied raw Unicode. Nexo supplies the correct font.
Native options are forwarded, for example
`<glyph:heart:2..3:colorable:shadow:#80ffffff>`; `<shift:-8>` adjusts spacing.
Glyph IDs must use 1–128 letters, digits, underscores, dots or hyphens. Use IDs
and options supported by your installed Nexo build.

The item viewer/message recipient is the MiniMessage target, allowing current
Nexo to check glyph/shift permissions. Older player-aware resolver shapes are
also supported. Console rendering uses the global resolver. With a working
hook, Nexo owns unknown-ID and denied-permission behavior, including its
`required` glyph or a literal denied tag.

When Nexo is missing, disabled, incompatible, throws, or supplies an empty
glyph, `glyph-fallbacks.<id>` supplies ordinary MiniMessage text. Without a
fallback, the unavailable glyph produces no text. Unavailable shifts also
produce no text. Fallback text does not recursively expand glyph/shift tags.
An invalid glyph tag can reject reload.

This is a complete **partial override** example for `lang.yml`; merge its keys
into existing sections instead of adding duplicate YAML headings:

```yaml
schema-version: 1
attributes:
  planetry-oxygen: '<aqua>Oxygen <white>{value}'
requirement-labels:
  auraskills-required-mining: 'Mining Level {value}'
rarities:
  rare: '<glyph:tag_rare>'
glyph-fallbacks:
  tag_rare: '<aqua>RARE'
messages:
  requirements-not-met: '<red>You cannot use this item: {requirements}!'
  received: '<green>Received {amount} × {item}.'
```

Run `/pitems reload` after editing. Successful reload queues migration for
online inventories. `/migrate` is also available subject to its normal cooldown.
Installing/updating Nexo or its resource pack is a separate operator workflow
and plugin changes need a backend restart. This integration controls text
rendered by PlanetryItems; other plugins own their chat, scoreboard and tablist.

For Nexo's own options, see its official
[glyph documentation](https://docs.nexomc.com/configuration/glyphs).
