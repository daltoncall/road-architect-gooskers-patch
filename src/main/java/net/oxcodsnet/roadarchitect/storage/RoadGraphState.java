package net.oxcodsnet.roadarchitect.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.oxcodsnet.roadarchitect.RoadArchitect;
import net.oxcodsnet.roadarchitect.storage.EdgeStorage;
import net.oxcodsnet.roadarchitect.storage.NodeStorage;
import net.oxcodsnet.roadarchitect.storage.components.Node;
import net.oxcodsnet.roadarchitect.util.GeometryUtils;
import net.oxcodsnet.roadarchitect.util.GooskerGraphDeduplicator;
import net.oxcodsnet.roadarchitect.util.KeyUtil;
import net.oxcodsnet.roadarchitect.util.PersistentStateUtil;

public class RoadGraphState
extends SavedData {
    private static final String KEY = "road_graph";
    private static final String NODES_KEY = "nodes";
    private static final String EDGES_KEY = "edges";
    private static final String RADIUS_KEY = "radius";
    private final NodeStorage nodeStorage;
    private final EdgeStorage edgeStorage;

    public RoadGraphState(double radius) {
        this(new NodeStorage(), new EdgeStorage(radius));
    }

    private RoadGraphState(NodeStorage nodes, EdgeStorage edges) {
        this.nodeStorage = nodes;
        this.edgeStorage = edges;
    }

    public static RoadGraphState get(ServerLevel world) {
        return GooskerGraphDeduplicator.cleanupAndCast((Object)PersistentStateUtil.get(world, () -> new RoadGraphState(RoadArchitect.CONFIG.maxConnectionDistance()), RoadGraphState::fromNbt, KEY));
    }

    public static RoadGraphState fromNbt(CompoundTag tag) {
        double radius = tag.getDouble(RADIUS_KEY);
        NodeStorage nodes = NodeStorage.fromNbt(tag.getList(NODES_KEY, 10));
        EdgeStorage edges = EdgeStorage.fromNbt(tag.getCompound(EDGES_KEY), radius);
        return new RoadGraphState(nodes, edges);
    }

    public NodeStorage nodes() {
        return this.nodeStorage;
    }

    public EdgeStorage edges() {
        return this.edgeStorage;
    }

    public Node addNodeWithEdges(BlockPos pos, String type) {
        Node newNode = GooskerGraphDeduplicator.getOrAddNode(this.nodeStorage, pos, type);
        for (Node other : this.nodeStorage.all().values()) {
            if (other.id().equals(newNode.id())) continue;
            this.connect(newNode, other);
        }
        this.setDirty();
        return newNode;
    }

    public void connect(Node nodeA, Node nodeB) {
        double max;
        double dz;
        String idNodeB;
        if (nodeA == null || nodeB == null) {
            return;
        }
        String idNodeA = nodeA.id();
        if (idNodeA.equals(idNodeB = nodeB.id())) {
            return;
        }
        double dx = nodeA.pos().getX() - nodeB.pos().getX();
        if (dx * dx + (dz = (double)(nodeA.pos().getZ() - nodeB.pos().getZ())) * dz > (max = this.edgeStorage.radius() * 2.0) * max) {
            return;
        }
        if (this.edgeStorage.all().containsKey(KeyUtil.edgeKey(idNodeA, idNodeB))) {
            return;
        }
        for (EdgeStorage.Edge e : this.edgeStorage.all().values()) {
            if (e.connects(idNodeA) || e.connects(idNodeB)) continue;
            Node n1 = this.nodeStorage.all().get(e.nodeA());
            Node n2 = this.nodeStorage.all().get(e.nodeB());
            if (n1 == null || n2 == null || !GeometryUtils.segmentsIntersect2D(nodeA.pos(), nodeB.pos(), n1.pos(), n2.pos())) continue;
            return;
        }
        boolean added = GooskerGraphDeduplicator.addEdgeIfDistinct(this.edgeStorage, nodeA, nodeB);
        if (added) {
            this.setDirty();
        }
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putDouble(RADIUS_KEY, this.edgeStorage.radius());
        tag.put(NODES_KEY, (Tag)this.nodeStorage.toNbt());
        tag.put(EDGES_KEY, (Tag)this.edgeStorage.toNbt());
        return tag;
    }
}

