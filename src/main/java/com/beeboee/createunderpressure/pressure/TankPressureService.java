package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final float PRESSURE_PER_BLOCK = 8.0f;
    private static final float MIN_PRESSURE = 16.0f;
    private static final float MAX_PRESSURE = 256.0f;
    private static final double EPSILON = 0.01;

    public static void tickTank(FluidTankBlockEntity tank) {
        Level level = tank.getLevel();
        if (level == null || level.isClientSide || !tank.isController()) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        Set<BlockPos> alreadyScanned = new HashSet<>();
        for (BlockPos seed : seeds(level, tank)) {
            if (alreadyScanned.contains(seed)) continue;

            Scan scan = scan(level, seed);
            if (scan == null || scan.pipes.isEmpty()) continue;
            alreadyScanned.addAll(scan.pipes);

            End source = bestSource(scan);
            if (source == null) continue;

            List<End> targets = targets(scan, source);
            if (targets.isEmpty()) {
                if (ownsNetwork(source, targets, tank)) DebugInfo.log(level, "NETWORK idle source={} surface={} endpoints={}", source.name(), source.surface, scan.ends.size());
                continue;
            }

            if (!ownsNetwork(source, targets, tank)) continue;

            List<Route> routes = routes(level, scan, source, targets);
            if (routes.isEmpty()) {
                DebugInfo.log(level, "NETWORK blocked source={} targets={}", source.name(), targets.size());
                continue;
            }

            float share = 1.0f / routes.size();
            DebugInfo.log(level, "NETWORK split source={} targets={} share={}", source.name(), routes.size(), share);
            for (Route route : routes) apply(level, source, route.target, route.path, share);
        }
    }

    private static Scan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        List<End> ends = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(seed, 0));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_DISTANCE) continue;
            if (!level.isLoaded(node.pos) || !pipes.add(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;

            BlockState state = level.getBlockState(node.pos);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    ends.add(End.tank(node.pos, face, tank));
                    continue;
                }

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                boolean fluidCap = FluidPropagator.hasFluidCapability(level, other, face.getOpposite());
                boolean openEnd = FluidPropagator.isOpenEnd(level, node.pos, face);
                boolean sourceBlock = openEnd && level.getFluidState(other).isSource();
                if (fluidCap || openEnd) ends.add(End.external(node.pos, face, other.getY(), fluidCap, openEnd, sourceBlock));
            }
        }

        return new Scan(pipes, ends);
    }

    private static Set<BlockPos> seeds(Level level, FluidTankBlockEntity tank) {
        Set<BlockPos> seeds = new HashSet<>();
        BlockPos base = tank.getController();
        for (int x = 0; x < tank.getWidth(); x++) {
            for (int z = 0; z < tank.getWidth(); z++) {
                BlockPos tankBlock = base.offset(x, 0, z);
                for (Direction direction : Direction.values()) {
                    BlockPos pipePos = tankBlock.relative(direction);
                    if (level.isLoaded(pipePos) && FluidPropagator.getPipe(level, pipePos) != null) seeds.add(pipePos);
                }
            }
        }
        return seeds;
    }

    private static End bestSource(Scan scan) {
        End best = null;
        for (End end : scan.ends) {
            if (!end.provides) continue;
            if (best == null || end.surface > best.surface + EPSILON || (Math.abs(end.surface - best.surface) <= EPSILON && end.pipe.getY() > best.pipe.getY())) best = end;
        }
        return best;
    }

    private static List<End> targets(Scan scan, End source) {
        List<End> targets = new ArrayList<>();
        for (End end : scan.ends) {
            if (!end.receives) continue;
            if (sameTank(source, end)) continue;
            if (end.surface >= source.surface - EPSILON) continue;
            targets.add(end);
        }
        targets.sort(Comparator.comparingDouble((End end) -> end.surface).thenComparingInt(end -> end.pipe.getY()));
        return targets;
    }

    private static List<Route> routes(Level level, Scan scan, End source, List<End> targets) {
        List<Route> routes = new ArrayList<>();
        Set<BlockPos> usedTargets = new HashSet<>();
        for (End target : targets) {
            if (!usedTargets.add(target.pipe.relative(target.face))) continue;
            List<Step> path = path(level, scan, source.pipe, target.pipe);
            if (path != null) routes.add(new Route(target, path));
        }
        return routes;
    }

    private static boolean ownsNetwork(End source, List<End> targets, FluidTankBlockEntity tank) {
        if (source.tank != null) return belongsToTank(source, tank);

        End owner = null;
        for (End target : targets) {
            if (target.tank == null) continue;
            if (owner == null || compareBlockPos(target.tank.getController(), owner.tank.getController()) < 0) owner = target;
        }
        return owner != null && belongsToTank(owner, tank);
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static boolean sameTank(End a, End b) {
        return a.tank != null && b.tank != null && a.tank.getController().equals(b.tank.getController());
    }

    private static boolean belongsToTank(End end, FluidTankBlockEntity tank) {
        return end.tank != null && end.tank.getController().equals(tank.getController());
    }

    private static List<Step> path(Level level, Scan scan, BlockPos source, BlockPos target) {
        Map<BlockPos, Step> from = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(source);
        visited.add(source);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(target)) break;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pos), pipe)) {
                BlockPos other = pos.relative(face);
                if (!scan.pipes.contains(other) || !visited.add(other)) continue;
                from.put(other, new Step(pos, face, other));
                queue.add(other);
            }
        }

        if (!source.equals(target) && !from.containsKey(target)) return null;
        List<Step> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(source)) {
            Step step = from.get(cursor);
            if (step == null) return null;
            reversed.add(step);
            cursor = step.from;
        }
        List<Step> out = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) out.add(reversed.get(i));
        return out;
    }

    private static void apply(Level level, End source, End target, List<Step> path, float share) {
        double head = source.surface - target.surface;
        if (head <= EPSILON) return;
        float pressure = pressureForHead(head) * share;
        Map<BlockPos, Map<Direction, Boolean>> graph = new HashMap<>();

        graph.computeIfAbsent(source.pipe, $ -> new IdentityHashMap<>()).put(source.face, true);
        for (Step step : path) {
            graph.computeIfAbsent(step.from, $ -> new IdentityHashMap<>()).put(step.face, false);
            graph.computeIfAbsent(step.to, $ -> new IdentityHashMap<>()).put(step.face.getOpposite(), true);
        }
        graph.computeIfAbsent(target.pipe, $ -> new IdentityHashMap<>()).put(target.face, false);

        DebugInfo.log(level, "NETWORK apply source={} target={} head={} pressure={} share={} path={}", source.name(), target.name(), head, pressure, share, path.size());

        for (Map.Entry<BlockPos, Map<Direction, Boolean>> pipeEntry : graph.entrySet()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipeEntry.getKey());
            if (pipe == null) continue;
            for (Map.Entry<Direction, Boolean> side : pipeEntry.getValue().entrySet()) {
                pipe.addPressure(side.getKey(), side.getValue(), pressure);
                DebugInfo.log(level, "Added tank pressure NETWORK pipePos={} side={} inbound={} pressure={}", pipeEntry.getKey(), side.getKey(), side.getValue(), pressure);
            }
        }

        for (BlockPos pipePos : graph.keySet()) FluidTransportBehaviour.cacheFlows(level, pipePos);
    }

    private static float pressureForHead(double head) {
        float pressure = (float) Math.min(MAX_PRESSURE, head * PRESSURE_PER_BLOCK);
        if (head >= 1.0) return Math.max(MIN_PRESSURE, pressure);
        return pressure;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static double surface(FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        int capacity = tank.getTankInventory().getCapacity();
        int height = Math.max(1, tank.getHeight());
        return (double) capacity / (double) height;
    }

    private record Scan(Set<BlockPos> pipes, List<End> ends) {}
    private record Node(BlockPos pos, int distance) {}
    private record Step(BlockPos from, Direction face, BlockPos to) {}
    private record Route(End target, List<Step> path) {}

    private static final class End {
        final BlockPos pipe;
        final Direction face;
        final FluidTankBlockEntity tank;
        final double surface;
        final boolean provides;
        final boolean receives;
        final boolean openEnd;

        private End(BlockPos pipe, Direction face, FluidTankBlockEntity tank, double surface, boolean provides, boolean receives, boolean openEnd) {
            this.pipe = pipe;
            this.face = face;
            this.tank = tank;
            this.surface = surface;
            this.provides = provides;
            this.receives = receives;
            this.openEnd = openEnd;
        }

        static End tank(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
            int amount = tank.getTankInventory().getFluidAmount();
            int capacity = tank.getTankInventory().getCapacity();
            return new End(pipe, face, tank, surface(tank), amount > 0, amount < capacity, false);
        }

        static End external(BlockPos pipe, Direction face, double y, boolean fluidCap, boolean openEnd, boolean sourceBlock) {
            return new End(pipe, face, null, y + (sourceBlock ? 1.0 : 0.0), sourceBlock, fluidCap || openEnd, openEnd);
        }

        String name() {
            if (tank != null) return "tank@" + tank.getController() + "/pipe=" + pipe + "/face=" + face;
            return "external@" + pipe.relative(face) + "/pipe=" + pipe + "/face=" + face + "/open=" + openEnd;
        }
    }
}
