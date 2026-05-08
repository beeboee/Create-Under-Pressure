package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.visual.CreatePipeFlowVisualBridge;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Fallback for the case where a network has >= 1000mB available across multiple
 * tanks, but no single tank can output a full source block by itself.
 *
 * NetworkPressurePlanner remains the main authority. This only handles the aggregate
 * tank->world edge case that the planner currently rejects as availableBelow1000.
 */
public final class AggregateTankWorldOutputService {
    private AggregateTankWorldOutputService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;
    private static final float VISUAL_PRESSURE = 64.0f;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan.pipes.isEmpty() || scan.contacts.size() < 2 || scan.openEnds.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(scan.pipes);

        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int moved = tryAggregateOutput(level, scan);
            if (moved > 0) DebugInfo.log(level, "AGGREGATE tank->world result owner={} moved={}mb", owner, moved);
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static int tryAggregateOutput(Level level, Scan scan) {
        List<OpenEnd> outlets = new ArrayList<>(scan.openEnds);
        outlets.removeIf(outlet -> !level.isLoaded(outlet.worldPos()) || !level.getBlockState(outlet.worldPos()).isAir());
        outlets.sort(Comparator
                .comparingInt((OpenEnd end) -> end.worldPos().getY())
                .thenComparing(end -> end.worldPos(), AggregateTankWorldOutputService::compareBlockPos));

        for (OpenEnd outlet : outlets) {
            List<TankContact> sources = reachableContacts(level, scan, outlet.pipe, 256.0);
            sources.removeIf(source -> !canProvide(source) || surface(source.tank) <= outlet.worldPos().getY() + DEAD_BAND);
            sources.sort(Comparator
                    .comparingDouble((TankContact source) -> surface(source.tank)).reversed()
                    .thenComparingInt(source -> source.pipe.distManhattan(outlet.pipe)));

            Fluid fluid = commonFluid(sources);
            if (fluid == null || !canPlaceFluidBlock(fluid)) continue;

            List<TankDraw> draws = new ArrayList<>();
            int total = 0;
            for (TankContact source : sources) {
                FluidStack stack = source.tank.getTankInventory().getFluid();
                if (stack.isEmpty() || !sameFluidKind(stack.getFluid(), fluid)) continue;
                if (!reachableWithinHead(level, scan, source.pipe, outlet.pipe, surface(source.tank))) continue;

                int floor = amountForSurface(source.tank, source.cutoffSurface());
                int available = Math.max(0, source.tank.getTankInventory().getFluidAmount() - floor);
                if (available <= 0) continue;

                int needed = WORLD_BLOCK_MB - total;
                int take = Math.min(available, needed);
                draws.add(new TankDraw(source, take));
                total += take;
                if (total >= WORLD_BLOCK_MB) break;
            }

            if (total < WORLD_BLOCK_MB) {
                DebugInfo.log(level, "AGGREGATE tank->world skip outlet={} reason=aggregateBelow1000 available={} sources={}", outlet.worldPos(), total, sources.size());
                continue;
            }

            if (!placeFluid(level, outlet.worldPos(), fluid)) continue;

            int drainedTotal = 0;
            for (TankDraw draw : draws) {
                FluidStack drained = draw.source.tank.getTankInventory().drain(draw.amount, FluidAction.EXECUTE);
                drainedTotal += drained.getAmount();
            }

            if (drainedTotal < WORLD_BLOCK_MB) {
                level.setBlockAndUpdate(outlet.worldPos(), Blocks.AIR.defaultBlockState());
                DebugInfo.log(level, "AGGREGATE tank->world rollback outlet={} reason=drainedBelow1000 drained={}", outlet.worldPos(), drainedTotal);
                continue;
            }

            CreateWorldEndIO.spawnCreateParticles(level, outlet.pipe, outlet.face, new FluidStack(fluid, WORLD_BLOCK_MB), false);
            CreatePipeFlowVisualBridge.apply(level, FluidPropagator.getPipe(level, outlet.pipe), outlet.pipe, outlet.face, false, VISUAL_PRESSURE);
            DebugInfo.log(level, "AGGREGATE tank->world outlet={} moved={} fluid={} draws={} rule=multiTankSourceBlock", outlet.worldPos(), drainedTotal, fluid, draws.size());
            return drainedTotal;
        }

        return 0;
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
                    contacts.add(new TankContact(tank, node.pos, face));
                    for (BlockPos seedPipe : tankSeedPipes(level, tank)) {
                        if (!seedPipe.equals(node.pos)) queue.add(new Node(seedPipe, node.distance + 1));
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new Scan(pipes, dedupeContacts(contacts), dedupeOpenEnds(openEnds));
    }

    private static List<TankContact> reachableContacts(Level level, Scan scan, BlockPos startPipe, double maxHead) {
        List<TankContact> reachable = new ArrayList<>();
        for (TankContact contact : scan.contacts) {
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

    private static Fluid commonFluid(List<TankContact> sources) {
        Fluid found = null;
        for (TankContact source : sources) {
            FluidStack stack = source.tank.getTankInventory().getFluid();
            if (stack.isEmpty()) continue;
            if (found == null) found = stack.getFluid();
            else if (!sameFluidKind(found, stack.getFluid())) return null;
        }
        return found;
    }

    private static boolean canProvide(TankContact contact) {
        return contact.tank.getTankInventory().getFluidAmount() > amountForSurface(contact.tank, contact.cutoffSurface())
                && surface(contact.tank) > contact.cutoffSurface() + EPSILON;
    }

    private static boolean canPlaceFluidBlock(Fluid fluid) {
        return isWater(fluid) || isLava(fluid);
    }

    private static boolean placeFluid(Level level, BlockPos pos, Fluid fluid) {
        if (isWater(fluid)) return level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        if (isLava(fluid)) return level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
        return false;
    }

    private static boolean sameFluidKind(Fluid a, Fluid b) {
        return (isWater(a) && isWater(b)) || (isLava(a) && isLava(b)) || a == b;
    }

    private static boolean isWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isLava(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
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

    private static List<TankContact> dedupeContacts(List<TankContact> contacts) {
        List<TankContact> deduped = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (TankContact contact : contacts) {
            String key = contact.tank.getController() + "|" + contact.pipe + "|" + contact.face;
            if (keys.add(key)) deduped.add(contact);
        }
        return deduped;
    }

    private static List<OpenEnd> dedupeOpenEnds(List<OpenEnd> ends) {
        List<OpenEnd> deduped = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (OpenEnd end : ends) if (keys.add(end.pipe + "|" + end.face)) deduped.add(end);
        return deduped;
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

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> contacts, List<OpenEnd> openEnds) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() { return AggregateTankWorldOutputService.cutoffSurface(pipe, face, tank); }
    }
    private record TankDraw(TankContact source, int amount) {}
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() { return pipe.relative(face); }
    }
}
