# PlanetryItems staff guide

[Documentation home](../README.md) · [All item settings](CONFIGURATION.md) ·
[Troubleshooting](OPERATIONS.md#troubleshooting)

Staff create and edit items in YAML files, then use commands in-game or in the
backend console. There is no in-game creation GUI, save-held-item command or
delete command.

## Before you start

You need file access to `plugins/PlanetryItems/` and the backend permission
`planetry.admin.items`. Operators have it by default. Ask the operator to grant
that node through the server's permission manager rather than making every
item editor an operator. File access is managed separately.

Work on staging first. Back up the PlanetryItems folder before editing. The
operator should also back up player inventories before changes that remove
IDs or alter durability, material or stack size. Keep the same catalog on all
backends sharing inventories.

Fresh installs include these examples:

| ID | Purpose | Requirement to use |
| --- | --- | --- |
| `lunar_crystal` | Stackable material, no durability | None |
| `moonblade` | Sword with attributes and AuraSkills bonuses | Core game level 5 and AuraSkills Fighting 10 |
| `explorer_helmet` | Wearable PAPER with oxygen metadata | Core game level 2 |
| `keepsake` | Unbreakable cosmetic head | None |

Active files live below `items/`. On upgrade, the old root `items.yml` is copied
once to `items/imported.yml` if that directory did not exist. The root file
remains a backup and is then ignored. Editing that backup has no effect.

## Create your first item

1. Create `plugins/PlanetryItems/items/staff.yml`. Use UTF-8, spaces rather than
   tabs, and consistent indentation. Paste this **whole file**:

```yaml
schema-version: 1
items:
  staff_crystal:
    material: AMETHYST_SHARD
    display-name: 'Training Crystal'
    lore:
      - '<gray>A crystal for the staff training session.'
    rarity: RARE
    type: ITEM
    max-stack-size: 64
    max-durability: 0
```

2. Run `/pitems reload`. Continue only when it reports success. On failure, the
   console identifies the bad file/field and the previous configuration stays
   active. Follow [reload recovery](OPERATIONS.md#reload-and-recover).
3. Run `/pitems list` and check that `staff_crystal` appears.
4. Run `/pitems give YourExactOnlineName staff_crystal 1`, substituting your
   actual online name. Hold it in your main hand and run `/pitems info`.
5. Check the name, lore, rarity and stack size. The name is aqua because rarity
   is `RARE`. There should be no durability counter. Give a second crystal and
   check stacking.

`staff_crystal` is the permanent ID; the display name is what players see.
Change the name while keeping the ID. IDs are case-sensitive lowercase,
1–64 characters, starting with a letter or digit. Subsequent characters may
also be `_` or `-`.

Each file has one `items:` heading. Add entries alongside `staff_crystal`, at
the same indentation, rather than adding another `items:` heading. IDs must
be unique across all files and subfolders. Keep backups outside `items/`.

## Start from a complete example

[staff-items.yml](examples/items/staff-items.yml) contains six definitions:

| ID | What to learn |
| --- | --- |
| `staff_crystal` | Stackable material without durability |
| `staff_blade` | Attributes, scalar modifiers, level requirements and bonuses |
| `staff_helmet` | Wearable PAPER, oxygen metadata and armour |
| `staff_ration` | Ordinary single-use food |
| `staff_keepsake` | Unbreakable soulbound cosmetic head with placement disabled |
| `staff_nexo_pickaxe` | Nexo appearance and a skill requirement |

Copy the file into `items/`, or copy individual entries into an existing file.
**Do not also keep the first-item example above under the same ID.** Replace
the Nexo example's placeholder source ID with a real loaded Nexo item before
giving it. Positive level requirements need their corresponding integrations.

A sword's material alone does not grant its usual attack modifiers. PlanetryItems
clears material/Nexo attributes and applies only those configured. The example
blade adds `7` attack damage; that is an attribute modifier, not a promise of
seven total damage or seven hearts after player stats and other plugins.

For ordinary food, use an edible material and `max-durability: 0`. The default
100 would instead make it reusable for 100 uses. `type: FOOD` does not turn
PAPER into edible food. PAPER with `type: TOOL` does not gain pickaxe mechanics,
and a PAPER chest suit does not gain elytra flight.

## Edit an existing item

1. Find its ID with `/pitems info`, then locate the definition in `items/`.
   Record its remaining durability if you are changing durability.
2. Back up files outside the loaded folder. Keep the ID unchanged and edit
   the intended fields using the [reference](CONFIGURATION.md).
3. Test on staging with a pre-existing copy as well as a fresh item. Leave
   inventory space if reducing stack size.
4. Have the operator coordinate matching definitions on every participating
   backend. Reloading one backend does not distribute files to others.
5. Run `/pitems reload` on each updated backend. Success queues full inventory
   updates for online players once Inventory Sync is ready.
6. Compare fresh and existing copies with `/pitems info`. Check appearance,
   attributes, requirements and remaining durability.

Migration updates material, texture/model, name, lore, equipment, attributes
and bonuses. It preserves quantity, enchantments and unrelated persistent
data, including container/bundle contents, book text and stored enchantments.
It never repairs durability: raising the maximum leaves remaining points
unchanged; lowering it caps them. Reverting YAML does not restore lost points.
Changing from no durability to positive durability also needs testing on old
instances; a stored zero stays zero. See [durability changes](OPERATIONS.md#durability-changes).

Players can run `/migrate` to scan storage/hotbar, armour, offhand and cursor.
It has a two-hour cooldown after complete success and cannot target another
player. Automatic join/reload migration does not use that cooldown. Ender
chests, world containers and dropped items are outside the scan. Nested item
contents are preserved, rather than recursively migrated.

**Removing or renaming an ID removes its existing stacks** when inventories are
scanned or reconciled. This also happens after switching to a backend missing
the definition. The removal message uses the saved name and asks the player
to contact staff. Restoring an accidentally removed definition stops further
removals; it does not recreate deleted stacks. Recovery needs the operator's
inventory backup or a reviewed replacement grant. Keep retired IDs defined
until a removal and recovery plan is agreed.

## Add levels, bonuses and equipment

Positive `planetry-gamelevel` needs loaded PlanetryCore progress. Positive
`auraskills-required-*` needs loaded AuraSkills levels. Operator status and
the staff permission do not bypass requirements. Staff can give an item to
someone who does not qualify, but it remains unusable.

Players can hold restricted items in either hand and move them through
storage within their soulbound policy, if configured. Native attributes and
AuraSkills bonuses remain inactive. Activation,
attacking, mining, consumption, projectiles and armour-equipping are blocked.
If requirements are lost while wearing an item, it moves to storage, or drops
beside the player when storage is full. Soulbound equipment stays worn when
storage is full, without native attributes or AuraSkills bonuses, until space
becomes available.

Use `ARMOUR`, `SPACESUIT` or `COSMETIC` with `equip-slot: HEAD`, `CHEST`, `LEGS`,
`FEET` or `OFF_HAND`. Wearables must have stack size 1. Equipment `OFF_HAND`
is spelled differently from the attribute slot group `OFFHAND`. PAPER can be
equipped, but a worn model needs a resource pack. Oxygen is saved metadata;
PlanetryItems does not consume it or implement an oxygen survival system.

## Keep an item with its player

Set `planetry-canplace: false` to silently prevent world placement from either
hand without spending the item or custom durability. Set
`planetry-soulbound: true` to also block drops, world deposits and external
inventory writes. The player can still move it around their own storage,
equipment and cursor, and use their own ender chest. Safe retrieval from other
inventories remains available. Ordinary food/tool use still works; soulbound
tridents cannot be thrown. On death, only soulbound items and carriers
containing them are retained; ordinary items follow normal death rules.

Both flags accept only unquoted YAML `true` or `false`. Defaults are
`planetry-canplace: true` and `planetry-soulbound: false`. Put each at the item
root or under `bonuses`, once per flag; repeating a flag across both locations
rejects reload. They are policy flags with no automatic lore, not numeric
Minecraft attributes. The example `staff_keepsake` demonstrates both locations.
Bundles/container items with nested soulbound contents receive the same
protection. Read [the policy reference](CONFIGURATION.md#placement-and-soulbound-policies)
before applying it to existing items.

For a deliberate staff drop, `/pitems soulboundbypass` uses your existing staff
permission and is player-only. It defaults to off and resets on session
changes, reload and death. When enabled, the first explicit drop warns; repeat
the identical item and amount within two seconds to permit that drop once.
It does not permit external storage, placement or death drops, and grants no
requirements bypass. The exact warning and confirmation rules are in
[operations](OPERATIONS.md#commands-and-permissions).

## Change text and appearance

Item names and flavour lore go in the item definition. Shared type labels,
stat prefixes, requirement messages and rarity glyphs go in `lang.yml`. Use
MiniMessage such as `<gray>` and `<bold>`, not legacy `&` codes. Rarity sets
the entire name color, even if the name has another color tag.
[Language and appearance](LANGUAGE.md) lists the colors and templates.

`custom-model-data` needs a matching resource pack. `nexo-id` copies a loaded
Nexo item's appearance, without its gameplay, enchantments, name, lore or
updater identity. `base64` accepts a player-head texture property and cannot
be combined with `nexo-id`. See [models and textures](CONFIGURATION.md#models-and-textures).

## Check an item before approving it

- Give a fresh copy and update an older one. Inspect both with `/pitems info`.
- Compare players below and above each requirement. Met requirement lines
  disappear for the holder; unusable items give no passive bonuses.
- Test the configured slot, both hands where relevant, and unequipping.
- Test placement and soulbound flags with ordinary items as controls: own ender
  chest/retrieval must work; chest writes, drops and nested-carrier escapes must
  fail. Check death keeps only protected items and full storage retains unusable
  soulbound equipment without bonuses. Test the staff drop confirmation separately.
- Use a low-durability test copy. Check the red final 10%, one point per accepted
  action, and removal at zero. Unbreakable items have no durability line.
- Check actual Nexo/AuraSkills builds and the resource pack with a real client.
- With the operator, transfer items between staging backends with matching
  catalogs and Inventory Sync; check quantity, identity and durability.

Crafting and workstation result clicks involving managed items are blocked.
There is no built-in repair, recipe or enchant-management command.
See [acceptance checks](ACCEPTANCE.md) for the full staging checklist.
