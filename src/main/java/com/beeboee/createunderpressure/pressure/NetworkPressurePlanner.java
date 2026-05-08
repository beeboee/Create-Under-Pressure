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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * One-authority pressure sim for a connected pipe network.
 *
 * This is intentionally conservative. The goal is to stop the old split-brain behavior
 * where TankPressureService, PressureSupplementService, and visuals each inferred a
 * slightly different network state.
 */
public final class NetworkPressurePlanner {
    private NetworkPressurePlanner() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int MAX_WORLD_SOURCE_SEARCH = 96;
    private static final int MAX_OUTLET_SEARCH = 20;
    private static final int MAX_TANK_MOVE_MB = 250;
    private static final int MAX_WORLD_INTAKE_MB = 250;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int WORLD_TRANSFER_INTERVAL = 10;
    private static final int SOURCE_SUPPRESS_TICKS = 80;
    private static final int SOURCE_BREAK_RADIUS = 2;
    private static final int SOURCE_BREAK_LIMIT = 6;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Integer>> SOURCE_DRAWS = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Long>> SUPPRESSED_SOURCES = new WeakHashMap<>();
    private static final Map<Level, Map<PipeFace, PlannedVisual>> VISUALS = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(scan.pipes);

        clearVisuals(level, scan);
        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            DebugInfo.log(level, "PLANNER scan owner={} pipes={} tanks={} openEnds={}", owner, scan.pipes.size(), scan.tankContacts.size(), scan.openEnds.size());

            int moved = 0;
            moved += fillTanksFromWorld(level, scan, MAX_WORLD_INTAKE_MB);
            moved += settleTanks(level, scan, MAX_TANK_MOVE_MB);
            moved += drainTanksToWorld(level, scan);
            moved += transferWorldToWorld(level, scan);

