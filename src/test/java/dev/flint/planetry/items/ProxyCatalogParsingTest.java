package dev.flint.planetry.items;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProxyCatalogParsingTest {
    @Test void emptyNetworkCatalogIsExplicitAndHasNoExamples() {
        assertTrue(ItemCatalog.parse("schema-version: 1\nitems: {}\n").definitions().isEmpty());
    }

    @Test void receivedCatalogUsesTheSameStrictParser() {
        var result = ItemCatalog.parse("items:\n  sample:\n    material: PAPER\n    max-durability: 0\n    canplace: false\n    soulbound: true\n");
        assertFalse(result.get("sample").canPlace());
        assertTrue(result.get("sample").soulbound());
        assertThrows(IllegalArgumentException.class, () -> ItemCatalog.parse("items:\n  sample:\n    material: NO_SUCH_MATERIAL\n"));
        assertThrows(IllegalArgumentException.class, () -> ItemCatalog.parse("items:\n  sample: {}\n  sample: {}\n"));
    }

    @Test void receivedLanguageRetainsFallbacksAndRejectsMalformedTemplates() throws Exception {
        assertNotNull(ItemLanguage.parse("schema-version: 1\nmessages:\n  given: '<green>Updated {item}'\n"));
        assertThrows(IllegalArgumentException.class, () -> ItemLanguage.parse("messages:\n  given: '<glyph>'\n"));
        assertThrows(IllegalArgumentException.class, () -> ItemLanguage.parse("schema-version: 9\n"));
    }
}
