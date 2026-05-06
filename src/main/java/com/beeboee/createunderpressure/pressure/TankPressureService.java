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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final float LAVA_PRESSURE_MULTIPLIER = 0.35f;
    private static final float TRICKLE_PRESSURE = 8.0f;
    private static final float MIN_PRESSURE = 16.0f;
    private static final float MAX_PRESSURE = 256.0f;
    private static final double DEAD_HEAD = 0.125;
    private static final double TRICKLE_HEAD = 1.0;
    private static final double MAX_HEAD = 32.0;
    private static final double EPSILON = 0.01;

    public static void tickTank(FluidTankBlockEntity tank) {
        // Intentionally disabled.
        // Direct tank-to-tank equalization bypassed pipe routes and pipe-height siphon rules.
        // Tank movement now comes from the pipe graph adding pressure between tank endpoints.
    }

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        Scan scan = scan(level, seed);
        if (scan == null || scan.pipes.isEmpty()) return;
        if (!ownsPipeNetwork(scan, seed)) return;

        End source = bestSource(scan);
        if (source == null) return;

        List<End> targets = targets(scan, source);
        if (targets.isEmpty()) {
            DebugInfo.log(level, "GRAPH idle owner={} source={} surface={} endpoints={} pipes={}", seed, source.name(), source.surface, scan.ends.size(), scan.pipes.size());
            return;
        }

        List<Route> routes = routes(level, scan, source, targets);
        if (routes.isEmpty()) {
            DebugInfo.log(level, "GRAPH blocked owner={} source={} targets={}", seed, source.name(), targets.size());
            return;
        }

        float share = 1.0f / routes.size();
        DebugInfo.log(level, "GRAPH split owner={} source={} targets={} share={} pipes={}", seed, source.name(), routes.size(), share, scan.pipes.size());
        for (Route route : routes) apply(level, source, route.target, route.path, share);
    }

    private static Scan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Set<BlockPos> bridgedTanks = new HashSet<>();
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
                    if (bridgedTanks.add(tank.getController())) {
                        for (BlockPos tankSeed : seeds(level, tank)) {
                            Direction targetFace = faceToTank(level, tank, tankSeed);
                            if (targetFace != null && tankCanProvideToPipe(tankSeed, targetFace, tank)) {
                                queue.add(new Node(tankSeed, node.distance + 1));
                            }
                        }
                    }
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
            for (int y = 0; y < tank.getHeight(); y++) {
                for (int z = 0; z < tank.getWidth(); z++) {
                    BlockPos tankBlock = base.offset(x, y, z);
                    for (Direction direction : Direction.values()) {
                        BlockPos pipePos = tankBlock.relative(direction);
                        if (level.isLoaded(pipePos) && FluidPropagator.getPipe(level, pipePos) != null) seeds.add(pipePos);
                    }
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
            if (!compatible(source, end)) continue;
            if (end.surface >= source.surface - DEAD_HEAD) continue;
            targets.add(end);
        }
        targets.sort(Comparator.comparingDouble((End end) -> source.surface - end.surface).reversed().thenComparingInt(end -> end.pipe.getY()));
        return targets;
    }

    private static boolean compatible(End source, End target) {
        if (source.tank == null || target.tank == null) return true;

        FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
        FluidStack targetFluid = target.tank.getTankInventory().getFluid();
        return sourceFluid.isEmpty() || targetFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
    }

    private static List<Route> routes(Level level, Scan scan, End source, List<End> targets) {
        List<Route> routes = new ArrayList<>();
        Set<BlockPos> usedTargets = new HashSet<>();
        for (End target : targets) {
            if (!usedTargets.add(target.pipe.relative(target.face))) continue;
            List<Step> path = path(level, scan, source, target.pipe);
            if (path == null) continue;
            if (blockedByEarlierReceivingTank(scan, source, target, path)) continue;
            routes.add(new Route(target, path));
        }
        return routes;
    }

    private static boolean blockedByEarlierReceivingTank(Scan scan, End source, End target, List<Step> path) {
        for (End blocker : scan.ends) {
            if (blocker.tank == null || !blocker.receives) continue;
            if (sameTank(blocker, source) || sameTank(blocker, target)) continue;
            if (!compatible(source, blocker)) continue;
            if (!tankBlocksPath(source, blocker.tank)) continue;
            if (blocksAlongPath(blocker, path)) return true;
        }
        return false;
    }

    private static boolean blocksAlongPath(End blocker, List<Step> path) {
        for (Step step : path) {
            if (blocker.pipe.equals(step.from) && blocker.face == step.fromFace) return true;
            if (blocker.pipe.equals(step.to)) return true;
        }
        return false;
    }

    private static boolean ownsPipeNetwork(Scan scan, BlockPos seed) {
        BlockPos owner = null;
        for (BlockPos pipe : scan.pipes) {
            if (owner == null || compareBlockPos(pipe, owner) < 0) owner = pipe;
        }
        return seed.equals(owner);
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static boolean sameTank(End a, End b) {
        return a.tank != null && b.tank != null && a.tank.getController().equals(b.tank.getController());
    }

    private static List<Step> path(Level level, Scan scan, End source, BlockPos target) {
        BlockPos start = source.pipe;
        Map<BlockPos, Step> from = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(target)) break;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) continue;

            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pos), pipe)) {
                BlockPos other = pos.relative(face);
                if (!level.isLoaded(other)) continue;

                if (scan.pipes.contains(other) && visited.add(other)) {
                    from.put(other, new Step(pos, face, other, face.getOpposite()));
                    queue.add(other);
                    continue;
                }

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank == null) continue;
                if (tankBlocksPath(source, tank)) continue;

                for (BlockPos tankSeed : seeds(level, tank)) {
                    if (tankSeed.equals(pos) || !scan.pipes.contains(tankSeed)) continue;
                    Direction targetFace = faceToTank(level, tank, tankSeed);
                    if (targetFace == null || !tankCanProvideToPipe(tankSeed, targetFace, tank)) continue;
                    if (!visited.add(tankSeed)) continue;
                    from.put(tankSeed, new Step(pos, face, tankSeed, targetFace));
                    queue.add(tankSeed);
                }
            }
        }

        if (!start.equals(target) && !from.containsKey(target)) return null;
        List<Step> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(start)) {
            Step step = from.get(cursor);
            if (step == null) return null;
            reversed.add(step);
            cursor = step.from;
        }
        List<Step> out = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) out.add(reversed.get(i));
        return out;
    }

    private static boolean tankBlocksPath(End source, FluidTankBlockEntity tank) {
        if (source.tank != null && source.tank.getController().equals(tank.getController())) return false;
        if (tank.getTankInventory().getFluidAmount() >= tank.getTankInventory().getCapacity()) return false;
        if (surface(tank) >= source.surface - DEAD_HEAD) return false;
        if (source.tank == null) return true;

        FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
        FluidStack targetFluid = tank.getTankInventory().getFluid();
        return targetFluid.isEmpty() || sourceFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
    }

    private static Direction faceToTank(Level level, FluidTankBlockEntity tank, BlockPos pipePos) {
        BlockPos controller = tank.getController();
        for (Direction direction : Direction.values()) {
            FluidTankBlockEntity adjacent = tankAt(level, pipePos.relative(direction));
            if (adjacent != null && adjacent.getController().equals(controller)) return direction;
        }
        return null;
    }

    private static void apply(Level level, End source, End target, List<Step> path, float share) {
        double head = source.surface - target.surface;
        float pressure = pressureForHead(head) * share * fluidPressureMultiplier(source);
        if (pressure <= 0) return;
        Map<BlockPos, Map<Direction, Boolean>> graph = new HashMap<>();

        graph.computeIfAbsent(source.pipe, $ -> new IdentityHashMap<>()).put(source.face, true);
        for (Step step : path) {
            graph.computeIfAbsent(step.from, $ -> new IdentityHashMap<>()).put(step.fromFace, false);
            graph.computeIfAbsent(step.to, $ -> new IdentityHashMap<>()).put(step.toFace, true);
        }
        graph.computeIfAbsent(target.pipe, $ -> new IdentityHashMap<>()).put(target.face, false);

        DebugInfo.log(level, "GRAPH apply source={} target={} head={} pressure={} share={} path={}", source.name(), target.name(), head, pressure, share, path.size());

        for (Map.Entry<BlockPos, Map<Direction, Boolean>> pipeEntry : graph.entrySet()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipeEntry.getKey());
            if (pipe == null) continue;
            for (Map.Entry<Direction, Boolean> side : pipeEntry.getValue().entrySet()) {
                pipe.addPressure(side.getKey(), side.getValue(), pressure);
                DebugInfo.log(level, "Added graph pressure pipePos={} side={} inbound={} pressure={}", pipeEntry.getKey(), side.getKey(), side.getValue(), pressure);
            }
        }

        for (BlockPos pipePos : graph.keySet()) FluidTransportBehaviour.cacheFlows(level, pipePos);
    }

    private static float fluidPressureMultiplier(End source) {
        if (source.tank == null) return 1.0f;
        FluidStack fluid = source.tank.getTankInventory().getFluid();
        return isLava(fluid) ? LAVA_PRESSURE_MULTIPLIER : 1.0f;
    }

    private static boolean isLava(FluidStack stack) {
        return !stack.isEmpty() && stack.getFluid() == Fluids.LAVA;
    }

    private static float pressureForHead(double head) {
        if (head < DEAD_HEAD) return 0.0f;
        if (head < TRICKLE_HEAD) {
            double t = (head - DEAD_HEAD) / (TRICKLE_HEAD - DEAD_HEAD);
            return (float) (TRICKLE_PRESSURE * t * (2.0 - t));
        }
        double t = Math.min(1.0, (head - TRICKLE_HEAD) / (MAX_HEAD - TRICKLE_HEAD));
        return (float) (MIN_PRESSURE + (t * t * (MAX_PRESSURE - MIN_PRESSURE)));
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

    private static double sourceCutoffSurface(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        BlockPos tankBlock = pipe.relative(face);
        double bottom = tank.getController().getY();
        double top = bottom + tank.getHeight();
        double cutoff = switch (face) {
            case UP -> tankBlock.getY();
            case DOWN -> tankBlock.getY() + 1.0;
            default -> tankBlock.getY();
        };
        return Math.max(bottom, Math.min(top, cutoff));
    }

    private static boolean tankCanProvideToPipe(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return false;
        double cutoffSurface = sourceCutoffSurface(pipe, face, tank);
        return surface(tank) > cutoffSurface + EPSILON && amount > amountForSurface(tank, cutoffSurface);
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        int capacity = tank.getTankInventory().getCapacity();
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(capacity, (int) Math.round(filledHeight * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        int capacity = tank.getTankInventory().getCapacity();
        int height = Math.max(1, tank.getHeight());
        return (double) capacity / (double) height;
    }

    private record Scan(Set<BlockPos> pipes, List<End> ends) {}
    private record Node(BlockPos pos, int distance) {}
    private record Step(BlockPos from, Direction fromFace, BlockPos to, Direction toFace) {}
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
            return new End(pipe, face, tank, surface(tank), tankCanProvideToPipe(pipe, face, tank), amount < capacity, false);
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
