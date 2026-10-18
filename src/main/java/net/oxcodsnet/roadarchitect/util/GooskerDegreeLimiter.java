package net.oxcodsnet.roadarchitect.util;

import net.oxcodsnet.roadarchitect.storage.EdgeStorage;
import net.oxcodsnet.roadarchitect.storage.components.Node;

/** Keeps generated structure nodes from turning into multi-road hubs. */
public final class GooskerDegreeLimiter {
    private static final int MAX_ROADS_PER_STRUCTURE = 2;

    private GooskerDegreeLimiter() {}

    public static boolean hasRoom(EdgeStorage storage, Node a, Node b) {
        if (storage == null || a == null || b == null) {
            return false;
        }
        return storage.neighbors(a.id()).size() < MAX_ROADS_PER_STRUCTURE
                && storage.neighbors(b.id()).size() < MAX_ROADS_PER_STRUCTURE;
    }
}
