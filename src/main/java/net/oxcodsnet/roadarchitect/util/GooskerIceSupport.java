package net.oxcodsnet.roadarchitect.util;

import java.util.Locale;
import net.minecraft.world.level.block.state.BlockState;

/** Recognizes vanilla solid ice surfaces without treating liquid water as road terrain. */
public final class GooskerIceSupport {
    private GooskerIceSupport() {}

    public static boolean isIce(BlockState state) {
        if (state == null) return false;
        String text = String.valueOf(state).toLowerCase(Locale.ROOT);
        return exact(text, "ice") || exact(text, "packed_ice")
                || exact(text, "blue_ice") || exact(text, "frosted_ice");
    }

    private static boolean exact(String text, String id) {
        String full = "minecraft:" + id;
        return text.contains("{" + full + "}") || text.contains("{" + full + "}[")
                || text.contains("block{" + full + "}") || text.equals(full)
                || text.startsWith(full + "[");
    }
}
