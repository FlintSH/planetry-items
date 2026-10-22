package dev.flint.planetry.items.syncprobe;

import com.google.common.collect.HashMultimap;
import dev.flint.planetry.items.ItemCatalog;
import dev.flint.planetry.items.ItemLanguage;
import dev.flint.planetry.items.PlanetryItems;
import dev.flint.planetry.items.sync.ProxyDefinitions;
import dev.flint.planetry.items.sync.SnapshotCache;
import dev.flint.planetry.items.sync.SyncProtocol;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Packaged plugin lifecycle and real Paper items, with controlled player/transport fixtures. */
public final class ItemsSyncProbe extends JavaPlugin {
    private static final String FUTURE = "future_tool";
    private static final String ACTIVE = "sync_tool";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private PlanetryItems plugin;
    private ProxyDefinitions controller;
    private Fake player;
    private String secret;
    private String phase;
    private String stage = "startup";
    private int groups;
    private boolean finished;
    private byte[] coldInventory;
    private byte[] coldCursor;
    private String instance;
    private Fake futurePlayer;
    private String futureInstance;

    @Override public void onEnable() {
        phase = System.getProperty("planetry.syncProbe.phase", "");
        after(1, () -> {
            plugin = (PlanetryItems) Bukkit.getPluginManager().getPlugin("PlanetryItems");
            check(plugin != null && plugin.isEnabled(), "Packaged PlanetryItems did not enable");
            check(Bukkit.isPrimaryThread(), "Probe did not start on server thread");
            check(plugin.proxyManagedDefinitions(), "definitions-source proxy did not activate");
            controller = plugin.proxyDefinitions();
            check(controller != null, "Production controller missing");
            secret = plugin.getConfig().getString("proxy-sync.shared-secret");
            check(secret != null && secret.length() >= 32, "Disposable fixture key missing");
            check(Bukkit.getMessenger().getIncomingChannelRegistrations(plugin, SyncProtocol.CHANNEL)
                    .stream().anyMatch(registration -> registration.getListener() == controller),
                    "Production controller is not registered with Paper messenger");
            check(Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, SyncProtocol.CHANNEL),
                    "Production outgoing channel is not registered");
            check(Bukkit.getOnlinePlayers().isEmpty(), "Unexpected connected players in disposable server");
            controller.requestNow();
            check(!controller.requestPending(), "Empty server invented a player carrier");
            player = new Fake(UUID.randomUUID());
            switch (phase) {
                case "cold" -> cold();
                case "restart" -> restart();
                default -> throw new IllegalStateException("Unknown acceptance phase");
            }
        });
        after(1200, () -> { throw new IllegalStateException("Probe exceeded 1200 ticks"); });
    }

    private void cold() throws Exception {
        stage = "cold proxy lifecycle excludes local files and waits without a carrier";
        check(!controller.ready() && controller.currentDigest().isEmpty() && plugin.proxyDefinitionsPending(),
                "Cold proxy backend became ready without a snapshot/cache");
        check(plugin.catalog().definitions().isEmpty(), "Cold proxy loaded local definitions");
        check(!language().startsWith("LOCAL"), "Cold proxy loaded local language");
        check(!Files.exists(cachePath()), "Cold proxy unexpectedly created a cache");
        check(!plugin.hooks().inventoryReady(player.player), "Pending definitions allow inventory mutation");
        passed();

        stage = "reload permission and empty-server feedback retain cold definitions";
        reload();
        check(player.messages.stream().anyMatch(text -> text.contains("permission")) && !controller.requestPending(),
                "Unprivileged reload started sync");
        player.admin = true;
        reload();
        check(player.messages.stream().anyMatch(text -> text.contains("needs a player")) && !controller.ready()
                && !Files.exists(cachePath()), "Empty-server reload pretended to synchronize");
        passed();

        stage = "missing proxy preserves managed storage and cursor during scheduled reconciliation";
        instance = UUID.randomUUID().toString();
        player.contents[0] = transferred(instance);
        player.cursor = transferred(instance + "-cursor");
        coldInventory = player.contents[0].serializeAsBytes();
        coldCursor = player.cursor.serializeAsBytes();
        plugin.guard().beginSession(player.player);
        plugin.guard().reconcile(player.player);
        expectNotReadyMigration();
        check(!plugin.items().usable(player.player, player.contents[0]), "Unknown pending item became usable");
        assertUseDenied(player.contents[0]);
        check(plugin.networkItemIds("").isEmpty(), "Cold proxy advertises local item IDs to network commands");
        check(!plugin.giveNetworkItem(player.id, "local_only", 1).equals("SUCCESS"),
                "Cold proxy network grant used a local fallback definition");
        assertColdPreserved();
        // Let the actual scheduled join/refresh work observe missing definitions before delivery.
        after(25, () -> {
            assertColdPreserved();
            check(plugin.proxyDefinitionsPending(), "Missing proxy silently enabled fallback definitions");
            passed();
            transportFences();
            firstSnapshot();
            unknownTransfer();
            unknownArmorRetention();
            futureGenerationRetention();
            rejectedSnapshots();
            futureActivation();
            generationFences();
            replayAndCurrent();
            manualReloadResults();
            persistRestartEvidence();
            succeed();
        });
    }

    private void expectNotReadyMigration() {
        boolean denied = false;
        try { plugin.guard().migrate(player.player); }
        catch (IllegalStateException expected) { denied = true; }
        check(denied, "Direct migration bypassed pending definitions");
    }

    private void assertColdPreserved() {
        check(Arrays.equals(coldInventory, player.contents[0].serializeAsBytes()), "Pending storage item changed/deleted");
        check(Arrays.equals(coldCursor, player.cursor.serializeAsBytes()), "Pending cursor item changed/deleted");
    }

    private void transportFences() throws Exception {
        stage = "registered transport rejects wrong carrier, nonce, offline session, expired request and bad MAC";
        SyncProtocol.Snapshot snapshot = snapshot("A", false);
        UUID nonce = seed(player, System.nanoTime());
        List<SyncProtocol.Packet> packets = SyncProtocol.chunks(nonce, snapshot);
        // A different object with the same player UUID represents a different connection/session.
        Fake replacedSession = new Fake(player.id);
        for (var packet : packets) receive(replacedSession, packet);
        for (var packet : SyncProtocol.chunks(UUID.randomUUID(), snapshot)) receive(player, packet);
        byte[] invalidMac = SyncProtocol.encode(packets.getFirst(), secret);
        invalidMac[invalidMac.length - 1] ^= 1;
        receive(player, invalidMac);
        player.online = false;
        for (var packet : packets) receive(player, packet);
        player.online = true;
        check(!controller.requestPending() && !controller.ready() && player.outgoing.isEmpty(),
                "Disconnected request remained pending or committed data");
        check(plugin.catalog().definitions().isEmpty() && !Files.exists(cachePath()), "Unauthenticated data activated");
        seed(player, System.nanoTime() - 11_000_000_000L);
        receive(player, packets.getFirst());
        check(!controller.requestPending() && !controller.ready(), "Expired transport request accepted data");
        passed();
    }

    private void firstSnapshot() throws Exception {
        stage = "signed multipart over 32K activates and persists one matching catalog/language pair";
        SyncProtocol.Snapshot snapshot = snapshot("A", false);
        UUID nonce = seed(player, System.nanoTime());
        reload();
        check(player.messages.stream().anyMatch(text -> text.contains("Syncing"))
                && player.messages.stream().noneMatch(text -> text.contains("Synced")),
                "Reload reported success before proxy activation");
        reload();
        check(player.messages.stream().anyMatch(text -> text.contains("already waiting")),
                "Repeated reload did not bound command waiters");
        player.messages.clear();
        var chunks = SyncProtocol.chunks(nonce, snapshot);
        check(SyncProtocol.encodeSnapshot(snapshot).length > 32_767 && chunks.size() >= 2,
                "Multipart fixture did not cross the plugin-message size boundary");
        ItemCatalog oldCatalog = plugin.catalog();
        ItemLanguage oldLanguage = plugin.language();
        // Out-of-order and duplicate chunks are real signed wire messages, not direct snapshot calls.
        receive(player, chunks.getLast());
        receive(player, chunks.getLast());
        check(plugin.catalog() == oldCatalog && plugin.language() == oldLanguage && !controller.ready()
                && !Files.exists(cachePath()) && player.outgoing.isEmpty(), "Partial snapshot changed active/cache state");
        check(player.messages.stream().noneMatch(text -> text.contains("Synced proxy") || text.contains("already match")),
                "Reload reported success before the final multipart chunk");
        for (int index = 0; index < chunks.size() - 1; index++) receive(player, chunks.get(index));
        expectAck(player, nonce, "OK", SyncProtocol.digest(snapshot));
        assertActive(snapshot, "A", false);
        check(player.messages.stream().anyMatch(text -> text.contains("Synced proxy item definitions")),
                "Reload did not report accepted proxy definitions");
        ItemStack created = plugin.items().create(ACTIVE, player.player);
        check(created.getType() == Material.IRON_PICKAXE && plain(created.getItemMeta().displayName()).equals("Proxy A Tool"),
                "Proxy definitions did not create real matching Paper metadata");
        check(plugin.items().currentDurability(created) == 100 && attributes(created, 6),
                "Proxy item lost configured custom durability/attributes");
        check(itemGeneration(created) == 1, "New proxy item did not stamp accepted generation 1");
        check(!plugin.items().canPlace(created), "Proxy item policy flag was not activated");
        check(!plugin.proxyDefinitionsPending() && plugin.hooks().inventoryReady(player.player),
                "Snapshot did not release definition readiness gate");
        passed();
    }

    private void unknownTransfer() {
        stage = "newer transferred managed IDs are retained, suspended and unusable before their definition";
        plugin.guard().reconcile(player.player);
        var migration = plugin.guard().migrate(player.player);
        check(migration.failed() >= 2 && migration.removed() == 0, "Unknown proxy items were deleted or migration pretended success");
        assertSuspended(player.contents[0], instance);
        assertSuspended(player.cursor, instance + "-cursor");
        check(!plugin.items().usable(player.player, player.contents[0]), "Unknown transferred item remained usable");
        assertUseDenied(player.contents[0]);
        // The only allowed difference before a definition is available is its attribute suspension.
        ItemStack expected = ItemStack.deserializeBytes(coldInventory);
        expected.editMeta(meta -> meta.setAttributeModifiers(HashMultimap.create()));
        check(expected.equals(player.contents[0]), "Unknown item lost instance metadata beyond attribute suspension");
        passed();
    }

    private void unknownArmorRetention() {
        stage = "unknown worn armor remains suspended in its slot with full storage and no world drop";
        Fake wearer = fullStoragePlayer();
        String armorInstance = UUID.randomUUID().toString();
        wearer.contents[39] = transferred(armorInstance).withType(Material.DIAMOND_HELMET);
        wearer.contents[39].setAmount(3);
        ItemStack expected = suspended(wearer.contents[39]);
        ItemStack[] storage = Arrays.copyOf(wearer.contents, 36);
        check(wearer.inventory.firstEmpty() == -1, "Armor retention fixture storage is not full");
        plugin.guard().reconcile(wearer.player);
        check(expected.equals(wearer.contents[39]), "Reconciliation moved/changed unknown full-storage helmet");
        var result = plugin.guard().migrate(wearer.player);
        check(result.failed() == 1 && result.updated() == 0 && result.removed() == 0,
                "Unknown full-storage helmet migration was not deferred");
        check(expected.equals(wearer.contents[39]) && wearer.contents[39].getAmount() == 3,
                "Migration lost unknown helmet slot, quantity or foreign metadata");
        assertSuspended(wearer.contents[39], armorInstance);
        check(Arrays.equals(storage, Arrays.copyOf(wearer.contents, 36)) && wearer.worldDrops == 0,
                "Unknown helmet displaced storage or invoked a world drop");
        assertUseDenied(wearer, wearer.contents[39]);
        passed();
    }

    private void futureGenerationRetention() {
        stage = "known generation-2 item is preserved while cached generation-1 gameplay remains ready";
        check(controller.currentGeneration() == 1 && plugin.proxyGeneration() == 1
                && plugin.catalog().get(ACTIVE).maxDurability() == 100,
                "Future generation fixture did not start against A1/max100");
        futurePlayer = fullStoragePlayer();
        futureInstance = UUID.randomUUID().toString();
        ItemStack future = futureKnown(futureInstance);
        futurePlayer.contents[0] = future;
        futurePlayer.contents[38] = futureKnown(futureInstance + "-chest").withType(Material.DIAMOND_CHESTPLATE);
        futurePlayer.cursor = futureKnown(futureInstance + "-cursor");
        ItemStack raw = future.clone();
        check(plugin.hooks().inventoryReady(futurePlayer.player) && !plugin.proxyDefinitionsPending(),
                "Last-good generation-1 cache incorrectly disabled all gameplay");
        check(plugin.items().definition(future) == null && !plugin.items().usable(futurePlayer.player, future),
                "Known future generation item used stale definition/rules");
        plugin.items().refresh(future, futurePlayer.player);
        check(raw.equals(future), "Direct stale refresh downgraded generation-2 instance");
        boolean deferred = false;
        try { plugin.items().migrate(future, futurePlayer.player); }
        catch (IllegalArgumentException expected) { deferred = true; }
        check(deferred && raw.equals(future), "Direct stale migration failed to reject without mutation");
        check(!plugin.items().damage(futurePlayer.player, future, EquipmentSlot.HAND) && raw.equals(future),
                "Direct stale action damage changed future durability/generation");
        assertUseDenied(futurePlayer, future);
        var damage = new PlayerItemDamageEvent(futurePlayer.player, future, 1, 1);
        Bukkit.getPluginManager().callEvent(damage);
        check(damage.isCancelled() && raw.equals(future), "Native damage guard changed future instance");
        ItemStack expectedHand = suspended(future);
        ItemStack expectedChest = suspended(futurePlayer.contents[38]);
        ItemStack expectedCursor = suspended(futurePlayer.cursor);
        ItemStack[] storage = Arrays.copyOf(futurePlayer.contents, 36);
        storage[0] = expectedHand;
        plugin.guard().reconcile(futurePlayer.player);
        var result = plugin.guard().migrate(futurePlayer.player);
        check(result.failed() == 3 && result.updated() == 0 && result.removed() == 0,
                "Stale guard migration did not defer all future instances");
        check(expectedHand.equals(futurePlayer.contents[0]) && expectedChest.equals(futurePlayer.contents[38])
                && expectedCursor.equals(futurePlayer.cursor),
                "Stale guard changed future slot, quantity, generation, durability or foreign metadata");
        check(Arrays.equals(storage, Arrays.copyOf(futurePlayer.contents, 36)) && futurePlayer.worldDrops == 0,
                "Stale guard displaced full storage or invoked a world drop");
        assertKnownFuturePreserved(futurePlayer.contents[0], futureInstance);
        assertKnownFuturePreserved(futurePlayer.contents[38], futureInstance + "-chest");
        assertKnownFuturePreserved(futurePlayer.cursor, futureInstance + "-cursor");
        // The new definition is a tool. Move this test-only worn instance to storage
        // before B activates, so the later assertion isolates generation migration.
        futurePlayer.contents[1] = futurePlayer.contents[38];
        futurePlayer.contents[38] = null;
        passed();
    }

    private Fake fullStoragePlayer() {
        Fake owner = new Fake(UUID.randomUUID());
        for (int slot = 0; slot < 36; slot++) owner.contents[slot] = new ItemStack(Material.STONE, 64);
        return owner;
    }

    private static ItemStack suspended(ItemStack original) {
        ItemStack expected = original.clone();
        expected.editMeta(meta -> meta.setAttributeModifiers(HashMultimap.create()));
        return expected;
    }

    private void rejectedSnapshots() throws Exception {
        stage = "invalid Paper material retains last-good pair, digest and durable cache";
        SyncProtocol.Snapshot valid = snapshot("B", true);
        reject(new SyncProtocol.Snapshot(3, utf8(items("B", true).replace("IRON_PICKAXE", "WATER")), valid.language()));
        passed();
        stage = "invalid language and item render retain last-good pair, digest and durable cache";
        reject(new SyncProtocol.Snapshot(4, valid.items(), utf8("rarities:\n  common: '<glyph>'\n")));
        reject(new SyncProtocol.Snapshot(5, utf8(items("B", true).replace("Proxy B Tool", "<glyph>")), valid.language()));
        passed();
    }

    private void reject(SyncProtocol.Snapshot invalid) throws Exception {
        State before = state();
        UUID nonce = seed(player, System.nanoTime());
        for (var packet : SyncProtocol.chunks(nonce, invalid)) receive(player, packet);
        expectAck(player, nonce, "REJECTED", SyncProtocol.digest(invalid));
        unchanged(before);
        check(controller.ready() && !plugin.proxyDefinitionsPending(), "Rejected update disabled a last-good backend");
    }

    private void generationFences() throws Exception {
        stage = "fresh authenticated request rejects a completed lower-generation snapshot";
        reject(snapshot("A", false));
        passed();
        stage = "fresh authenticated request rejects different content at the active generation";
        var active = snapshot("B", true);
        var fork = new SyncProtocol.Snapshot(2, active.items(), utf8(
                "schema-version: 1\nmessages:\n  sync-probe: 'Proxy revision FORK'\n"));
        check(!SyncProtocol.digest(fork).equals(controller.currentDigest()), "Fork fixture matches accepted content");
        reject(fork);
        passed();
    }

    private void futureActivation() throws Exception {
        stage = "newer paired snapshot restores transferred items with instance data and durability intact";
        SyncProtocol.Snapshot snapshot = snapshot("B", true);
        UUID nonce = seed(player, System.nanoTime());
        for (var packet : SyncProtocol.chunks(nonce, snapshot)) receive(player, packet);
        expectAck(player, nonce, "OK", SyncProtocol.digest(snapshot));
        assertActive(snapshot, "B", true);
        var result = plugin.guard().migrate(player.player);
        check(result.removed() == 0 && result.failed() == 0 && result.updated() >= 2,
                "Future definition did not migrate both retained items");
        assertRestored(player.contents[0], instance);
        assertRestored(player.cursor, instance + "-cursor");
        check(plugin.items().usable(player.player, player.contents[0]), "Future item remains unusable after definition activation");
        passed();

        stage = "generation-2 same-ID migration retains 200 remaining against new maximum 300";
        check(plugin.catalog().get(ACTIVE).maxDurability() == 300, "B2 did not activate the larger durability maximum");
        var knownResult = plugin.guard().migrate(futurePlayer.player);
        check(knownResult.removed() == 0 && knownResult.failed() == 0 && knownResult.updated() == 3,
                "Generation-2 definition did not migrate the retained same-ID instances");
        assertKnownRestored(futurePlayer.contents[0], futureInstance);
        assertKnownRestored(futurePlayer.contents[1], futureInstance + "-chest");
        assertKnownRestored(futurePlayer.cursor, futureInstance + "-cursor");
        check(futurePlayer.worldDrops == 0, "Successful future migration invoked a world drop");
        ItemStack created = plugin.items().create(ACTIVE, futurePlayer.player);
        check(itemGeneration(created) == 2 && plugin.items().currentDurability(created) == 300,
                "New B item did not stamp accepted generation/max durability");
        passed();
    }

    private void replayAndCurrent() throws Exception {
        stage = "replayed old snapshots are fenced and authenticated CURRENT acknowledges without replacing state";
        State before = state();
        UUID nonce = seed(player, System.nanoTime());
        var oldPackets = SyncProtocol.chunks(nonce, snapshot("A", false));
        var current = new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, nonce, controller.currentDigest(), 0, 0, new byte[0]);
        receive(player, current);
        expectAck(player, nonce, "OK", before.digest());
        for (var packet : oldPackets) receive(player, packet);
        check(player.outgoing.size() == 1, "Consumed nonce generated replay acknowledgements");
        unchanged(before);
        UUID fresh = seed(player, System.nanoTime());
        for (var packet : oldPackets) receive(player, packet);
        check(player.outgoing.isEmpty() && controller.requestPending(), "Stale nonce completed a new request");
        unchanged(before);
        receive(player, new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, fresh, before.digest(), 0, 0, new byte[0]));
        expectAck(player, fresh, "OK", before.digest());
        unchanged(before);
        passed();
    }

    private void reload() {
        player.messages.clear();
        plugin.getCommand("planetryitems").execute(player.player, "pitems", new String[]{"reload"});
    }

    private void manualReloadResults() throws Exception {
        stage = "reload confirms CURRENT only after authenticated response";
        State before = state();
        UUID nonce = seed(player, System.nanoTime());
        reload();
        check(player.messages.size() == 1 && player.messages.getFirst().contains("Syncing"),
                "Reload pretended the proxy was already current");
        receive(player, new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, nonce, before.digest(), 0, 0, new byte[0]));
        check(player.messages.stream().anyMatch(text -> text.contains("already match the proxy")
                && text.contains(before.digest().substring(0, 12))), "Reload did not confirm matching proxy revision");
        unchanged(before);
        passed();

        stage = "reload rejection reports failure and retains the accepted pair";
        nonce = seed(player, System.nanoTime());
        reload();
        var invalid = new SyncProtocol.Snapshot(3, utf8(items("B", true)), utf8("messages: {bad: [invalid]}"));
        for (var chunk : SyncProtocol.chunks(nonce, invalid)) receive(player, chunk);
        check(player.messages.stream().anyMatch(text -> text.contains("were rejected"))
                && player.messages.stream().noneMatch(text -> text.contains("Synced proxy")),
                "Reload did not report rejected proxy data accurately");
        unchanged(before);
        expectAck(player, nonce, "REJECTED", SyncProtocol.digest(invalid));
        passed();
    }

    private void persistRestartEvidence() throws Exception {
        stage = "durable cache contains the final pair and real transferred item survives serialization";
        SyncProtocol.Snapshot expected = snapshot("B", true);
        var cached = SnapshotCache.load(cachePath()).orElseThrow();
        check(Arrays.equals(SyncProtocol.encodeSnapshot(expected), SyncProtocol.encodeSnapshot(cached)),
                "Durable cache is not the exact final pair");
        Files.createDirectories(getDataFolder().toPath());
        Files.writeString(getDataFolder().toPath().resolve("expected.digest"), controller.currentDigest(), StandardCharsets.UTF_8);
        Files.write(getDataFolder().toPath().resolve("expected.snapshot"), SyncProtocol.encodeSnapshot(expected));
        Files.write(getDataFolder().toPath().resolve("transferred.item"), player.contents[0].serializeAsBytes());
        Files.writeString(getDataFolder().toPath().resolve("instance"), instance, StandardCharsets.UTF_8);
        Files.write(getDataFolder().toPath().resolve("known-future.item"), futurePlayer.contents[0].serializeAsBytes());
        Files.writeString(getDataFolder().toPath().resolve("known-future.instance"), futureInstance, StandardCharsets.UTF_8);
        assertRestored(ItemStack.deserializeBytes(player.contents[0].serializeAsBytes()), instance);
        passed();
    }

    private void restart() throws Exception {
        stage = "fresh Paper process initializes exact paired cache before readiness without a player carrier";
        Path evidence = getDataFolder().toPath();
        String expectedDigest = Files.readString(evidence.resolve("expected.digest"), StandardCharsets.UTF_8);
        check(controller.ready() && !plugin.proxyDefinitionsPending(), "Restart ignored the valid durable proxy cache");
        check(controller.currentDigest().equals(expectedDigest), "Restart digest differs from prior process");
        check(controller.currentGeneration() == 2 && plugin.proxyGeneration() == 2,
                "Restart did not retain accepted generation 2");
        assertActive(snapshot("B", true), "B", true);
        check(Arrays.equals(Files.readAllBytes(evidence.resolve("expected.snapshot")),
                SyncProtocol.encodeSnapshot(SnapshotCache.load(cachePath()).orElseThrow())), "Restart altered the persisted pair");
        check(!controller.requestPending() && plugin.hooks().inventoryReady(player.player),
                "Cached restart requires an absent proxy/player carrier");
        passed();

        stage = "cached restart reconciles real serialized instance and rejects invalid replacement";
        instance = Files.readString(evidence.resolve("instance"), StandardCharsets.UTF_8);
        player.contents[0] = ItemStack.deserializeBytes(Files.readAllBytes(evidence.resolve("transferred.item")));
        String knownInstance = Files.readString(evidence.resolve("known-future.instance"), StandardCharsets.UTF_8);
        player.contents[1] = ItemStack.deserializeBytes(Files.readAllBytes(evidence.resolve("known-future.item")));
        plugin.guard().beginSession(player.player);
        plugin.guard().reconcile(player.player);
        var result = plugin.guard().migrate(player.player);
        check(result.removed() == 0 && result.failed() == 0, "Cached startup removed/fails a valid transferred item");
        assertRestored(player.contents[0], instance);
        assertKnownRestored(player.contents[1], knownInstance);
        reject(new SyncProtocol.Snapshot(3, snapshot("A", false).items(), utf8("schema-version: 2\n")));
        passed();
        succeed();
    }

    private void assertActive(SyncProtocol.Snapshot snapshot, String revision, boolean future) throws Exception {
        check(controller.ready() && !controller.requestPending() && controller.currentDigest().equals(SyncProtocol.digest(snapshot)),
                "Accepted snapshot readiness/digest differs");
        check(controller.currentGeneration() == snapshot.generation() && plugin.proxyGeneration() == snapshot.generation(),
                "Accepted controller/plugin generation differs");
        check(plugin.catalog().definitions().size() == (future ? 2 : 1) && plugin.catalog().get("local_only") == null,
                "Proxy catalog contains local fallback or mismatched IDs");
        check(plugin.catalog().get(ACTIVE) != null && (plugin.catalog().get(FUTURE) != null) == future,
                "Active proxy revision IDs differ");
        check(language().equals("Proxy revision " + revision), "Catalog/language revisions diverged");
        check(Arrays.equals(SyncProtocol.encodeSnapshot(snapshot),
                SyncProtocol.encodeSnapshot(SnapshotCache.load(cachePath()).orElseThrow())), "Active/cache snapshots diverged");
    }

    private void assertSuspended(ItemStack stack, String expectedInstance) {
        check(stack != null && FUTURE.equals(plugin.items().id(stack)), "Transferred managed ID disappeared");
        check(rawDurability(stack) == 7 && expectedInstance.equals(instance(stack)), "Suspension lost durability/instance PDC");
        check(itemGeneration(stack) == 2, "Suspension changed future source generation");
        var modifiers = stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        check(modifiers != null && modifiers.modifiers().isEmpty(), "Unknown item retains active native attributes");
        check(stack.getEnchantmentLevel(Enchantment.UNBREAKING) == 1, "Suspension lost instance enchantment");
    }

    private void assertUseDenied(ItemStack stack) { assertUseDenied(player, stack); }

    private void assertUseDenied(Fake owner, ItemStack stack) {
        var event = new PlayerInteractEvent(owner.player, Action.RIGHT_CLICK_AIR, stack,
                null, BlockFace.SELF, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        check(event.useItemInHand() == Event.Result.DENY, "Registered event guard allowed an unknown proxy item");
    }

    private void assertRestored(ItemStack stack, String expectedInstance) {
        check(stack != null && FUTURE.equals(plugin.items().id(stack)) && stack.getType() == Material.DIAMOND_PICKAXE,
                "Future item material/identity not restored");
        check(plain(stack.getItemMeta().displayName()).equals("Proxy B Future") && attributes(stack, 5),
                "Future item definition name/attributes not restored");
        check(rawDurability(stack) == 7 && plugin.items().currentDurability(stack) == 7,
                "Future activation/restart changed custom durability");
        check(itemGeneration(stack) == 2, "Future activation/restart did not retain accepted generation 2");
        check(expectedInstance.equals(instance(stack)) && stack.getEnchantmentLevel(Enchantment.UNBREAKING) == 1,
                "Migration lost instance PDC/enchantment");
        check(stack.getItemMeta().getCustomModelDataComponent().getFloats().equals(List.of(812f)),
                "Future custom model data not restored");
    }

    private ItemStack transferred(String instance) {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        stack.editMeta(meta -> {
            meta.displayName(Component.text("Transferred future item"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item_id"), PersistentDataType.STRING, FUTURE);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "durability"), PersistentDataType.INTEGER, 7);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "proxy_generation"), PersistentDataType.LONG, 2L);
            meta.getPersistentDataContainer().set(new NamespacedKey("syncprobe", "instance"), PersistentDataType.STRING, instance);
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey("syncprobe", "transferred_damage"), 9, AttributeModifier.Operation.ADD_NUMBER));
        });
        return stack;
    }

    private ItemStack futureKnown(String instance) {
        ItemStack stack = transferred(instance).withType(Material.DIAMOND_PICKAXE);
        stack.editMeta(meta -> {
            meta.displayName(Component.text("Transferred generation 2 tool"));
            var model = meta.getCustomModelDataComponent();
            model.setFloats(List.of(923f));
            meta.setCustomModelDataComponent(model);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item_id"), PersistentDataType.STRING, ACTIVE);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "durability"), PersistentDataType.INTEGER, 200);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "proxy_generation"), PersistentDataType.LONG, 2L);
        });
        return stack;
    }

    private void assertKnownFuturePreserved(ItemStack stack, String expectedInstance) {
        check(stack != null && ACTIVE.equals(plugin.items().id(stack)) && stack.getAmount() == 1,
                "Same-ID future item lost identity/quantity");
        check(rawDurability(stack) == 200 && itemGeneration(stack) == 2 && expectedInstance.equals(instance(stack)),
                "Same-ID future item lost durability/generation/foreign PDC");
        check(plain(stack.getItemMeta().displayName()).equals("Transferred generation 2 tool")
                && stack.getItemMeta().getCustomModelDataComponent().getFloats().equals(List.of(923f))
                && stack.getEnchantmentLevel(Enchantment.UNBREAKING) == 1,
                "Same-ID future item lost name/model/enchantment");
        var modifiers = stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        check(modifiers != null && modifiers.modifiers().isEmpty(), "Same-ID future native attributes remain active");
    }

    private void assertKnownRestored(ItemStack stack, String expectedInstance) {
        check(stack != null && ACTIVE.equals(plugin.items().id(stack)) && stack.getType() == Material.IRON_PICKAXE
                && stack.getAmount() == 1 && plain(stack.getItemMeta().displayName()).equals("Proxy B Tool")
                && attributes(stack, 6), "Generation-2 same-ID definition metadata did not activate");
        check(rawDurability(stack) == 200 && plugin.items().currentDurability(stack) == 200 && itemGeneration(stack) == 2,
                "Generation-2 same-ID migration/restart clamped or repaired remaining durability");
        check(expectedInstance.equals(instance(stack)) && stack.getEnchantmentLevel(Enchantment.UNBREAKING) == 1,
                "Generation-2 same-ID migration/restart lost foreign PDC/enchantment");
        check(Integer.valueOf(300).equals(stack.getData(DataComponentTypes.MAX_DAMAGE))
                && Integer.valueOf(100).equals(stack.getData(DataComponentTypes.DAMAGE)),
                "Generation-2 native durability bar differs from 200/300");
    }

    private static SyncProtocol.Snapshot snapshot(String revision, boolean future) {
        return new SyncProtocol.Snapshot(revision.equals("A") ? 1 : 2, utf8(items(revision, future)), utf8(
                "schema-version: 1\nmessages:\n  sync-probe: 'Proxy revision " + revision + "'\n"));
    }

    private static String items(String revision, boolean future) {
        String definitions = """
                items:
                  sync_tool:
                    material: IRON_PICKAXE
                    display-name: 'Proxy %s Tool'
                    type: TOOL
                    rarity: COMMON
                    max-durability: %d
                    canplace: false
                    attributes:
                      'minecraft:attack_damage': 6
                """.formatted(revision, revision.equals("A") ? 100 : 300);
        if (future) definitions += """
                  future_tool:
                    material: DIAMOND_PICKAXE
                    display-name: 'Proxy B Future'
                    type: TOOL
                    rarity: RARE
                    max-durability: 10
                    custom-model-data: 812
                    attributes:
                      'minecraft:attack_damage': 5
                """;
        return definitions + "# " + "multipart-fixture-".repeat(2600) + "\n";
    }

    /** Sole production reflection seam: model an outstanding transport request, never bypass validation. */
    private UUID seed(Fake carrier, long started) throws Exception {
        check(Bukkit.isPrimaryThread(), "Transport seeded off server thread");
        carrier.outgoing.clear();
        UUID request = UUID.randomUUID();
        Class<?> pendingType = Class.forName(ProxyDefinitions.class.getName() + "$Pending", true,
                ProxyDefinitions.class.getClassLoader());
        Constructor<?> constructor = pendingType.getDeclaredConstructor(UUID.class, Player.class, long.class);
        constructor.setAccessible(true);
        Field pending = ProxyDefinitions.class.getDeclaredField("pending");
        pending.setAccessible(true);
        pending.set(controller, constructor.newInstance(request, carrier.player, started));
        return request;
    }

    private void receive(Fake carrier, SyncProtocol.Packet packet) { receive(carrier, SyncProtocol.encode(packet, secret)); }
    private void receive(Fake carrier, byte[] message) {
        Bukkit.getMessenger().dispatchIncomingMessage(carrier.player, SyncProtocol.CHANNEL, message);
    }

    private void expectAck(Fake carrier, UUID nonce, String status, String digest) {
        check(carrier.outgoing.size() == 1, "Expected exactly one signed acknowledgement");
        var packet = carrier.outgoing.getFirst();
        check(packet.kind() == SyncProtocol.Kind.RESULT && packet.request().equals(nonce) && packet.digest().equals(digest)
                && new String(packet.payload(), StandardCharsets.UTF_8).equals(status), "Acknowledgement differs");
        check(!controller.requestPending(), "Completed transport request remains pending");
    }

    private record State(ItemCatalog catalog, ItemLanguage language, String digest, long generation, byte[] cache) { }
    private State state() throws Exception { return new State(plugin.catalog(), plugin.language(), controller.currentDigest(), controller.currentGeneration(), Files.readAllBytes(cachePath())); }
    private void unchanged(State before) throws Exception {
        check(plugin.catalog() == before.catalog() && plugin.language() == before.language()
                && controller.currentDigest().equals(before.digest()) && controller.currentGeneration() == before.generation()
                && plugin.proxyGeneration() == before.generation() && Arrays.equals(Files.readAllBytes(cachePath()), before.cache()),
                "Rejected/replayed update changed last-good references, digest, generation or cache");
    }
    private Path cachePath() { return plugin.getDataFolder().toPath().resolve(SnapshotCache.FILE_NAME); }
    private String language() { return plain(plugin.language().text("messages.sync-probe", Map.of(), null)); }
    private int rawDurability(ItemStack stack) { return stack.getItemMeta().getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, "durability"), PersistentDataType.INTEGER, -1); }
    private long itemGeneration(ItemStack stack) { return stack.getItemMeta().getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, "proxy_generation"), PersistentDataType.LONG, -1L); }
    private static String instance(ItemStack stack) { return stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("syncprobe", "instance"), PersistentDataType.STRING); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String plain(Component component) { return component == null ? "" : PLAIN.serialize(component); }
    private static boolean attributes(ItemStack stack, double amount) {
        var modifiers = stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        return modifiers != null && modifiers.modifiers().size() == 1 && modifiers.modifiers().stream()
                .anyMatch(entry -> entry.attribute().equals(Attribute.ATTACK_DAMAGE) && entry.modifier().getAmount() == amount);
    }
    private void passed() { groups++; getLogger().info("PASS: " + stage); }
    private void succeed() {
        check(groups == (phase.equals("cold") ? 18 : 2), "Acceptance group count differs");
        finished = true;
        getLogger().info("ITEMS_SYNC_PROBE_OK phase=" + phase + " groups=" + groups + "; controlled players/transport, no real clients");
        Bukkit.shutdown();
    }
    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("ITEMS_SYNC_PROBE_FAILED phase=" + phase + " stage=" + stage + ": " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    private void after(long ticks, Checked action) {
        Bukkit.getScheduler().runTaskLater(this, () -> { try { if (!finished) action.run(); } catch (Throwable error) { fail(error); } }, ticks);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object zero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return (char) 0;
    }

    private final class Fake {
        final UUID id;
        final ItemStack[] contents = new ItemStack[43];
        final List<SyncProtocol.Packet> outgoing = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        boolean admin;
        final PersistentDataContainer data = new ItemStack(Material.PAPER).getItemMeta().getPersistentDataContainer();
        final Inventory enderChest = Bukkit.createInventory(null, InventoryType.ENDER_CHEST);
        final World world;
        final PlayerInventory inventory;
        Player player;
        ItemStack cursor;
        boolean online = true;
        int worldDrops;

        Fake(UUID id) {
            this.id = id;
            World realWorld = Bukkit.getWorlds().getFirst();
            world = proxy(World.class, (object, method, args) -> {
                if (method.getName().startsWith("dropItem")) {
                    worldDrops++;
                    throw new IllegalStateException("Controlled player invoked a forbidden world drop");
                }
                return method.invoke(realWorld, args);
            });
            inventory = proxy(PlayerInventory.class, (object, method, args) -> {
                check(Bukkit.isPrimaryThread(), "Controlled inventory accessed off server thread");
                return switch (method.getName()) {
                    case "getSize" -> contents.length;
                    case "getMaxStackSize" -> 64;
                    case "getType" -> InventoryType.PLAYER;
                    case "getHolder" -> player;
                    case "getViewers" -> List.of(player);
                    case "getItem" -> contents[index(args[0])];
                    case "setItem" -> { contents[index(args[0])] = (ItemStack) args[1]; yield null; }
                    case "getItemInMainHand", "getItemInHand" -> hand(0);
                    case "getItemInOffHand" -> hand(40);
                    case "setItemInMainHand", "setItemInHand" -> { contents[0] = (ItemStack) args[0]; yield null; }
                    case "setItemInOffHand" -> { contents[40] = (ItemStack) args[0]; yield null; }
                    case "getHeldItemSlot" -> 0;
                    case "getContents" -> contents.clone();
                    case "getStorageContents" -> Arrays.copyOf(contents, 36);
                    case "getArmorContents" -> Arrays.copyOfRange(contents, 36, 40);
                    case "getHelmet" -> hand(39);
                    case "getChestplate" -> hand(38);
                    case "getLeggings" -> hand(37);
                    case "getBoots" -> hand(36);
                    case "setHelmet" -> { contents[39] = (ItemStack) args[0]; yield null; }
                    case "setChestplate" -> { contents[38] = (ItemStack) args[0]; yield null; }
                    case "setLeggings" -> { contents[37] = (ItemStack) args[0]; yield null; }
                    case "setBoots" -> { contents[36] = (ItemStack) args[0]; yield null; }
                    case "firstEmpty" -> firstEmpty();
                    case "addItem" -> add((ItemStack[]) args[0]);
                    case "setStorageContents" -> {
                        Arrays.fill(contents, 0, 36, null);
                        System.arraycopy(args[0], 0, contents, 0, ((ItemStack[]) args[0]).length);
                        yield null;
                    }
                    case "iterator" -> Arrays.asList(contents.clone()).listIterator(args == null || args.length == 0 ? 0 : (int) args[0]);
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "hashCode" -> System.identityHashCode(object);
                    case "equals" -> object == args[0];
                    default -> throw new UnsupportedOperationException("Unimplemented controlled inventory API: " + method);
                };
            });
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "getDisplayName" -> "SyncProbeUser";
                case "name", "displayName" -> Component.text("SyncProbeUser");
                case "getServer" -> Bukkit.getServer();
                case "getPlayer" -> object;
                case "isOnline", "isConnected", "isValid" -> online;
                case "hasPlayedBefore" -> true;
                case "hasPermission", "isPermissionSet" -> admin && args[0].equals("planetry.admin.items");
                case "isDead", "isSneaking", "isOp", "isGliding" -> false;
                case "get" -> args[0].equals(Identity.UUID) ? Optional.of(id) : Optional.empty();
                case "pointers" -> Pointers.builder().withStatic(Identity.UUID, id).build();
                case "getPersistentDataContainer" -> data;
                case "getInventory" -> inventory;
                case "getEnderChest" -> enderChest;
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getWorld" -> world;
                case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                case "getItemOnCursor" -> cursor == null ? new ItemStack(Material.AIR) : cursor;
                case "setItemOnCursor" -> { cursor = (ItemStack) args[0]; yield null; }
                case "sendPluginMessage" -> {
                    check(Bukkit.isPrimaryThread(), "Outgoing packet sent off server thread");
                    check(args[0] == plugin && SyncProtocol.CHANNEL.equals(args[1]), "Wrong transport plugin/channel");
                    outgoing.add(SyncProtocol.decode((byte[]) args[2], secret));
                    yield null;
                }
                case "sendMessage" -> {
                    if (args != null) for (Object argument : args) {
                        if (argument instanceof Component component) messages.add(plain(component));
                        else if (argument instanceof String text) messages.add(text);
                        else if (argument instanceof String[] lines) messages.addAll(List.of(lines));
                    }
                    yield null;
                }
                case "updateInventory", "closeInventory", "saveData" -> null;
                case "getEffectivePermissions" -> Set.of();
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == args[0];
                case "toString" -> "SyncProbeUser";
                default -> zero(method.getReturnType());
            });
        }

        int index(Object value) {
            if (value instanceof Integer slot) return slot;
            return switch ((EquipmentSlot) value) {
                case HAND -> 0; case OFF_HAND -> 40; case FEET -> 36; case LEGS -> 37;
                case CHEST -> 38; case HEAD -> 39; case BODY -> 41; case SADDLE -> 42;
            };
        }
        ItemStack hand(int slot) { return contents[slot] == null ? new ItemStack(Material.AIR) : contents[slot]; }
        int firstEmpty() { for (int slot = 0; slot < 36; slot++) if (contents[slot] == null || contents[slot].getType().isAir()) return slot; return -1; }
        Map<Integer, ItemStack> add(ItemStack[] incoming) {
            var remaining = new HashMap<Integer, ItemStack>();
            for (int index = 0; index < incoming.length; index++) {
                int slot = firstEmpty();
                if (slot < 0) remaining.put(index, incoming[index].clone());
                else contents[slot] = incoming[index].clone();
            }
            return remaining;
        }
    }
}
