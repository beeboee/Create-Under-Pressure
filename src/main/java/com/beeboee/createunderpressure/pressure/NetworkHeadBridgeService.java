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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Focused patch layer for one missing planner behavior:
 * a filled tank can pass pressure between its pipe contacts, but only after its
 * water surface is above the relevant contact cutoffs.
 */
public final class NetworkHeadBridgeService {
    private NetworkHeadBridgeService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int MAX_MOVE_MB = 125;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan.pipes.isEmpty() || scan.contacts.size() < 2) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(scan.pipes);

        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int moved = settleWithFilledTankBridges(level, scan);
            if (moved > 0) DebugInfo.log(level, "HEAD_BRIDGE moved={}mb", moved);
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
                if (tank == null) continue;

                TankContact contact = new TankContact(tank, node.pos, face);
                contacts.add(contact);
                for (BlockPos tankSeed : tankSeedPipes(level, tank)) {
                    if (!tankSeed.equals(node.pos)) queue.add(new Node(tankSeed, node.distance + 1));
                }
            }
        }

        return new Scan(pipes, dedupeContacts(contacts));
    }

    private static int settleWithFilledTankBridges(Level level, Scan scan) {
        List<TankContact> sources = new ArrayList<>(scan.contacts);
        sources.sort(Comparator.comparingDouble((TankContact contact) -> surface(contact.tank)).reversed());

        for (TankContact source : sources) {
            if (!canProvide(source)) continue;
            FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
            if (sourceFluid.isEmpty()) continue;
            double sourceHead = surface(source.tank);

            List<TankContact> targets = new ArrayList<>(scan.contacts);
            targets.removeIf(target -> sameTank(source.tank, target.tank)
                    || !canFillWith(target.tank, sourceFluid)
                    || surface(target.tank) >= sourceHead - DEAD_BAND
                    || target.cutoffSurface() > sourceHead + EPSILON
                    || !reachableWithinHead(level, scan, source.pipe, target.pipe, sourceHead));
            targets.sort(Comparator
                    .comparingDouble((TankContact target) -> surface(target.tank))
                    .thenComparingInt(target -> target.pipe.distManhattan(source.pipe))
                    .thenComparing(target -> target.tank.getController(), NetworkHeadBridgeService::compareBlockPos));

            for (TankContact target : targets) {
                int sourceFloor = amountForSurface(source.tank, Math.max(source.cutoffSurface(), surface(target.tank)));
                int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
                if (available <= 0) continue;

                int targetMax = amountForSurface(target.tank, Math.min(sourceHead, target.topY()));
                int needed = targetMax - target.tank.getTankInventory().getFluidAmount();
                if (needed <= 0) continue;

                int moved = moveFluid(source.tank, target.tank, Math.min(MAX_MOVE_MB, Math.min(available, needed)));
                if (moved <= 0) continue;

                DebugInfo.log(level,
                        "HEAD_BRIDGE tank->tank source={} target={} sourcePipe={} targetPipe={} moved={} sourceSurface={} targetSurface={} sourceCutoff={} targetCutoff={}",
                        source.tank.getController(), target.tank.getController(), source.pipe, target.pipe,
                        moved, sourceHead, surface(target.tank), source.cutoffSurface(), target.cutoffSurface());
                return moved;
            }
        }

        return 0;
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
            if (pipe != null) {
                for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                    BlockPos next = pipePos.relative(face);
                    if (!scan.pipes.contains(next) || next.getY() > maxHead + EPSILON || !visited.add(next)) continue;
                    queue.add(next);
                }
            }

            for (TankContact entry : scan.contacts) {
                if (!entry.pipe.equals(pipePos) || !canBridgeAtHead(entry, maxHead)) continue;
                for (TankContact exit : scan.contacts) {
                    if (!sameTank(entry.tank, exit.tank) || exit.pipe.equals(pipePos) || !canBridgeAtHead(exit, maxHead)) continue;
                    if (exit.pipe.getY() > maxHead + EPSILON || !visited.add(exit.pipe)) continue;
                    queue.add(exit.pipe);
                    DebugInfo.log(level, "HEAD_BRIDGE path through tank={} fromPipe={} toPipe={} maxHead={} tankSurface={} entryCutoff={} exitCutoff={}",
                            entry.tank.getController(), entry.pipe, exit.pipe, maxHead, surface(entry.tank), entry.cutoffSurface(), exit.cutoffSurface());
                }
            }
        }
        return false;
    }

    private static boolean canBridgeAtHead(TankContact contact, double maxHead) {
        return contact.cutoffSurface() <= maxHead + EPSILON
                && surface(contact.tank) > contact.cutoffSurface() + EPSILON;
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

    private static boolean canFillWith(FluidTankBlockEntity tank, FluidStack fluid) {
        FluidStack stack = tank.getTankInventory().getFluid();
        return stack.isEmpty() || FluidStack.isSameFluidSameComponents(stack, fluid);
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

    private static List<TankContact> dedupeContacts(List<TankContact> contacts) {
        Map<String, TankContact> deduped = new HashMap<>();
        for (TankContact contact : contacts) deduped.putIfAbsent(contact.tank.getController() + "|" + contact.pipe + "|" + contact.face, contact);
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

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> contacts) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() { return NetworkHeadBridgeService.cutoffSurface(pipe, face, tank); }
        double topY() { return tank.getController().getY() + tank.getHeight(); }
    }
}
