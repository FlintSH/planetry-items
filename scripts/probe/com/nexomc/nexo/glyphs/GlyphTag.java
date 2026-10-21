package com.nexomc.nexo.glyphs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/** Controlled native API shape and font/argument sentinels, not an actual Nexo compatibility test. */
public final class GlyphTag {
    public static final GlyphTag INSTANCE = new GlyphTag();
    public static java.util.UUID lastViewer;
    public TagResolver getRESOLVER() {
        return TagResolver.resolver("glyph", (arguments, context) -> {
            String id = arguments.pop().value();
            if (context.target() instanceof net.kyori.adventure.pointer.Pointered pointered)
                lastViewer = pointered.get(net.kyori.adventure.identity.Identity.UUID).orElse(null);
            if (context.target() instanceof Player viewer && !viewer.hasPermission("nexo.glyph.probe")) {
                while (arguments.hasNext()) arguments.pop();
                return Tag.inserting(Component.text("<glyph:" + id + ">"));
            }
            var options = new java.util.ArrayList<String>();
            while (arguments.hasNext()) options.add(arguments.pop().value());
            if (id.equals("probe_missing")) return null;
            if (id.equals("probe_empty")) return Tag.inserting(Component.empty());
            if (id.equals("probe_broken")) throw new LinkageError("Controlled glyph failure");
            return Tag.inserting(Component.text("GLYPH[" + id
                    + (options.isEmpty() ? "" : ":" + String.join(":", options)) + "]").font(Key.key("nexo:probe_glyph")));
        });
    }
}
