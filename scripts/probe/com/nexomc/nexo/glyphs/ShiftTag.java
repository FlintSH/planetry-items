package com.nexomc.nexo.glyphs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class ShiftTag {
    public static final ShiftTag INSTANCE = new ShiftTag();
    public TagResolver getRESOLVER() {
        return TagResolver.resolver("shift", (arguments, context) -> {
            String value = arguments.pop().value();
            if (context.target() instanceof org.bukkit.entity.Player viewer && !viewer.hasPermission("nexo.shift"))
                return Tag.preProcessParsed("\\<shift:" + value + ">");
            return Tag.inserting(Component.text("SHIFT[" + value + "]"));
        });
    }
}
