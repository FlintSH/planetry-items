package dev.flint.planetry.items;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Objects;

/** Imports legacy definitions once; an existing empty directory stays intentionally empty. */
final class ItemFiles {
    private ItemFiles() { }
    static void initialize(Path data, InputStream examples) throws IOException {
        try (examples) {
            Path directory = data.resolve("items");
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                    throw new IOException("items must be a real directory");
                return;
            }
            Files.createDirectories(data);
            Path staging = Files.createTempDirectory(data, ".items-import-");
            try {
                Path legacy = data.resolve("items.yml");
                if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(legacy) || !Files.isRegularFile(legacy))
                        throw new IOException("Legacy items.yml must be a regular file");
                    Files.copy(legacy, staging.resolve("imported.yml"));
                } else {
                    Files.copy(Objects.requireNonNull(examples, "Missing bundled items.yml"), staging.resolve("examples.yml"));
                }
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(staging)) {
                    try (var files = Files.list(staging)) {
                        for (Path file : files.toList()) Files.delete(file);
                    }
                    Files.delete(staging);
                }
            }
        }
    }
}
