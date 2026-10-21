package com.nexomc.nexo.glyphs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Returns readable sentinels so rarity resolver use can be asserted without a resource pack. */
public final class GlyphTag {
    public static final GlyphTag INSTANCE = new GlyphTag();
    public TagResolver getRESOLVER() {
        return TagResolver.resolver("glyph", (arguments, context) ->
                Tag.inserting(Component.text("GLYPH[" + arguments.pop().value() + "]")));
    }
}
