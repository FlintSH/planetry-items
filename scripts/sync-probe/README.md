# Proxy sync acceptance fixture

Run the separately packaged probe against the release JAR with Java 25 and the
shared build guard:

```sh
python3 scripts/sync-smoke.py target/PlanetryItems-VERSION.jar --log-file /tmp/items-sync-paper.log
```

Maven must first produce `target/test-classpath.txt`. The runner checks the pinned
Paper 26.1.2 build 74 digest, compiles this directory separately, and starts two
owned server processes sequentially in one temporary loopback folder. Cold startup
checks missing-proxy preservation, signed multipart updates, rejection/replay,
future item migration and persistence across 15 assertion groups. Snapshot A is
generation 1 with a durability maximum of 100; B is generation 2 with a maximum
of 300. A known-ID item arriving from generation 2 with 200 remaining must retain
its metadata, generation and durability through stale refresh, direct migration,
guard reconciliation and interaction/native damage events. B migration restores
its active definition with 200 remaining. Separate full-storage players check
that unknown helmets and known future chest armor retain their worn slots and
quantities while native attributes are suspended. Their world fixtures count
and reject every drop callback.

Invalid content uses higher generations so material/language validation is
actually reached. Fresh authenticated requests separately reject an older
generation and a different snapshot at the accepted generation, preserving
catalog/language references, digest, generation and cache bytes. Restart checks
the generation-2 paired cache and both serialized item kinds without a carrier,
despite different local definitions/language, across 2 assertion groups. Both
phases need their own `ITEMS_SYNC_PROBE_OK` marker with the exact group count and
a clean server exit. `--log-file` preserves cold output and a sibling `.restart`
log.

The probe uses the real plugin startup, registered messenger callback, server
thread, ItemStacks, events and cache. Its sole production reflection seam seeds
`ProxyDefinitions.Pending(UUID, Player, long)` because controlled Player proxies
are absent from CraftServer's connected-player collection. Request generation
and carrier discovery remain covered by controller unit tests. No clients or
Velocity proxy connect, and this fixture does not establish real-client transfer,
actual optional-plugin, resource-pack or Purpur compatibility. The runner reads
no production configuration or player data, and fixture classes stay outside
the release JAR.
