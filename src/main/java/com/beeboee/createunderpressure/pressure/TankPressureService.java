package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final int MAX_ROUTES = 32;
    private static final int MAX_HYDRAULIC_SETTLE_MB = 250;
    private static final int SETTLE_EPSILON_MB = 25;

    private static final float LAVA_PRESSURE_MULTIPLIER = 0.35f;
    private static final float TRICKLE_PRESSURE = 8.0f;
    private static final float MAX_PRESSURE = 256.0f;

    private static final float TANK_SETTLE_MAX_PRESSURE = 0.25f;
    private static final double TANK_SETTLE_DEADBAND = 0.35;
    private static final double TANK_SETTLE_FULL_HEAD = 4.0;

    private static final double DEAD_HEAD = 0.125;
    private static final double TANK_SETTLE_HEAD = 0.0;
    private static final double TRICKLE_HEAD = 1.0;
    private static final double MAX_HEAD = 32.0;
    private static final double PIPE_RESISTANCE_PER_STEP = 0.08;
    private static final double EPSILON = 0.01;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickTank(FluidTankBlockEntity tank) {
        // Intentionally disabled. Tank movement should come from connected pipe pressure,
        // not direct cosmetic tank-to-tank inventory transfers.
    }

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan == null || scan.pipes.isEmpty()) return;
        if (!ownsPipeNetwork(scan, seed)) return;
        if (overlaps(scan.pipes, processed.pipes)) return;
        processed.pipes.addAll(scan.pipes);

        int settled = settleConnectedTanks(level, scan);
        if (settled > 0) DebugInfo.log(level, "HYDRAULIC settle owner={} moved={}mb", seed, settled);

        List<PressureRoute> routes = pressureRoutes(level, scan);
        if (routes.isEmpty()) {
            DebugInfo.log(level, "GRAPH idle owner={} endpoints={} pipes={}", seed, scan.ends.size(), scan.pipes.size());
            return;
        }

        DebugInfo.log(level, "GRAPH settle owner={} routes={} endpoints={} pipes={}", seed, routes.size(), scan.ends.size(), scan.pipes.size());
        for (PressureRoute route : routes) apply(level, route);
    }

    private static ProcessedTick processed(Level level) {
        long gameTime = level.getGameTime();
        ProcessedTick processed = PROCESSED.get(level);
        if (processed == null || processed.gameTime != gameTime) {
            processed = new ProcessedTick(gameTime, new HashSet<>());
            PROCESSED.put(level, processed);
        }
        return processed;
    }

    private static boolean overlaps(Set<BlockPos> scanPipes, Set<BlockPos> processedPipes) {
        for (BlockPos pipe : scanPipes) {
            if (processedPipes.contains(pipe)) return true;
        }
        return false;
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

    private static int settleConnectedTanks(Level level, Scan scan) {
        List<FluidTankBlockEntity> tanks = uniqueTanks(scan);
        if (tanks.size() < 2) return 0;
        if (!singleFluidGroup(tanks)) return 0;

        int total = 0;
        int totalCapacity = 0;
        for (FluidTankBlockEntity tank : tanks) {
            total += tank.getTankInventory().getFluidAmount();
            totalCapacity += tank.getTankInventory().getCapacity();
        }
        if (total <= 0 || total >= totalCapacity) return 0;

        double targetSurface = networkSurfaceForAmount(tanks, total);
        List<TankDelta> sources = new ArrayList<>();
        List<TankDelta> targets = new ArrayList<>();

        for (FluidTankBlockEntity tank : tanks) {
            int current = tank.getTankInventory().getFluidAmount();
            int target = amountForSurface(tank, targetSurface);
            int delta = current - target;
            if (delta > SETTLE_EPSILON_MB) sources.add(new TankDelta(tank, delta));
            if (delta < -SETTLE_EPSILON_MB) targets.add(new TankDelta(tank, -delta));
        }

        if (sources.isEmpty() || targets.isEmpty()) return 0;
        sources.sort((a, b) -> Double.compare(surface(b.tank) - targetSurface, surface(a.tank) - targetSurface));
        targets.sort((a, b) -> Double.compare(targetSurface - surface(b.tank), targetSurface - surface(a.tank)));

        int movedTotal = 0;
        int sourceIndex = 0;
        for (TankDelta target : targets) {
            int needed = target.amount;
            while (needed > SETTLE_EPSILON_MB && sourceIndex < sources.size()) {
                TankDelta source = sources.get(sourceIndex);
                if (source.amount <= SETTLE_EPSILON_MB) {
                    sourceIndex++;
                    continue;
                }

                int move = Math.min(MAX_HYDRAULIC_SETTLE_MB - movedTotal, Math.min(source.amount, needed));
                if (move <= 0) return movedTotal;

                int moved = moveFluid(source.tank, target.tank, move);
                if (moved <= 0) {
                    sourceIndex++;
                    continue;
                }

                movedTotal += moved;
                source.amount -= moved;
                needed -= moved;

                if (movedTotal >= MAX_HYDRAULIC_SETTLE_MB) return movedTotal;
            }
        }

        if (movedTotal > 0) {
            DebugInfo.log(level, "HYDRAULIC targetSurface={} tanks={} moved={}mb", targetSurface, tanks.size(), movedTotal);
        }
        return movedTotal;
    }

    private static List<FluidTankBlockEntity> uniqueTanks(Scan scan) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        for (End end : scan.ends) {
            if (end.tank != null) tanks.putIfAbsent(end.tank.getController(), end.tank);
        }
        return new ArrayList<>(tanks.values());
    }

    private static boolean singleFluidGroup(List<FluidTankBlockEntity> tanks) {
        FluidStack group = FluidStack.EMPTY;
        for (FluidTankBlockEntity tank : tanks) {
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (fluid.isEmpty()) continue;
            if (group.isEmpty()) {
                group = fluid;
                continue;
            }
            if (!FluidStack.isSameFluidSameComponents(group, fluid)) return false;
        }
        return !group.isEmpty();
    }

    private static double networkSurfaceForAmount(List<FluidTankBlockEntity> tanks, int totalAmount) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (FluidTankBlockEntity tank : tanks) {
            low = Math.min(low, tank.getController().getY());
            high = Math.max(high, tank.getController().getY() + tank.getHeight());
        }

        for (int i = 0; i < 48; i++) {
            double mid = (low + high) * 0.5;
            int amount = 0;
            for (FluidTankBlockEntity tank : tanks) amount += amountForSurface(tank, mid);
            if (amount < totalAmount) low = mid;
            else high = mid;
        }

        return (low + high) * 0.5;
    }

    private static int moveFluid(FluidTankBlockEntity from, FluidTankBlockEntity to, int amount) {
        FluidStack simulatedDrain = from.getTankInventory().drain(amount, FluidAction.SIMULATE);
        if (simulatedDrain.isEmpty()) return 0;

        int canFill = to.getTankInventory().fill(simulatedDrain, FluidAction.SIMULATE);
        int actual = Math.min(amount, canFill);
        if (actual <= 0) return 0;

        FluidStack drained = from.getTankInventory().drain(actual, FluidAction.EXECUTE);
        if (drained.isEmpty()) return 0;

        int filled = to.getTankInventory().fill(drained, FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            FluidStack remainder = drained.copy();
            remainder.setAmount(drained.getAmount() - filled);
            from.getTankInventory().fill(remainder, FluidAction.EXECUTE);
        }
        return filled;
    }

    private static List<PressureRoute> pressureRoutes(Level level, Scan scan) {
        Map<String, PressureRoute> bestRoutesByTarget = new HashMap<>();

        for (End source : scan.ends) {
            if (!source.provides) continue;

            for (End target : scan.ends) {
                if (source == target) continue;
                if (!canReceiveFrom(source, target)) continue;
                if (tankToTank(source, target)) continue;

                double head = source.head - target.head;
                double requiredHead = requiredHead(source, target);
                if (head <= requiredHead + EPSILON) continue;

                List<Step> path = path(level, scan, source, target.pipe);
                if (path == null) continue;

                double activeHead = head - requiredHead;
                double conductance = conductance(path);
                float pressure = routePressure(source, target, activeHead, conductance);
                if (pressure <= 0) continue;

                PressureRoute route = new PressureRoute(source, target, path, head, activeHead, conductance, pressure);
                String key = target.nodeKey();
                PressureRoute previous = bestRoutesByTarget.get(key);
                if (previous == null || routeScore(route) > routeScore(previous)) bestRoutesByTarget.put(key, route);
            }
        }

        List<PressureRoute> routes = new ArrayList<>(bestRoutesByTarget.values());
        routes.sort((a, b) -> {
            int pressureCompare = Float.compare(b.pressure, a.pressure);
            if (pressureCompare != 0) return pressureCompare;
            int sourceCompare = a.source.nodeKey().compareTo(b.source.nodeKey());
            if (sourceCompare != 0) return sourceCompare;
            return a.target.nodeKey().compareTo(b.target.nodeKey());
        });

        if (routes.size() <= MAX_ROUTES) return routes;
        return new ArrayList<>(routes.subList(0, MAX_ROUTES));
    }

    private static float routePressure(End source, End target, double activeHead, double conductance) {
        if (tankToTank(source, target)) return tankSettlePressure(source, activeHead, conductance);
        return pressureForHead(activeHead) * (float) conductance * pressureMultiplier(source);
    }

    private static float tankSettlePressure(End source, double activeHead, double conductance) {
        if (activeHead <= TANK_SETTLE_DEADBAND) return 0.0f;

        double t = (activeHead - TANK_SETTLE_DEADBAND) / (TANK_SETTLE_FULL_HEAD - TANK_SETTLE_DEADBAND);
        t = Math.max(0.0, Math.min(1.0, t));
        double eased = t * t * (3.0 - (2.0 * t));

        return (float) (TANK_SETTLE_MAX_PRESSURE * eased * conductance * pressureMultiplier(source));
    }

    private static double routeScore(PressureRoute route) {
        return route.activeHead + (route.pressure * 0.01) - (route.path.size() * 0.001);
    }

    private static boolean canReceiveFrom(End source, End target) {
        if (!target.receives) return false;
        if (sameTank(source, target)) return false;
        return compatible(source, target);
    }

    private static double requiredHead(End source, End target) {
        return tankToTank(source, target) ? TANK_SETTLE_HEAD : DEAD_HEAD;
    }

    private static boolean compatible(End source, End target) {
        if (source.tank == null || target.tank == null) return true;

        FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
        FluidStack targetFluid = target.tank.getTankInventory().getFluid();
        return sourceFluid.isEmpty() || targetFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
    }

    private static boolean tankToTank(End source, End target) {
        return source.tank != null && target.tank != null;
    }

    private static double conductance(List<Step> path) {
        return 1.0 / (1.0 + (path.size() * PIPE_RESISTANCE_PER_STEP));
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
        if (surface(tank) >= source.head - TANK_SETTLE_HEAD) return false;
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

    private static void apply(Level level, PressureRoute route) {
        Map<BlockPos, Map<Direction, Boolean>> graph = new HashMap<>();

        graph.computeIfAbsent(route.source.pipe, $ -> new IdentityHashMap<>()).put(route.source.face, true);
        for (Step step : route.path) {
            graph.computeIfAbsent(step.from, $ -> new IdentityHashMap<>()).put(step.fromFace, false);
            graph.computeIfAbsent(step.to, $ -> new IdentityHashMap<>()).put(step.toFace, true);
        }
        graph.computeIfAbsent(route.target.pipe, $ -> new IdentityHashMap<>()).put(route.target.face, false);

        DebugInfo.log(level, "GRAPH route source={} target={} head={} activeHead={} pressure={} conductance={} path={}",
                route.source.name(), route.target.name(), route.head, route.activeHead, route.pressure, route.conductance, route.path.size());

        for (Map.Entry<BlockPos, Map<Direction, Boolean>> pipeEntry : graph.entrySet()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipeEntry.getKey());
            if (pipe == null) continue;
            for (Map.Entry<Direction, Boolean> side : pipeEntry.getValue().entrySet()) {
                pipe.addPressure(side.getKey(), side.getValue(), route.pressure);
                DebugInfo.log(level, "Added graph pressure pipePos={} side={} inbound={} pressure={}", pipeEntry.getKey(), side.getKey(), side.getValue(), route.pressure);
            }
        }

        for (BlockPos pipePos : graph.keySet()) FluidTransportBehaviour.cacheFlows(level, pipePos);
    }

    private static float pressureMultiplier(End source) {
        return fluidPressureMultiplier(source);
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
        return (float) (TRICKLE_PRESSURE + (t * t * (MAX_PRESSURE - TRICKLE_PRESSURE)));
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
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
    private record TankDelta(FluidTankBlockEntity tank, int amount) {
        private TankDelta withAmount(int amount) {
            return new TankDelta(tank, amount);
        }
    }
    private record PressureRoute(End source, End target, List<Step> path, double head, double activeHead, double conductance, float pressure) {}
    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}

    private static final class End {
        final BlockPos pipe;
        final Direction face;
        final FluidTankBlockEntity tank;
        final double head;
        final boolean provides;
        final boolean receives;
        final boolean openEnd;

        private End(BlockPos pipe, Direction face, FluidTankBlockEntity tank, double head, boolean provides, boolean receives, boolean openEnd) {
            this.pipe = pipe;
            this.face = face;
            this.tank = tank;
            this.head = head;
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

        String nodeKey() {
            if (tank != null) return "tank:" + tank.getController();
            return "external:" + pipe.relative(face);
        }

        String name() {
            if (tank != null) return "tank@" + tank.getController() + "/pipe=" + pipe + "/face=" + face;
            return "external@" + pipe.relative(face) + "/pipe=" + pipe + "/face=" + face + "/open=" + openEnd;
        }
    }
}