            DebugInfo.log(level, "PLANNER result owner={} moved={}mb", owner, moved);
        } finally {
            DebugInfo.endNetwork();
        }
    }

    public static PlannedVisual visualFor(Level level, BlockPos pipe, Direction face) {
        Map<PipeFace, PlannedVisual> map = VISUALS.get(level);
        return map == null ? null : map.get(new PipeFace(pipe, face));
    }

    private static ProcessedTick processed(Level level) {
        long time = level.getGameTime();
        ProcessedTick processed = PROCESSED.get(level);
        if (processed == null || processed.gameTime != time) {
            processed = new ProcessedTick(time, new HashSet<>());
            PROCESSED.put(level, processed);
        }
        return processed;
    }

    private static Scan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        List<TankContact> tankContacts = new ArrayList<>();
        List<OpenEnd> openEnds = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(seed, 0));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_SCAN_DISTANCE) continue;
            if (!level.isLoaded(node.pos) || !pipes.add(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;

            BlockState state = level.getBlockState(node.pos);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    FluidTankBlockEntity controller = controller(tank);
                    tankContacts.add(new TankContact(controller, node.pos, face));
                    for (BlockPos seedPipe : tankSeedPipes(level, controller)) {
                        if (!seedPipe.equals(node.pos)) queue.add(new Node(seedPipe, node.distance + 1));
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new Scan(pipes, dedupeTankContacts(tankContacts), dedupeOpenEnds(openEnds));
    }

    private static int fillTanksFromWorld(Level level, Scan scan, int budget) {
        if (budget <= 0 || scan.openEnds.isEmpty() || scan.tankContacts.isEmpty()) return 0;
        int moved = 0;

        for (OpenEnd end : scan.openEnds) {
            if (moved >= budget) break;
            BlockPos touched = end.worldPos();
            FluidState state = level.getFluidState(touched);
            if (state.isEmpty()) continue;

            WorldSource source = resolveWorldSource(level, touched, state.getType());
            if (source == null) {
                DebugInfo.log(level, "PLANNER world intake skip touched={} reason=noSource", touched);
                continue;
            }

            double sourceHead = source.source.getY() + 1.0;
            List<TankContact> targets = reachableTankContacts(level, scan, end.pipe, sourceHead);
            targets.sort(Comparator
                    .comparingDouble((TankContact contact) -> surface(contact.tank))
                    .thenComparingInt(contact -> contact.pipe.distManhattan(end.pipe))
                    .thenComparing(contact -> contact.tank.getController(), NetworkPressurePlanner::compareBlockPos));

            for (TankContact target : targets) {
                if (moved >= budget) break;
                if (!canFillWith(target.tank, source.fluid)) continue;
                if (target.cutoffSurface() > sourceHead + EPSILON) continue;

                int maxAtHead = amountForSurface(target.tank, Math.min(sourceHead, target.topY()));
                int needed = maxAtHead - target.tank.getTankInventory().getFluidAmount();
                if (needed <= 0) continue;

                int request = Math.min(budget - moved, Math.min(MAX_WORLD_INTAKE_MB, needed));
                int filled = target.tank.getTankInventory().fill(new FluidStack(source.fluid, request), FluidAction.EXECUTE);
                if (filled <= 0) continue;

                moved += filled;
                addVisual(level, end, PlannedVisual.INTAKE);
                recordWorldDraw(level, source, filled);
                DebugInfo.log(level, "PLANNER world->tank source={} touched={} target={} targetPipe={} moved={} sourceHead={} targetSurface={} cutoff={} sourceDistance={}",
                        source.source, touched, target.tank.getController(), target.pipe, filled, sourceHead, surface(target.tank), target.cutoffSurface(), source.distance);
            }
        }

        return moved;
    }

    private static int settleTanks(Level level, Scan scan, int budget) {
        if (budget <= 0 || scan.tankContacts.size() < 2) return 0;
        int moved = 0;
        List<TankContact> sources = new ArrayList<>(scan.tankContacts);
        sources.sort(Comparator.comparingDouble((TankContact contact) -> surface(contact.tank)).reversed());

        for (TankContact source : sources) {
            if (moved >= budget) break;
            if (!canProvide(source)) continue;
            FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
            if (sourceFluid.isEmpty()) continue;
            double sourceSurface = surface(source.tank);

            List<TankContact> targets = reachableTankContacts(level, scan, source.pipe, sourceSurface);
            targets.removeIf(target -> sameTank(source.tank, target.tank)
                    || !canFillWith(target.tank, sourceFluid.getFluid())
                    || target.cutoffSurface() > sourceSurface + EPSILON
                    || surface(target.tank) >= sourceSurface - DEAD_BAND);
            targets.sort(Comparator
                    .comparingDouble((TankContact target) -> surface(target.tank))
                    .thenComparingInt(target -> target.pipe.distManhattan(source.pipe))
                    .thenComparing(target -> target.tank.getController(), NetworkPressurePlanner::compareBlockPos));

            for (TankContact target : targets) {
                if (moved >= budget) break;
                int sourceFloor = amountForSurface(source.tank, Math.max(source.cutoffSurface(), surface(target.tank)));
                int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
                if (available <= 0) continue;

                int targetMax = amountForSurface(target.tank, Math.min(sourceSurface, target.topY()));
                int needed = targetMax - target.tank.getTankInventory().getFluidAmount();
                if (needed <= 0) continue;

                int request = Math.min(budget - moved, Math.min(available, needed));
                int actual = moveFluid(source.tank, target.tank, request);
                if (actual <= 0) continue;

                moved += actual;
                DebugInfo.log(level, "PLANNER tank->tank source={} target={} sourcePipe={} targetPipe={} moved={} sourceSurface={} targetSurface={} sourceCutoff={} targetCutoff={}",
                        source.tank.getController(), target.tank.getController(), source.pipe, target.pipe, actual, sourceSurface, surface(target.tank), source.cutoffSurface(), target.cutoffSurface());
            }
        }

        return moved;
    }

    private static int drainTanksToWorld(Level level, Scan scan) {
        if (scan.tankContacts.isEmpty() || scan.openEnds.isEmpty()) return 0;

        List<OpenEnd> outlets = new ArrayList<>(scan.openEnds);
        outlets.removeIf(outlet -> !level.isLoaded(outlet.worldPos()) || !level.getBlockState(outlet.worldPos()).isAir());
        outlets.sort(Comparator
                .comparingInt((OpenEnd end) -> end.worldPos().getY())
                .thenComparing(end -> end.worldPos(), NetworkPressurePlanner::compareBlockPos));

        for (OpenEnd outlet : outlets) {
            double outletHead = outlet.worldPos().getY();
            List<TankContact> sources = reachableTankContacts(level, scan, outlet.pipe, 256.0);
            sources.removeIf(source -> !canProvide(source) || surface(source.tank) <= outletHead + DEAD_BAND);
            sources.sort(Comparator
                    .comparingDouble((TankContact source) -> surface(source.tank)).reversed()
                    .thenComparingInt(source -> source.pipe.distManhattan(outlet.pipe)));

            for (TankContact source : sources) {
                FluidStack stack = source.tank.getTankInventory().getFluid();
                if (stack.isEmpty() || !canPlaceFluidBlock(stack.getFluid())) continue;
                if (!reachableWithinHead(level, scan, source.pipe, outlet.pipe, surface(source.tank))) continue;

                int sourceFloor = amountForSurface(source.tank, source.cutoffSurface());
                int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
                if (available < WORLD_BLOCK_MB) {
                    DebugInfo.log(level, "PLANNER tank->world skip source={} outlet={} reason=availableBelow1000 available={}", source.tank.getController(), outlet.worldPos(), available);
                    continue;
                }

                if (!placeFluid(level, outlet.worldPos(), stack.getFluid())) continue;
                FluidStack drained = source.tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty()) {
                    level.setBlockAndUpdate(outlet.worldPos(), Blocks.AIR.defaultBlockState());
                    continue;
                }

                addVisual(level, outlet, PlannedVisual.OUTPUT);
                DebugInfo.log(level, "PLANNER tank->world source={} sourcePipe={} outlet={} outletPipe={} moved={} sourceSurface={} cutoff={} outletHead={}",
                        source.tank.getController(), source.pipe, outlet.worldPos(), outlet.pipe, drained.getAmount(), surface(source.tank), source.cutoffSurface(), outletHead);
                return drained.getAmount();
            }

            DebugInfo.log(level, "PLANNER tank->world idle outlet={} reason=noEligibleSource", outlet.worldPos());
        }

        return 0;
    }

    private static int transferWorldToWorld(Level level, Scan scan) {
        if (!scan.tankContacts.isEmpty() || scan.openEnds.size() < 2) return 0;
        if (level.getGameTime() % WORLD_TRANSFER_INTERVAL != 0) return 0;

        for (OpenEnd sourceEnd : scan.openEnds) {
            BlockPos touched = sourceEnd.worldPos();
            FluidState state = level.getFluidState(touched);
            if (state.isEmpty()) continue;

            WorldSource source = resolveWorldSource(level, touched, state.getType());
            if (source == null) continue;

            List<WorldTarget> targets = new ArrayList<>();
            for (OpenEnd outlet : scan.openEnds) {
                if (outlet.equals(sourceEnd)) continue;
                int capY = outlet.worldPos().getY();
                if (capY > source.source.getY()) {
                    DebugInfo.log(level, "PLANNER world->world reject source={} outlet={} reason=wouldPushUp capY={}", source.source, outlet.worldPos(), capY);
                    continue;
                }
                if (sameFluidBody(level, source.source, outlet.worldPos(), source.fluid)) {
                    DebugInfo.log(level, "PLANNER world->world skip source={} outlet={} reason=sameBody", source.source, outlet.worldPos());
                    continue;
                }
                if (!reachableWithinHead(level, scan, sourceEnd.pipe, outlet.pipe, source.source.getY() + 1.0)) continue;

                BlockPos place = outletPlacement(level, outlet.worldPos(), source.fluid, capY, source.source);
                if (place == null) continue;
                targets.add(new WorldTarget(outlet, place, outlet.worldPos().distManhattan(place), isFlowing(level, place, source.fluid)));
            }

            targets.sort(Comparator
                    .comparing((WorldTarget target) -> !target.flowing)
                    .thenComparingInt(target -> target.place.getY())
                    .thenComparingInt(target -> target.distance)
                    .thenComparing(target -> target.place, NetworkPressurePlanner::compareBlockPos));

            for (WorldTarget target : targets) {
                if (!placeFluid(level, target.place, source.fluid)) continue;
                recordWorldDraw(level, source, WORLD_BLOCK_MB);
                addVisual(level, sourceEnd, PlannedVisual.INTAKE);
                addVisual(level, target.end, PlannedVisual.OUTPUT);
                DebugInfo.log(level, "PLANNER world->world source={} touched={} target={} outlet={} moved=1000 flowingTarget={} sourceDistance={}",
                        source.source, touched, target.place, target.end.worldPos(), target.flowing, source.distance);
                return WORLD_BLOCK_MB;
            }
        }

        return 0;
    }

    private static WorldSource resolveWorldSource(Level level, BlockPos touched, Fluid touchedFluid) {
        if (recentlySuppressed(level, touched)) return null;
        List<WorldSource> candidates = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(touched, 0));
        visited.add(touched);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_WORLD_SOURCE_SEARCH) continue;
            FluidState state = level.getFluidState(node.pos);
            if (state.isEmpty() || !sameFluidKind(state.getType(), touchedFluid)) continue;
            if (state.isSource() && !recentlySuppressed(level, node.pos)) {
                candidates.add(new WorldSource(touched, node.pos, sourceFluidFor(state.getType()), node.distance));
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) continue;
                FluidState nextState = level.getFluidState(next);
                if (!nextState.isEmpty() && sameFluidKind(nextState.getType(), touchedFluid)) queue.add(new Node(next, node.distance + 1));
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator
                .comparingInt((WorldSource source) -> source.source.getY()).reversed()
                .thenComparingInt(source -> source.distance)
                .thenComparing(source -> source.source, NetworkPressurePlanner::compareBlockPos));
        WorldSource chosen = candidates.get(0);
        DebugInfo.log(level, "PLANNER source resolved touched={} source={} distance={} candidates={} fluid={}", touched, chosen.source, chosen.distance, candidates.size(), chosen.fluid);
        return chosen;
    }

    private static void recordWorldDraw(Level level, WorldSource source, int amount) {
        Map<BlockPos, Integer> draws = SOURCE_DRAWS.computeIfAbsent(level, $ -> new HashMap<>());
        int total = draws.getOrDefault(source.source, 0) + amount;
        if (total < WORLD_BLOCK_MB) {
            draws.put(source.source, total);
            return;
        }

        draws.remove(source.source);
        hardDeleteSource(level, source.source, source.fluid);
        DebugInfo.log(level, "PLANNER source consumed source={} touched={} pulled={} fluid={}", source.source, source.touched, total, source.fluid);
    }

    private static void hardDeleteSource(Level level, BlockPos source, Fluid fluid) {
        deleteFluid(level, source, fluid);
        suppress(level, source);

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(source, 0));
        visited.add(source);
        int deleted = 0;

        while (!queue.isEmpty() && deleted < SOURCE_BREAK_LIMIT) {
            Node node = queue.removeFirst();
            if (node.distance >= SOURCE_BREAK_RADIUS) continue;
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) continue;
                FluidState state = level.getFluidState(next);
                if (state.isEmpty() || !sameFluidKind(state.getType(), fluid)) continue;
                queue.add(new Node(next, node.distance + 1));
                if (state.isSource()) {
                    deleteFluid(level, next, fluid);
                    suppress(level, next);
                    deleted++;
                    DebugInfo.log(level, "PLANNER source breaker deleted={} around={}", next, source);
                    if (deleted >= SOURCE_BREAK_LIMIT) break;
                }
            }
        }
    }

    private static void deleteFluid(Level level, BlockPos pos, Fluid fluid) {
        FluidState state = level.getFluidState(pos);
        if (!state.isEmpty() && sameFluidKind(state.getType(), fluid)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private static boolean recentlySuppressed(Level level, BlockPos pos) {
        Map<BlockPos, Long> map = SUPPRESSED_SOURCES.get(level);
        if (map == null) return false;
        Long until = map.get(pos);
        if (until == null) return false;
        if (level.getGameTime() <= until) return true;
        map.remove(pos);
        return false;
    }

    private static void suppress(Level level, BlockPos pos) {
        SUPPRESSED_SOURCES.computeIfAbsent(level, $ -> new HashMap<>()).put(pos, level.getGameTime() + SOURCE_SUPPRESS_TICKS);
    }

    private static List<TankContact> reachableTankContacts(Level level, Scan scan, BlockPos startPipe, double maxHead) {
        List<TankContact> reachable = new ArrayList<>();
        for (TankContact contact : scan.tankContacts) {
            if (reachableWithinHead(level, scan, startPipe, contact.pipe, maxHead)) reachable.add(contact);
        }
        return reachable;
    }

    private static boolean reachableWithinHead(Level level, Scan scan, BlockPos startPipe, BlockPos targetPipe, double maxHead) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPipe);
        visited.add(startPipe);
        while (!queue.isEmpty()) {
            BlockPos pipePos = queue.removeFirst();
            if (pipePos.equals(targetPipe)) return true;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                BlockPos next = pipePos.relative(face);
                if (!scan.pipes.contains(next) || !visited.add(next)) continue;
                if (next.getY() > maxHead + EPSILON) continue;
                queue.add(next);
            }
        }
        return false;
    }

    private static BlockPos outletPlacement(Level level, BlockPos outlet, Fluid fluid, int capY, BlockPos source) {
        if (canPlaceAtOutlet(level, outlet, fluid, capY) && !sameFluidBody(level, source, outlet, fluid)) return outlet;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(outlet, 0));
        visited.add(outlet);
        BlockPos bestAir = null;
        int bestAirDistance = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_OUTLET_SEARCH) continue;
            if (isFlowing(level, node.pos, fluid) && node.pos.getY() <= capY && !sameFluidBody(level, source, node.pos, fluid)) return node.pos;
            if (level.getBlockState(node.pos).isAir() && node.pos.getY() <= capY && node.distance < bestAirDistance) {
                bestAir = node.pos;
                bestAirDistance = node.distance;
            }
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next) || next.getY() > capY) continue;
                FluidState state = level.getFluidState(next);
                if (level.getBlockState(next).isAir() || (!state.isEmpty() && sameFluidKind(state.getType(), fluid))) queue.add(new Node(next, node.distance + 1));
            }
        }
        return bestAir;
    }

    private static boolean sameFluidBody(Level level, BlockPos a, BlockPos b, Fluid fluid) {
        if (a.equals(b)) return true;
        FluidState aState = level.getFluidState(a);
        FluidState bState = level.getFluidState(b);
        if (aState.isEmpty() || bState.isEmpty()) return false;
        if (!sameFluidKind(aState.getType(), fluid) || !sameFluidKind(bState.getType(), fluid)) return false;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(a, 0));
        visited.add(a);
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_WORLD_SOURCE_SEARCH) continue;
            if (node.pos.equals(b)) return true;
            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) continue;
                FluidState state = level.getFluidState(next);
                if (!state.isEmpty() && sameFluidKind(state.getType(), fluid)) queue.add(new Node(next, node.distance + 1));
            }
        }
        return false;
    }

    private static boolean canPlaceAtOutlet(Level level, BlockPos pos, Fluid fluid, int capY) {
        if (pos.getY() > capY) return false;
        if (level.getBlockState(pos).isAir()) return true;
        FluidState state = level.getFluidState(pos);
        return !state.isEmpty() && sameFluidKind(state.getType(), fluid) && !state.isSource();
    }

    private static boolean placeFluid(Level level, BlockPos pos, Fluid fluid) {
        if (isWater(fluid)) return level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        if (isLava(fluid)) return level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
        return false;
    }

    private static boolean isFlowing(Level level, BlockPos pos, Fluid fluid) {
        FluidState state = level.getFluidState(pos);
        return !state.isEmpty() && sameFluidKind(state.getType(), fluid) && !state.isSource();
    }

    private static int moveFluid(FluidTankBlockEntity from, FluidTankBlockEntity to, int amount) {
        FluidStack simulated = from.getTankInventory().drain(amount, FluidAction.SIMULATE);
        if (simulated.isEmpty()) return 0;
        int canFill = to.getTankInventory().fill(simulated, FluidAction.SIMULATE);
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

    private static boolean canProvide(TankContact contact) {
        return contact.tank.getTankInventory().getFluidAmount() > amountForSurface(contact.tank, contact.cutoffSurface())
                && surface(contact.tank) > contact.cutoffSurface() + EPSILON;
    }

    private static boolean canFillWith(FluidTankBlockEntity tank, Fluid fluid) {
        FluidStack stack = tank.getTankInventory().getFluid();
        return stack.isEmpty() || sameFluidKind(stack.getFluid(), fluid);
    }

    private static boolean canPlaceFluidBlock(Fluid fluid) {
        return isWater(fluid) || isLava(fluid);
    }

    private static boolean sameTank(FluidTankBlockEntity a, FluidTankBlockEntity b) {
        return a.getController().equals(b.getController());
    }

    private static double surface(FluidTankBlockEntity tank) {
        return surface(tank, tank.getTankInventory().getFluidAmount());
    }

    private static double surface(FluidTankBlockEntity tank, int amount) {
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(tank.getTankInventory().getCapacity(), (int) Math.round(filledHeight * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / (double) Math.max(1, tank.getHeight());
    }

    private static double cutoffSurface(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
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

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        return controller(tank);
    }

    private static FluidTankBlockEntity controller(FluidTankBlockEntity tank) {
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static Set<BlockPos> tankSeedPipes(Level level, FluidTankBlockEntity tank) {
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

    private static List<TankContact> dedupeTankContacts(List<TankContact> contacts) {
        Map<String, TankContact> deduped = new HashMap<>();
        for (TankContact contact : contacts) deduped.putIfAbsent(contact.tank.getController() + "|" + contact.pipe + "|" + contact.face, contact);
        return new ArrayList<>(deduped.values());
    }

    private static List<OpenEnd> dedupeOpenEnds(List<OpenEnd> ends) {
        Map<String, OpenEnd> deduped = new HashMap<>();
        for (OpenEnd end : ends) deduped.putIfAbsent(end.pipe + "|" + end.face, end);
        return new ArrayList<>(deduped.values());
    }

    private static BlockPos ownerPipe(Set<BlockPos> pipes) {
        BlockPos owner = null;
        for (BlockPos pipe : pipes) if (owner == null || compareBlockPos(pipe, owner) < 0) owner = pipe;
        return owner;
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static boolean sameFluidKind(Fluid a, Fluid b) {
        return (isWater(a) && isWater(b)) || (isLava(a) && isLava(b));
    }

    private static Fluid sourceFluidFor(Fluid fluid) {
        if (isWater(fluid)) return Fluids.WATER;
        if (isLava(fluid)) return Fluids.LAVA;
        return fluid;
    }

    private static boolean isWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isLava(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private static void clearVisuals(Level level, Scan scan) {
        Map<PipeFace, PlannedVisual> map = VISUALS.computeIfAbsent(level, $ -> new HashMap<>());
        for (OpenEnd end : scan.openEnds) map.remove(new PipeFace(end.pipe, end.face));
    }

    private static void addVisual(Level level, OpenEnd end, PlannedVisual action) {
        VISUALS.computeIfAbsent(level, $ -> new HashMap<>()).put(new PipeFace(end.pipe, end.face), action);
    }

    public enum PlannedVisual { INTAKE, OUTPUT }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> tankContacts, List<OpenEnd> openEnds) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() { return NetworkPressurePlanner.cutoffSurface(pipe, face, tank); }
        double topY() { return tank.getController().getY() + tank.getHeight(); }
    }
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() { return pipe.relative(face); }
    }
    private record WorldSource(BlockPos touched, BlockPos source, Fluid fluid, int distance) {}
    private record WorldTarget(OpenEnd end, BlockPos place, int distance, boolean flowing) {}
    private record PipeFace(BlockPos pipe, Direction face) {}
}
