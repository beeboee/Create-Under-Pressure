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
 * Small, conservative sim-side corrections layered on top of TankPressureService.
 *
 * This exists so we can spot-fix the current test failures without making the main
 * pressure service even more tangled. It can be folded back into TankPressureService
 * once the behavior is proven.
 */
public final class PressureSupplementService {
    private PressureSupplementService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int WORLD_TRANSFER_INTERVAL = 10;
    private static final int MAX_SCAN_DISTANCE = 96;
    private static final int MAX_TANK_SETTLE_MB = 125;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int MAX_OUTLET_SEARCH = 16;
    private static final int MOVED_SOURCE_SUPPRESSION_TICKS = 60;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;

    private static final Direction[] SOURCE_BREAK_DIRECTIONS = new Direction[] {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP
    };

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Long>> RECENTLY_MOVED_SOURCES = new WeakHashMap<>();

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

        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int moved = settleThroughTankBridges(level, scan);
            if (moved > 0) DebugInfo.log(level, "SUPPLEMENT tank bridge moved={}mb", moved);

            int transferred = transferWorldToWorldFallback(level, scan);
            if (transferred > 0) DebugInfo.log(level, "SUPPLEMENT world transfer moved={}mb", transferred);
        } finally {
            DebugInfo.endNetwork();
        }
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
        List<TankContact> contacts = new ArrayList<>();
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
                    TankContact contact = new TankContact(tank, node.pos, face);
                    contacts.add(contact);

                    // Include other pipe contacts on the same tank when the tank can bridge to them.
                    if (canProvide(contact)) {
                        for (BlockPos tankSeed : tankSeeds(level, tank)) {
                            if (!tankSeed.equals(node.pos)) queue.add(new Node(tankSeed, node.distance + 1));
                        }
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new Scan(pipes, dedupeContacts(contacts), dedupeOpenEnds(openEnds));
    }

    private static int settleThroughTankBridges(Level level, Scan scan) {
        if (scan.contacts.size() < 2) return 0;

        List<TankContact> sources = new ArrayList<>(scan.contacts);
        sources.sort(Comparator.comparingDouble((TankContact c) -> surface(c.tank)).reversed());

        for (TankContact source : sources) {
            if (!canProvide(source)) continue;
            FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
            if (sourceFluid.isEmpty()) continue;

            TankContact target = scan.contacts.stream()
                    .filter(c -> !sameTank(source.tank, c.tank))
                    .filter(c -> compatible(source.tank, c.tank))
                    .filter(c -> c.tank.getTankInventory().getFluidAmount() < c.tank.getTankInventory().getCapacity())
                    .filter(c -> c.cutoffSurface() <= surface(source.tank) + EPSILON)
                    .filter(c -> surface(c.tank) < surface(source.tank) - DEAD_BAND)
                    .min(Comparator
                            .comparingDouble((TankContact c) -> surface(c.tank))
                            .thenComparing(c -> c.tank.getController().distManhattan(source.tank.getController())))
                    .orElse(null);

            if (target == null) continue;

            int sourceFloor = amountForSurface(source.tank, Math.max(source.cutoffSurface(), surface(target.tank)));
            int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
            if (available <= 0) continue;

            int requested = Math.min(MAX_TANK_SETTLE_MB, available);
            int moved = moveFluid(source.tank, target.tank, requested);
            if (moved > 0) {
                DebugInfo.log(level,
                        "SUPPLEMENT tank bridge source={} target={} sourceSurface={} targetSurface={} sourceCutoff={} targetCutoff={} moved={} requested={}",
                        source.tank.getController(), target.tank.getController(), surface(source.tank), surface(target.tank),
                        source.cutoffSurface(), target.cutoffSurface(), moved, requested);
                return moved;
            }
        }

        return 0;
    }

    private static int transferWorldToWorldFallback(Level level, Scan scan) {
        if (level.getGameTime() % WORLD_TRANSFER_INTERVAL != 0) return 0;
        if (!scan.contacts.isEmpty() || scan.openEnds.size() < 2) return 0;

        for (OpenEnd sourceEnd : scan.openEnds) {
            BlockPos sourcePos = sourceEnd.worldPos();
            if (recentlyMovedSource(level, sourcePos)) continue;

            FluidState sourceState = level.getFluidState(sourcePos);
            if (sourceState.isEmpty() || !sourceState.isSource()) continue;

            Fluid fluid = sourceFluidFor(sourceState.getType());
            int maxOutputY = sourcePos.getY();
            double sourceHead = sourcePos.getY() + 1.0;

            List<WorldOutletTarget> targets = new ArrayList<>();
            for (OpenEnd targetEnd : scan.openEnds) {
                if (targetEnd.equals(sourceEnd)) continue;
                if (targetEnd.pipe.getY() > maxOutputY) {
                    DebugInfo.log(level, "SUPPLEMENT world transfer candidate rejected outlet={} reason=wouldPushUp source={} sourceY={} targetPipeY={}",
                            targetEnd.worldPos(), sourcePos, sourcePos.getY(), targetEnd.pipe.getY());
                    continue;
                }

                BlockPos placePos = outletPlacement(level, targetEnd.worldPos(), fluid, maxOutputY);
                if (placePos == null) continue;
                targets.add(new WorldOutletTarget(targetEnd, placePos, targetEnd.worldPos().distManhattan(placePos)));
            }

            targets.sort(Comparator
                    .comparingInt((WorldOutletTarget target) -> target.placePos.getY())
                    .thenComparingInt(target -> target.searchDistance)
                    .thenComparing(target -> target.placePos, PressureSupplementService::compareBlockPos));

            for (WorldOutletTarget target : targets) {
                if (!placeFluidBlockAtOutlet(level, target.placePos, fluid)) continue;

                hardDeleteWorldSource(level, sourcePos, fluid);
                markRecentlyMovedSource(level, sourcePos);
                markRecentlyMovedSource(level, target.placePos);
                DebugInfo.log(level,
                        "SUPPLEMENT world transfer source={} target={} outlet={} fluid={} sourceHead={} searchDistance={} rule=downFirstFlowingOnly",
                        sourcePos, target.placePos, target.end.worldPos(), fluid, sourceHead, target.searchDistance);
                return WORLD_BLOCK_MB;
            }

            DebugInfo.log(level, "SUPPLEMENT world transfer idle source={} reason=noDownwardFlowingOutlet", sourcePos);
        }

        return 0;
    }

    private static boolean recentlyMovedSource(Level level, BlockPos pos) {
        Map<BlockPos, Long> moved = RECENTLY_MOVED_SOURCES.get(level);
        if (moved == null) return false;
        Long until = moved.get(pos);
        if (until == null) return false;
        if (level.getGameTime() <= until) return true;
        moved.remove(pos);
        return false;
    }

    private static void markRecentlyMovedSource(Level level, BlockPos pos) {
        RECENTLY_MOVED_SOURCES
                .computeIfAbsent(level, $ -> new HashMap<>())
                .put(pos, level.getGameTime() + MOVED_SOURCE_SUPPRESSION_TICKS);
    }

    private static void hardDeleteWorldSource(Level level, BlockPos sourcePos, Fluid fluid) {
        level.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());

        // Vanilla water can immediately remake an infinite source. Remove one adjacent
        // matching source block as a breaker so a moved source actually leaves the inlet.
        for (Direction direction : SOURCE_BREAK_DIRECTIONS) {
            BlockPos breaker = sourcePos.relative(direction);
            if (!level.isLoaded(breaker)) continue;
            FluidState state = level.getFluidState(breaker);
            if (!state.isSource() || !sameFluidKind(state.getType(), fluid)) continue;

            level.setBlockAndUpdate(breaker, Blocks.AIR.defaultBlockState());
            markRecentlyMovedSource(level, breaker);
            DebugInfo.log(level, "SUPPLEMENT world transfer hardDeletedExtraSource source={} extra={} fluid={}", sourcePos, breaker, fluid);
            return;
        }
    }

    private static BlockPos outletPlacement(Level level, BlockPos targetPos, Fluid fluid, int maxOutputY) {
        if (!level.isLoaded(targetPos)) return null;
        if (canPlaceFlowingBlockAtOutlet(level, targetPos, fluid, maxOutputY)) return targetPos;

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(targetPos, 0));
        visited.add(targetPos);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_OUTLET_SEARCH) continue;

            if (canPlaceFlowingBlockAtOutlet(level, node.pos, fluid, maxOutputY)) return node.pos;

            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) continue;
                FluidState nextState = level.getFluidState(next);
                if (!nextState.isEmpty() && sameFluidKind(nextState.getType(), fluid) && next.getY() <= maxOutputY) {
                    queue.add(new Node(next, node.distance + 1));
                }
            }
        }

        return null;
    }

    private static boolean canPlaceFlowingBlockAtOutlet(Level level, BlockPos pos, Fluid fluid, int maxOutputY) {
        if (pos.getY() > maxOutputY) return false;
        FluidState state = level.getFluidState(pos);
        return !state.isEmpty() && sameFluidKind(state.getType(), fluid) && !state.isSource();
    }

    private static boolean placeFluidBlockAtOutlet(Level level, BlockPos pos, Fluid fluid) {
        FluidState state = level.getFluidState(pos);
        if (state.isEmpty() || state.isSource() || !sameFluidKind(state.getType(), fluid)) return false;
        if (isWater(fluid)) return level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        if (isLavaFluid(fluid)) return level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
        return false;
    }

    private static List<TankContact> dedupeContacts(List<TankContact> contacts) {
        Map<String, TankContact> deduped = new HashMap<>();
        for (TankContact contact : contacts) deduped.putIfAbsent(contact.tank.getController() + "|" + contact.pipe + "|" + contact.face, contact);
        return new ArrayList<>(deduped.values());
    }

    private static List<OpenEnd> dedupeOpenEnds(List<OpenEnd> openEnds) {
        Map<String, OpenEnd> deduped = new HashMap<>();
        for (OpenEnd end : openEnds) deduped.putIfAbsent(end.pipe + "|" + end.face, end);
        return new ArrayList<>(deduped.values());
    }

    private static Set<BlockPos> tankSeeds(Level level, FluidTankBlockEntity tank) {
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

    private static boolean canProvide(TankContact contact) {
        return contact.tank.getTankInventory().getFluidAmount() > amountForSurface(contact.tank, contact.cutoffSurface())
                && surface(contact.tank) > contact.cutoffSurface() + EPSILON;
    }

    private static boolean compatible(FluidTankBlockEntity source, FluidTankBlockEntity target) {
        FluidStack sourceFluid = source.getTankInventory().getFluid();
        FluidStack targetFluid = target.getTankInventory().getFluid();
        return sourceFluid.isEmpty() || targetFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
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
        int capacity = tank.getTankInventory().getCapacity();
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(capacity, (int) Math.round(filledHeight * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / (double) Math.max(1, tank.getHeight());
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

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
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
        return (isWater(a) && isWater(b)) || (isLavaFluid(a) && isLavaFluid(b));
    }

    private static Fluid sourceFluidFor(Fluid fluid) {
        if (isWater(fluid)) return Fluids.WATER;
        if (isLavaFluid(fluid)) return Fluids.LAVA;
        return fluid;
    }

    private static boolean isWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isLavaFluid(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> contacts, List<OpenEnd> openEnds) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() {
            return sourceCutoffSurface(pipe, face, tank);
        }
    }
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() {
            return pipe.relative(face);
        }
    }
    private record WorldOutletTarget(OpenEnd end, BlockPos placePos, int searchDistance) {}
}
