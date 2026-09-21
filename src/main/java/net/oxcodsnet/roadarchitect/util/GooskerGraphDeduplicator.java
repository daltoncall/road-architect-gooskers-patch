package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.oxcodsnet.roadarchitect.storage.EdgeStorage;
import net.oxcodsnet.roadarchitect.storage.NodeStorage;
import net.oxcodsnet.roadarchitect.storage.RoadGraphState;
import net.oxcodsnet.roadarchitect.storage.components.Node;
import net.oxcodsnet.roadarchitect.util.KeyUtil;

public final class GooskerGraphDeduplicator {
    private static final Set<RoadGraphState> CLEANED = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap()));

    private GooskerGraphDeduplicator() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static RoadGraphState cleanupAndCast(Object object) {
        boolean bl;
        RoadGraphState roadGraphState = (RoadGraphState)((Object)object);
        Set<RoadGraphState> set = CLEANED;
        synchronized (set) {
            bl = CLEANED.add(roadGraphState);
        }
        if (bl) {
            GooskerGraphDeduplicator.cleanupDuplicates(roadGraphState);
        }
        return roadGraphState;
    }

    public static Node getOrAddNode(NodeStorage nodeStorage, BlockPos blockPos, String string) {
        for (Node node : nodeStorage.all().values()) {
            if (!GooskerGraphDeduplicator.sameStructurePosition(node, blockPos, string)) continue;
            return node;
        }
        return nodeStorage.add(blockPos, string);
    }

    public static boolean addEdgeIfDistinct(EdgeStorage edgeStorage, Node node, Node node2) {
        if (node == null || node2 == null || GooskerGraphDeduplicator.sameXZ(node.pos(), node2.pos())) {
            return false;
        }
        return edgeStorage.add(node, node2);
    }

    private static boolean sameStructurePosition(Node node, BlockPos blockPos, String string) {
        return node != null && node.type().equals(string) && GooskerGraphDeduplicator.sameXZ(node.pos(), blockPos);
    }

    private static boolean sameXZ(BlockPos blockPos, BlockPos blockPos2) {
        return blockPos != null && blockPos2 != null && blockPos.getX() == blockPos2.getX() && blockPos.getZ() == blockPos2.getZ();
    }

    private static String key(Node node) {
        BlockPos blockPos = node.pos();
        return node.type() + "\u0000" + blockPos.getX() + "\u0000" + blockPos.getZ();
    }

    private static void cleanupDuplicates(RoadGraphState roadGraphState) {
        HashMap<String, Node> hashMap = new HashMap<String, Node>(roadGraphState.nodes().all());
        HashMap<String, List<Node>> hashMap2 = new HashMap<>();
        for (Node node : hashMap.values()) {
            hashMap2.computeIfAbsent(GooskerGraphDeduplicator.key(node), string -> new ArrayList<>()).add(node);
        }
        HashMap<String, String> hashMap3 = new HashMap<>();
        for (List<Node> duplicates : hashMap2.values()) {
            if (duplicates.size() < 2) continue;
            duplicates.sort(Comparator.comparing(Node::id));
            Node canonical = duplicates.get(0);
            for (int i = 1; i < duplicates.size(); ++i) {
                hashMap3.put(duplicates.get(i).id(), canonical.id());
            }
        }
        if (hashMap3.isEmpty()) {
            return;
        }
        Map<String, EdgeStorage.Edge> edges = roadGraphState.edges().all();
        for (EdgeStorage.Edge edge : edges.values()) {
            String string2 = hashMap3.getOrDefault(edge.nodeA(), edge.nodeA());
            String string3 = hashMap3.getOrDefault(edge.nodeB(), edge.nodeB());
            if (string2.equals(edge.nodeA()) && string3.equals(edge.nodeB())) continue;
            roadGraphState.edges().remove(edge.id());
            if (string2.equals(string3)) continue;
            Node node = (Node)hashMap.get(string2);
            Node node2 = (Node)hashMap.get(string3);
            if (node == null || node2 == null || GooskerGraphDeduplicator.sameXZ(node.pos(), node2.pos())) continue;
            String string4 = KeyUtil.edgeKey(string2, string3);
            if (roadGraphState.edges().getStatus(string4) != null) continue;
            roadGraphState.edges().add(node, node2);
        }
        for (String string5 : hashMap3.keySet()) {
            roadGraphState.nodes().remove(string5);
        }
        roadGraphState.setDirty();
    }
}
