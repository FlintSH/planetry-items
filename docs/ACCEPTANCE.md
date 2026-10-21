# Validation

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
low-durability colouring and optional integration failure/cleanup.

Real Paper probes validate actual ItemStack components and serialization, empty
vanilla attributes, registry attributes, custom equipment, head texture properties,
oxygen PDC, use and equipment restrictions, custom durability, commands, full
inventory grant atomicity, reload retention and the optional bridges. They use
controlled Player implementations and Bukkit event dispatch. The command tests
briefly substitute a forwarding server lookup so controlled players can be found;
the original server is restored in a finally block. These are not network clients.

Before live installation, the operator should test on staging with the same
Paper/Purpur version, actual Nexo1.22+/resource pack, AuraSkills2.4.0, PlanetryCore,
Inventory Sync and protection plugins used by the network:

1. Give every configured item to two players with different game/skill levels.
   Check glyph fonts, models, head textures, small type labels, attribute colours,
   one blank between sections, hidden met requirements and red final durability.
2. Try both hands, shift/number/drag equipment moves, right-click equip, dispensers,
   reconnects, death/respawn and full storage. Confirm denied items give no passive
   modifiers and cannot be used; losing a requirement removes worn items safely.
3. Exercise melee/mining/protected blocks, food, bows/tridents, shields, wearable
   damage and item break effects. Confirm one use consumed, cancellations do not
   spend one, and AuraSkills stats return to their previous value after removal.
4. Transfer items between backends with matching definitions, restart them, and
   verify identity, oxygen and remaining durability survive. Ensure Nexo reloads
   do not replace Planetry metadata, and verify missing/loading integration data
   denies only the operations that depend on it.

Automated probes do not prove actual Nexo/AuraSkills binary compatibility,
client resource-pack rendering, arbitrary plugin event ordering, Purpur or
cross-backend transfers. They also do not authorize production installation.

Main CI uploads the tested JAR with `SHA256SUMS`. Release downloads must match
those tested artifact bytes, and the release tag must resolve to the tested full
commit SHA. No rebuild occurs in the release job.
