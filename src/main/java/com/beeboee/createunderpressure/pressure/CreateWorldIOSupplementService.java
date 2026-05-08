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
 * Experimental Create-native world boundary layer.
 *
 * This keeps our pressure rules local, but delegates actual world pipe-end IO to
 * Create's OpenEndedPipe handler through CreateWorldEndIO. It is intentionally small
 * and supplement-like so we can test Create semantics before ripping out the old
 * planner world-source code.
 */
public final class CreateWorldIOSupplementService {
    private CreateWorldIOSupplementService() {}

    private static final int TICK_INTERVAL = 10;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int WORLD_TO_TANK_MB = 250;
    private static final int WARMUP_STEPS = 2;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<WorldMoveKey, Integer>> WARMUPS = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan.pipes.isEmpty() || scan.openEnds.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(scan.pipes);

        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int moved = 0;
            moved += createWorldToTank(level, scan);
            if (moved == 0) moved += createTankToWorld(level, scan);
            if (moved == 0) moved += createWorldToWorld(level, scan);
            if (moved > 0) DebugInfo.log(level, "CREATE_IO moved={}mb", moved);
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
                    for (BlockPos tankSeed : tankSeedPipes(level, tank)) {
                        if (!tankSeed.equals(node.pos)) queue.add(new Node(tankSeed, node.distance + 1));
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new Scan(pipes, dedupeContacts(contacts), dedupeOpenEnds(openEnds));
    }

