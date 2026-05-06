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
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final int TANK_TRANSFER_CAP_MB = 100;
    private static final int LAVA_TANK_TRANSFER_CAP_MB = 25;
    private static final int TANK_TRANSFER_EPSILON_MB = 5;
    private static final double TANK_TRANSFER_FACTOR = 0.20;
    private static final double LAVA_TANK_TRANSFER_FACTOR = 0.08;
    private static final double TANK_RATE_FULL_HEAD = 4.0;
    private static final double TANK_MIN_RATE_MULTIPLIER = 0.05;
    private static final float LAVA_PRESSURE_MULTIPLIER = 0.35f;
    private static final float TRICKLE_PRESSURE = 8.0f;
    private static final float MIN_PRESSURE = 16.0f;
    private static final float MAX_PRESSURE = 256.0f;
    private static final double DEAD_HEAD = 0.125;
    private static final double TRICKLE_HEAD = 1.0;
    private static final double MAX_HEAD = 32.0;
    private static final double EPSILON = 0.01;

    public static void tickTank(FluidTankBlockEntity tank) {
        Level level = tank.getLevel();
        if (level == null || level.isClientSide || !tank.isController()) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        equalizeConnectedTanks(level, tank);
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

    private static void equalizeConnectedTanks(Level level, FluidTankBlockEntity tickTank) {
        TankNetwork network = tankNetwork(level, tickTank);
        if (network.tanks.size() < 2) return;
        if (!network.owner.getController().equals(tickTank.getController())) return;

        FluidStack groupFluid = groupFluid(network.tanks);
        if (groupFluid == null) return;

        int totalFluid = 0;
        for (FluidTankBlockEntity tank : network.tanks) totalFluid += tank.getTankInventory().getFluidAmount();
        if (totalFluid <= 0) return;

        boolean lava = isLava(groupFluid);
        int transferCap = lava ? LAVA_TANK_TRANSFER_CAP_MB : TANK_TRANSFER_CAP_MB;
        double transferFactor = lava ? LAVA_TANK_TRANSFER_FACTOR : TANK_TRANSFER_FACTOR;

        double targetSurface = sharedSurface(network.tanks, totalFluid);
        List<TankDelta> surplus = new ArrayList<>();
        List<TankDelta> deficit = new ArrayList<>();

        for (FluidTankBlockEntity tank : network.tanks) {
            int current = tank.getTankInventory().getFluidAmount();
            int target = amountForSurface(tank, targetSurface);
            int delta = target - current;
            if (delta > TANK_TRANSFER_EPSILON_MB) deficit.add(new TankDelta(tank, surface(tank), delta));
            if (delta < -TANK_TRANSFER_EPSILON_MB) surplus.add(new TankDelta(tank, surface(tank), -delta));
        }

        if (surplus.isEmpty() || deficit.isEmpty()) return;

        surplus.sort(Comparator.comparingDouble((TankDelta delta) -> delta.surface).reversed().thenComparing(delta -> delta.tank.getController(), TankPressureService::compareBlockPos));
        deficit.sort(Comparator.comparingDouble((TankDelta delta) -> delta.surface).thenComparing(delta -> delta.tank.getController(), TankPressureService::compareBlockPos));

        int totalOutPlan = 0;
        for (TankDelta from : surplus) {
            from.plan = plannedTankMove(from, targetSurface, transferFactor, transferCap);
            totalOutPlan += from.plan;
        }

        int totalInPlan = 0;
        for (TankDelta to : deficit) {
            to.plan = plannedTankMove(to, targetSurface, transferFactor, transferCap);
            totalInPlan += to.plan;
        }

        int transferBudget = Math.min(totalOutPlan, totalInPlan);
        if (transferBudget <= 0) return;

        scalePlans(deficit, totalInPlan, transferBudget);
        scalePlans(surplus, totalOutPlan, transferBudget);

        int movedTotal = 0;
        int sourceIndex = 0;
        int targetsMoved = 0;
        for (TankDelta to : deficit) {
            int remainingNeed = to.plan;
            int movedToTarget = 0;

            while (remainingNeed > 0 && sourceIndex < surplus.size()) {
                TankDelta from = surplus.get(sourceIndex);
                if (from.plan <= 0) {
                    sourceIndex++;
                    continue;
                }

                int requested = Math.min(remainingNeed, from.plan);
                int moved = moveFluid(from.tank, to.tank, requested);
                if (moved <= 0) {
                    from.plan = 0;
                    sourceIndex++;
                    continue;
                }

                from.plan -= moved;
                remainingNeed -= moved;
                movedToTarget += moved;
                movedTotal += moved;
            }

            if (movedToTarget > 0) targetsMoved++;
        }

        if (movedTotal > 0) DebugInfo.log(level, "TANK smooth source={} fluid={} tanks={} targetSurface={} moved={} targetsMoved={} sources={} cap={} factor={}", tickTank.getController(), groupFluid.getHoverName().getString(), network.tanks.size(), targetSurface, movedTotal, targetsMoved, surplus.size(), transferCap, transferFactor);
    }

    private static int plannedTankMove(TankDelta delta, double targetSurface, double transferFactor, int transferCap) {
        double head = Math.abs(delta.surface - targetSurface);
        double headMultiplier = Math.max(TANK_MIN_RATE_MULTIPLIER, Math.min(1.0, head / TANK_RATE_FULL_HEAD));
        int planned = (int) Math.ceil(delta.amount * transferFactor * headMultiplier);
        return Math.max(1, Math.min(Math.min(delta.amount, transferCap), planned));
    }

    private static void scalePlans(List<TankDelta> deltas, int totalPlan, int budget) {
        if (totalPlan <= budget) return;

        int remainingBudget = budget;
        int remainingPlan = totalPlan;
        for (int i = 0; i < deltas.size(); i++) {
            TankDelta delta = deltas.get(i);
            int original = delta.plan;

            if (i == deltas.size() - 1) delta.plan = Math.min(original, remainingBudget);
            else delta.plan = Math.min(original, Math.max(1, (int) Math.round((double) remainingBudget * original / remainingPlan)));

            remainingBudget -= delta.plan;
            remainingPlan -= original;
        }
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

    private static FluidStack groupFluid(List<FluidTankBlockEntity> tanks) {
        FluidStack groupFluid = FluidStack.EMPTY;
        for (FluidTankBlockEntity tank : tanks) {
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (fluid.isEmpty()) continue;
            if (groupFluid.isEmpty()) {
                groupFluid = fluid;
                continue;
            }
            if (!FluidStack.isSameFluidSameComponents(groupFluid, fluid)) return null;
        }
        return groupFluid;
    }

    private static TankNetwork tankNetwork(Level level, FluidTankBlockEntity start) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        Set<BlockPos> pipes = new HashSet<>();
        ArrayDeque<FluidTankBlockEntity> tankQueue = new ArrayDeque<>();
        ArrayDeque<BlockPos> pipeQueue = new ArrayDeque<>();

        addTank(tanks, tankQueue, start);

        while (!tankQueue.isEmpty() || !pipeQueue.isEmpty()) {
            while (!tankQueue.isEmpty()) {
                FluidTankBlockEntity tank = tankQueue.removeFirst();
                for (BlockPos seed : seeds(level, tank)) {
                    if (pipes.add(seed)) pipeQueue.add(seed);
                }
            }

            while (!pipeQueue.isEmpty()) {
                BlockPos pipePos = pipeQueue.removeFirst();
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
                if (pipe == null) continue;

                for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                    BlockPos other = pipePos.relative(face);
                    if (!level.isLoaded(other)) continue;

                    FluidTankBlockEntity tank = tankAt(level, other);
                    if (tank != null) {
                        addTank(tanks, tankQueue, tank);
                        continue;
                    }

                    if (FluidPropagator.getPipe(level, other) != null && pipes.add(other)) pipeQueue.add(other);
                }
            }
        }

        FluidTankBlockEntity owner = null;
        for (FluidTankBlockEntity tank : tanks.values()) {
            if (owner == null || compareBlockPos(tank.getController(), owner.getController()) < 0) owner = tank;
        }
        return new TankNetwork(new ArrayList<>(tanks.values()), owner == null ? start : owner);
    }

    private static void addTank(Map<BlockPos, FluidTankBlockEntity> tanks, ArrayDeque<FluidTankBlockEntity> queue, FluidTankBlockEntity tank) {
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        if (controller == null) controller = tank;
        if (tanks.putIfAbsent(controller.getController(), controller) == null) queue.add(controller);
    }

    private static double sharedSurface(List<FluidTankBlockEntity> tanks, int totalFluid) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (FluidTankBlockEntity tank : tanks) {
            double bottom = tank.getController().getY();
            low = Math.min(low, bottom);
            high = Math.max(high, bottom + tank.getHeight());
        }

        for (int i = 0; i < 48; i++) {
            double mid = (low + high) / 2.0;
            double volume = volumeAtSurface(tanks, mid);
            if (volume < totalFluid) low = mid;
            else high = mid;
        }
        return (low + high) / 2.0;
    }

    private static double volumeAtSurface(List<FluidTankBlockEntity> tanks, double surfaceY) {
        double total = 0.0;
        for (FluidTankBlockEntity tank : tanks) total += amountAtSurface(tank, surfaceY);
        return total;
    }

    private static double amountAtSurface(FluidTankBlockEntity tank, double surfaceY) {
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return filledHeight * layerCapacity(tank);
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        int capacity = tank.getTankInventory().getCapacity();
        return Math.max(0, Math.min(capacity, (int) Math.round(amountAtSurface(tank, surfaceY))));
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
                        for (BlockPos tankSeed : seeds(level, tank)) queue.add(new Node(tankSeed, node.distance + 1));
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
            if (source.tank != null && end.tank != null) continue;
            if (!compatible(source, end)) continue;
            if (end.surface >= source.surface - DEAD_HEAD) continue;
            targets.add(end);
        }
        targets.sort(Comparator.comparingDouble((End end) -> end.surface).thenComparingInt(end -> end.pipe.getY()));
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
            List<Step> path = path(level, scan, source.pipe, target.pipe);
            if (path != null) routes.add(new Route(target, path));
        }
        return routes;
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
                if (!level.isLoaded(other)) continue;

                if (scan.pipes.contains(other) && visited.add(other)) {
                    from.put(other, new Step(pos, face, other, face.getOpposite()));
                    queue.add(other);
                    continue;
                }

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank == null) continue;

                for (BlockPos tankSeed : seeds(level, tank)) {
                    if (tankSeed.equals(pos) || !scan.pipes.contains(tankSeed) || !visited.add(tankSeed)) continue;
                    Direction targetFace = faceToTank(level, tank, tankSeed);
                    if (targetFace == null) continue;
                    from.put(tankSeed, new Step(pos, face, tankSeed, targetFace));
                    queue.add(tankSeed);
                }
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

    private static double layerCapacity(FluidTankBlockEntity tank) {
        int capacity = tank.getTankInventory().getCapacity();
        int height = Math.max(1, tank.getHeight());
        return (double) capacity / (double) height;
    }

    private record Scan(Set<BlockPos> pipes, List<End> ends) {}
    private record Node(BlockPos pos, int distance) {}
    private record Step(BlockPos from, Direction fromFace, BlockPos to, Direction toFace) {}
    private record Route(End target, List<Step> path) {}
    private record TankNetwork(List<FluidTankBlockEntity> tanks, FluidTankBlockEntity owner) {}
    private static final class TankDelta {
        final FluidTankBlockEntity tank;
        final double surface;
        final int amount;
        int plan;

        TankDelta(FluidTankBlockEntity tank, double surface, int amount) {
            this.tank = tank;
            this.surface = surface;
            this.amount = amount;
        }
    }

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
