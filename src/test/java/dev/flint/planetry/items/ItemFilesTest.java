package dev.flint.planetry.items;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemFilesTest {
    @TempDir Path data;
    private ByteArrayInputStream examples() { return new ByteArrayInputStream("items: {}\n".getBytes(StandardCharsets.UTF_8)); }
    @Test void importsLegacyOnceAndPreservesOriginal() throws Exception {
        String legacy = "items:\n  custom:\n    material: PAPER\n";
        Files.writeString(data.resolve("items.yml"), legacy);
        ItemFiles.initialize(data, examples());
        assertEquals(legacy, Files.readString(data.resolve("items/imported.yml")));
        assertEquals(legacy, Files.readString(data.resolve("items.yml")));
        Files.delete(data.resolve("items/imported.yml"));
        ItemFiles.initialize(data, examples());
        try (var entries = Files.list(data.resolve("items"))) { assertEquals(0, entries.count()); }
    }
    @Test void freshInstallWritesOnlyExamples() throws Exception {
        ItemFiles.initialize(data, examples());
        assertEquals("items: {}\n", Files.readString(data.resolve("items/examples.yml")));
        assertFalse(Files.exists(data.resolve("items.yml")));
    }
    @Test void preservesExistingFolderAndIgnoresLegacy() throws Exception {
        Files.createDirectory(data.resolve("items"));
        Files.writeString(data.resolve("items.yml"), "broken: [");
        ItemFiles.initialize(data, examples());
        try (var entries = Files.list(data.resolve("items"))) { assertEquals(0, entries.count()); }
    }
    @Test void rejectsSymlinkFolder() throws Exception {
        Path outside = Files.createDirectory(data.resolve("outside"));
        Files.createSymbolicLink(data.resolve("items"), outside);
        assertThrows(java.io.IOException.class, () -> ItemFiles.initialize(data, examples()));
    }
    @Test void rejectsLegacySymlinkWithoutPartialFolder() throws Exception {
        Path outside = Files.writeString(data.resolve("outside.yml"), "items: {}");
        Files.createSymbolicLink(data.resolve("items.yml"), outside);
        assertThrows(java.io.IOException.class, () -> ItemFiles.initialize(data, examples()));
        assertFalse(Files.exists(data.resolve("items")));
        try (var entries = Files.list(data)) { assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".items-import-"))); }
    }
}