    private static int createWorldToTank(Level level, Scan scan) {
        if (scan.contacts.isEmpty()) return 0;

        for (OpenEnd input : scan.openEnds) {
            FluidStack available = CreateWorldEndIO.drain(level, input.pipe, input.face, WORLD_TO_TANK_MB, FluidAction.SIMULATE);
            if (available.isEmpty()) continue;

            List<TankContact> targets = new ArrayList<>(scan.contacts);
            targets.removeIf(target -> !canFillWith(target.tank, available));
            targets.sort(Comparator
                    .comparingDouble((TankContact target) -> surface(target.tank))
                    .thenComparingInt(target -> target.pipe.distManhattan(input.pipe)));

            for (TankContact target : targets) {
                int canFill = target.tank.getTankInventory().fill(copyWithAmount(available, WORLD_TO_TANK_MB), FluidAction.SIMULATE);
                if (canFill <= 0) continue;

                FluidStack drained = CreateWorldEndIO.drain(level, input.pipe, input.face, Math.min(WORLD_TO_TANK_MB, canFill), FluidAction.EXECUTE);
                if (drained.isEmpty()) continue;
                int filled = target.tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
                if (filled <= 0) continue;

                CreateWorldEndIO.spawnCreateParticles(level, input.pipe, input.face, drained, true);
                DebugInfo.log(level, "CREATE_IO world->tank input={} target={} moved={} stack={} rule=CreateOpenEndedPipeDrain",
                        input.worldPos(), target.tank.getController(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static int createTankToWorld(Level level, Scan scan) {
        if (scan.contacts.isEmpty()) return 0;

        List<OpenEnd> outlets = new ArrayList<>(scan.openEnds);
        outlets.sort(Comparator
                .comparingInt((OpenEnd outlet) -> outlet.worldPos().getY())
                .thenComparing(outlet -> outlet.worldPos(), CreateWorldIOSupplementService::compareBlockPos));

        for (OpenEnd outlet : outlets) {
            List<TankContact> sources = new ArrayList<>(scan.contacts);
            sources.removeIf(source -> !canProvide(source));
            sources.sort(Comparator
                    .comparingDouble((TankContact source) -> surface(source.tank)).reversed()
                    .thenComparingInt(source -> source.pipe.distManhattan(outlet.pipe)));

            for (TankContact source : sources) {
                FluidStack stack = source.tank.getTankInventory().getFluid();
                if (stack.isEmpty()) continue;
                FluidStack block = copyWithAmount(stack, WORLD_BLOCK_MB);
                if (CreateWorldEndIO.simulateFill(level, outlet.pipe, outlet.face, block) <= 0) continue;

                int sourceFloor = amountForSurface(source.tank, source.cutoffSurface());
                int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
                if (available < WORLD_BLOCK_MB) continue;

                FluidStack drained = source.tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty()) continue;
                int filled = CreateWorldEndIO.fill(level, outlet.pipe, outlet.face, drained, FluidAction.EXECUTE);
                if (filled <= 0) {
                    source.tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
                    continue;
                }

                CreateWorldEndIO.spawnCreateParticles(level, outlet.pipe, outlet.face, drained, false);
                DebugInfo.log(level, "CREATE_IO tank->world source={} outlet={} moved={} stack={} rule=CreateOpenEndedPipeFill",
                        source.tank.getController(), outlet.worldPos(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static int createWorldToWorld(Level level, Scan scan) {
        if (!scan.contacts.isEmpty() || scan.openEnds.size() < 2) return 0;

        for (OpenEnd input : scan.openEnds) {
            FluidStack available = CreateWorldEndIO.simulateDrain(level, input.pipe, input.face);
            if (available.isEmpty()) continue;

            List<OpenEnd> outputs = new ArrayList<>(scan.openEnds);
            outputs.remove(input);
            outputs.removeIf(output -> output.worldPos().getY() > input.worldPos().getY());
            outputs.sort(Comparator
                    .comparingInt((OpenEnd output) -> output.worldPos().getY())
                    .thenComparingInt(output -> output.pipe.distManhattan(input.pipe)));

            for (OpenEnd output : outputs) {
                FluidStack block = copyWithAmount(available, WORLD_BLOCK_MB);
                if (CreateWorldEndIO.simulateFill(level, output.pipe, output.face, block) <= 0) continue;

                WorldMoveKey key = new WorldMoveKey(input.pipe, input.face, output.pipe, output.face);
                int warmup = incrementWarmup(level, key);
                if (warmup < WARMUP_STEPS) {
                    CreateWorldEndIO.spawnCreateParticles(level, input.pipe, input.face, block, true);
                    CreateWorldEndIO.spawnCreateParticles(level, output.pipe, output.face, block, false);
                    DebugInfo.log(level, "CREATE_IO world->world warmup input={} output={} step={}/{} stack={} rule=CreateOpenEndedPipeHandlers",
                            input.worldPos(), output.worldPos(), warmup, WARMUP_STEPS, block);
                    return 0;
                }

                clearWarmup(level, key);
                FluidStack drained = CreateWorldEndIO.drain(level, input.pipe, input.face, WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty()) continue;
                int filled = CreateWorldEndIO.fill(level, output.pipe, output.face, drained, FluidAction.EXECUTE);
                if (filled <= 0) continue;

                CreateWorldEndIO.spawnCreateParticles(level, input.pipe, input.face, drained, true);
                CreateWorldEndIO.spawnCreateParticles(level, output.pipe, output.face, drained, false);
                DebugInfo.log(level, "CREATE_IO world->world input={} output={} moved={} stack={} rule=CreateOpenEndedPipeDrainFill",
                        input.worldPos(), output.worldPos(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static int incrementWarmup(Level level, WorldMoveKey key) {
        Map<WorldMoveKey, Integer> map = WARMUPS.computeIfAbsent(level, $ -> new HashMap<>());
        int value = map.getOrDefault(key, 0) + 1;
        map.put(key, value);
        return value;
    }

    private static void clearWarmup(Level level, WorldMoveKey key) {
        Map<WorldMoveKey, Integer> map = WARMUPS.get(level);
        if (map != null) map.remove(key);
    }

    private static FluidStack copyWithAmount(FluidStack stack, int amount) {
        FluidStack copy = stack.copy();
        copy.setAmount(Math.min(amount, stack.getAmount()));
        return copy;
    }

    private static boolean canProvide(TankContact contact) {
        return contact.tank.getTankInventory().getFluidAmount() > amountForSurface(contact.tank, contact.cutoffSurface())
                && surface(contact.tank) > contact.cutoffSurface() + EPSILON;
    }

    private static boolean canFillWith(FluidTankBlockEntity tank, FluidStack fluid) {
        FluidStack stack = tank.getTankInventory().getFluid();
        return stack.isEmpty() || FluidStack.isSameFluidSameComponents(stack, fluid);
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

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> contacts, List<OpenEnd> openEnds) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() { return CreateWorldIOSupplementService.cutoffSurface(pipe, face, tank); }
    }
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() { return pipe.relative(face); }
    }
    private record WorldMoveKey(BlockPos inputPipe, Direction inputFace, BlockPos outputPipe, Direction outputFace) {}
}
