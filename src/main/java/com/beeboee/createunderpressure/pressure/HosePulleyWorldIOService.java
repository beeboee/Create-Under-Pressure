package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.visual.CreatePipeFlowVisualBridge;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Experimental replacement for our custom world fluid placement/removal.
 *
 * Tank-to-tank/head rules still live elsewhere; this service owns only world boundary
 * I/O and delegates actual block placement/removal to Create's hose pulley behaviours.
 */
public final class HosePulleyWorldIOService {
    private HosePulleyWorldIOService() {}

    private static final int TICK_INTERVAL = 8;
    private static final int PREPARE_TICKS = 96;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int FILLABLE_GUARD_RADIUS = 5;
    private static final double EPSILON = 0.01;
    private static final double DEAD_BAND = 0.05;
    private static final double PRE_WORLD_TANK_FILL_DEPTH = 0.5;
    private static final float VISUAL_PRESSURE = 96.0f;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<EndKey, HoseContext>> CONTEXTS = new WeakHashMap<>();

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
            tickContexts(level, scan);
            DebugInfo.log(level, "HOSE_IO scan owner={} pipes={} tanks={} openEnds={}", owner, scan.pipes.size(), scan.contacts.size(), scan.openEnds.size());

            int moved = 0;
            moved += worldToTank(level, pipe, scan);
            if (moved == 0) moved += tankToWorld(level, pipe, scan);
            if (moved == 0) moved += worldToWorld(level, pipe, scan);

            DebugInfo.log(level, "HOSE_IO result owner={} moved={}mb", owner, moved);
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static int worldToTank(Level level, FluidTransportBehaviour ownerPipe, Scan scan) {
        if (scan.contacts.isEmpty()) return 0;

        List<OpenEnd> inputs = new ArrayList<>(scan.openEnds);
        inputs.removeIf(input -> level.getFluidState(input.worldPos()).isEmpty());
        inputs.sort(Comparator.comparing(input -> input.worldPos(), HosePulleyWorldIOService::compareBlockPos));

        for (OpenEnd input : inputs) {
            HoseContext ctx = context(level, ownerPipe, input);
            FluidStack available = ctx.drainable();
            if (available.isEmpty() || available.getAmount() < WORLD_BLOCK_MB) {
                DebugInfo.log(level, "HOSE_IO world->tank skip input={} reason=hoseDrainWarmingOrEmpty available={} rootFluid={}",
                        input.worldPos(), available, level.getFluidState(input.worldPos()).getType());
                continue;
            }

            double sourceHead = input.worldPos().getY() + 1.0;
            List<TankContact> targets = reachableContacts(level, scan, input.pipe, sourceHead);
            targets.removeIf(target -> target.cutoffSurface() > sourceHead + EPSILON || !canFillWith(target.tank, available));
            targets.sort(Comparator
                    .comparingDouble((TankContact target) -> surface(target.tank))
                    .thenComparingInt(target -> target.pipe.distManhattan(input.pipe)));

            for (TankContact target : targets) {
                int canFill = target.tank.getTankInventory().fill(copyWithAmount(available, WORLD_BLOCK_MB), FluidAction.SIMULATE);
                if (canFill < WORLD_BLOCK_MB) continue;

                FluidStack drained = ctx.handler.drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty() || drained.getAmount() < WORLD_BLOCK_MB) continue;

                int filled = target.tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
                if (filled < drained.getAmount()) {
                    FluidStack remainder = drained.copy();
                    remainder.setAmount(drained.getAmount() - filled);
                    ctx.handler.fill(remainder, FluidAction.EXECUTE);
                }

                showIntake(level, input, drained);
                DebugInfo.log(level, "HOSE_IO world->tank input={} target={} moved={} stack={} rule=HosePulleyDrain",
                        input.worldPos(), target.tank.getController(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static int tankToWorld(Level level, FluidTransportBehaviour ownerPipe, Scan scan) {
        if (scan.contacts.isEmpty()) return 0;

        List<OpenEnd> outlets = new ArrayList<>(scan.openEnds);
        outlets.sort(Comparator
                .comparingInt((OpenEnd outlet) -> outlet.worldPos().getY())
                .thenComparing(outlet -> outlet.worldPos(), HosePulleyWorldIOService::compareBlockPos));

        for (OpenEnd outlet : outlets) {
            double outletHead = outlet.worldPos().getY();
            List<TankContact> sources = reachableContacts(level, scan, outlet.pipe, 256.0);
            sources.removeIf(source -> !canProvide(source) || surface(source.tank) <= outletHead + DEAD_BAND);
            sources.sort(Comparator
                    .comparingDouble((TankContact source) -> surface(source.tank)).reversed()
                    .thenComparingInt(source -> source.pipe.distManhattan(outlet.pipe)));

            for (TankContact source : sources) {
                FluidStack stack = source.tank.getTankInventory().getFluid();
                if (stack.isEmpty()) continue;
                double sourceSurface = surface(source.tank);
                if (!reachableWithinHead(level, scan, source.pipe, outlet.pipe, sourceSurface)) continue;

                TankContact tankFirst = tankNeedsFillBeforeWorld(level, scan, source, outlet, stack);
                if (tankFirst != null) {
                    DebugInfo.log(level, "HOSE_IO tank->world defer source={} outlet={} reason=tankBeforeWorld target={} targetSurface={} targetCutoff={} outletHead={}",
                            source.tank.getController(), outlet.worldPos(), tankFirst.tank.getController(), surface(tankFirst.tank), tankFirst.cutoffSurface(), outletHead);
                    continue;
                }

                int floor = amountForSurface(source.tank, source.cutoffSurface());
                int available = source.tank.getTankInventory().getFluidAmount() - floor;
                if (available < WORLD_BLOCK_MB) {
                    DebugInfo.log(level, "HOSE_IO tank->world skip source={} outlet={} reason=availableBelow1000 available={}", source.tank.getController(), outlet.worldPos(), available);
                    continue;
                }

                FluidStack block = copyWithAmount(stack, WORLD_BLOCK_MB);
                if (blocksOutput(level, outlet.worldPos(), block.getFluid())) {
                    DebugInfo.log(level, "HOSE_IO tank->world skip source={} outlet={} reason=wetEndReservedAsInput stack={}", source.tank.getController(), outlet.worldPos(), block);
                    continue;
                }

                HoseContext ctx = context(level, ownerPipe, outlet);
                if (!hasLikelyFillableSpace(level, outlet.worldPos(), block.getFluid())) {
                    DebugInfo.log(level, "HOSE_IO tank->world skip source={} outlet={} reason=noNearbyFillableSpace stack={}", source.tank.getController(), outlet.worldPos(), block);
                    continue;
                }
                if (!ctx.canDeposit(block.getFluid())) {
                    DebugInfo.log(level, "HOSE_IO tank->world skip source={} outlet={} reason=hoseFillWarmingOrRejected stack={}", source.tank.getController(), outlet.worldPos(), block);
                    continue;
                }

                FluidStack drained = source.tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty()) continue;
                int filled = ctx.handler.fill(drained, FluidAction.EXECUTE);
                if (filled < WORLD_BLOCK_MB) {
                    source.tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
                    DebugInfo.log(level, "HOSE_IO tank->world rollback source={} outlet={} filled={}", source.tank.getController(), outlet.worldPos(), filled);
                    continue;
                }

                showOutput(level, outlet, drained);
                DebugInfo.log(level, "HOSE_IO tank->world source={} outlet={} moved={} stack={} rule=HosePulleyFill",
                        source.tank.getController(), outlet.worldPos(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static TankContact tankNeedsFillBeforeWorld(Level level, Scan scan, TankContact source, OpenEnd outlet, FluidStack stack) {
        double sourceSurface = surface(source.tank);
        double outletHead = outlet.worldPos().getY();
        return scan.contacts.stream()
                .filter(target -> !sameTank(source.tank, target.tank))
                .filter(target -> canFillWith(target.tank, stack))
                .filter(target -> target.cutoffSurface() <= sourceSurface + EPSILON)
                .filter(target -> reachableWithinHead(level, scan, source.pipe, target.pipe, sourceSurface))
                .filter(target -> {
                    double fillTo = Math.min(target.topY(), Math.max(target.cutoffSurface() + PRE_WORLD_TANK_FILL_DEPTH, outletHead + 1.0));
                    return fillTo > target.tank.getController().getY() + EPSILON
                            && surface(target.tank) < fillTo - DEAD_BAND
                            && target.tank.getTankInventory().getFluidAmount() < amountForSurface(target.tank, fillTo);
                })
                .min(Comparator
                        .comparingDouble((TankContact target) -> surface(target.tank))
                        .thenComparingInt(target -> target.pipe.distManhattan(source.pipe)))
                .orElse(null);
    }

    private static int worldToWorld(Level level, FluidTransportBehaviour ownerPipe, Scan scan) {
        if (!scan.contacts.isEmpty() || scan.openEnds.size() < 2) return 0;

        for (OpenEnd input : scan.openEnds) {
            if (level.getFluidState(input.worldPos()).isEmpty()) continue;
            HoseContext inputCtx = context(level, ownerPipe, input);
            FluidStack available = inputCtx.drainable();
            if (available.isEmpty() || available.getAmount() < WORLD_BLOCK_MB) {
                DebugInfo.log(level, "HOSE_IO world->world skip input={} reason=hoseDrainWarmingOrEmpty available={} rootFluid={}",
                        input.worldPos(), available, level.getFluidState(input.worldPos()).getType());
                continue;
            }

            List<OpenEnd> outputs = new ArrayList<>(scan.openEnds);
            outputs.remove(input);
            outputs.removeIf(output -> output.worldPos().getY() > input.worldPos().getY());
            outputs.sort(Comparator
                    .comparingInt((OpenEnd output) -> output.worldPos().getY())
                    .thenComparingInt(output -> output.pipe.distManhattan(input.pipe)));

            for (OpenEnd output : outputs) {
                if (blocksOutput(level, output.worldPos(), available.getFluid())) {
                    DebugInfo.log(level, "HOSE_IO world->world reject input={} output={} reason=wetEndReservedAsInput", input.worldPos(), output.worldPos());
                    continue;
                }
                if (!reachableWithinHead(level, scan, input.pipe, output.pipe, input.worldPos().getY() + 1.0)) {
                    DebugInfo.log(level, "HOSE_IO world->world reject input={} output={} reason=pipeHumpAboveHead sourceHead={}", input.worldPos(), output.worldPos(), input.worldPos().getY() + 1.0);
                    continue;
                }
                HoseContext outputCtx = context(level, ownerPipe, output);
                FluidStack block = copyWithAmount(available, WORLD_BLOCK_MB);
                if (!hasLikelyFillableSpace(level, output.worldPos(), block.getFluid())) continue;
                if (!outputCtx.canDeposit(block.getFluid())) continue;

                FluidStack drained = inputCtx.handler.drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
                if (drained.isEmpty() || drained.getAmount() < WORLD_BLOCK_MB) continue;
                int filled = outputCtx.handler.fill(drained, FluidAction.EXECUTE);
                if (filled < WORLD_BLOCK_MB) {
                    DebugInfo.log(level, "HOSE_IO world->world failedAfterDrain input={} output={} filled={} WARNING=sourceAlreadyPulled", input.worldPos(), output.worldPos(), filled);
                    continue;
                }

                showIntake(level, input, drained);
                showOutput(level, output, drained);
                DebugInfo.log(level, "HOSE_IO world->world input={} output={} moved={} stack={} rule=HosePulleyDrainFill",
                        input.worldPos(), output.worldPos(), filled, drained);
                return filled;
            }
        }

        return 0;
    }

    private static boolean blocksOutput(Level level, BlockPos pos, Fluid fluid) {
        FluidState state = level.getFluidState(pos);
        if (state.isEmpty()) return false;
        if (!state.getType().isSame(fluid)) return true;
        return state.isSource();
    }

    private static HoseContext context(Level level, FluidTransportBehaviour ownerPipe, OpenEnd end) {
        Map<EndKey, HoseContext> map = CONTEXTS.computeIfAbsent(level, $ -> new HashMap<>());
        EndKey key = new EndKey(end.pipe, end.face);
        return map.computeIfAbsent(key, $ -> new HoseContext(ownerPipe, end.worldPos()));
    }

    private static void tickContexts(Level level, Scan scan) {
        Map<EndKey, HoseContext> map = CONTEXTS.get(level);
        if (map == null) return;
        Set<EndKey> active = new HashSet<>();
        for (OpenEnd end : scan.openEnds) active.add(new EndKey(end.pipe, end.face));
        map.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
        for (HoseContext ctx : map.values()) ctx.tick();
    }

    private static void showIntake(Level level, OpenEnd end, FluidStack stack) {
        CreateWorldEndIO.spawnCreateParticles(level, end.pipe, end.face, stack, true);
        CreatePipeFlowVisualBridge.apply(level, FluidPropagator.getPipe(level, end.pipe), end.pipe, end.face, true, VISUAL_PRESSURE);
    }

    private static void showOutput(Level level, OpenEnd end, FluidStack stack) {
        CreateWorldEndIO.spawnCreateParticles(level, end.pipe, end.face, stack, false);
        CreatePipeFlowVisualBridge.apply(level, FluidPropagator.getPipe(level, end.pipe), end.pipe, end.face, false, VISUAL_PRESSURE);
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
                    for (BlockPos seedPipe : tankSeedPipes(level, tank)) if (!seedPipe.equals(node.pos)) queue.add(new Node(seedPipe, node.distance + 1));
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new Scan(pipes, dedupeContacts(contacts), dedupeOpenEnds(openEnds));
    }

    private static List<TankContact> reachableContacts(Level level, Scan scan, BlockPos startPipe, double maxHead) {
        List<TankContact> reachable = new ArrayList<>();
        for (TankContact contact : scan.contacts) if (reachableWithinHead(level, scan, startPipe, contact.pipe, maxHead)) reachable.add(contact);
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
                if (!scan.pipes.contains(next) || !visited.add(next) || next.getY() > maxHead + EPSILON) continue;
                queue.add(next);
            }
        }
        return false;
    }

    private static boolean hasLikelyFillableSpace(Level level, BlockPos root, Fluid fluid) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));
        visited.add(root);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (!level.isLoaded(node.pos)) continue;
            BlockState state = level.getBlockState(node.pos);
            FluidState fluidState = state.getFluidState();
            if (isLikelyFillable(level, node.pos, state, fluidState, fluid)) return true;
            if (node.distance >= FILLABLE_GUARD_RADIUS) continue;
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos next = node.pos.relative(direction);
                if (visited.add(next)) queue.add(new Node(next, node.distance + 1));
            }
        }

        return false;
    }

    private static boolean isLikelyFillable(Level level, BlockPos pos, BlockState state, FluidState fluidState, Fluid fluid) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return fluid.isSame(Fluids.WATER) && !state.getValue(BlockStateProperties.WATERLOGGED);
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return fluidState.getType().isSame(fluid) && state.getValue(LiquidBlock.LEVEL) != 0;
        }
        if (!fluidState.isEmpty()) return false;
        return state.canBeReplaced() || !state.blocksMotion();
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

    private static boolean sameTank(FluidTankBlockEntity a, FluidTankBlockEntity b) {
        return a.getController().equals(b.getController());
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static Set<BlockPos> tankSeedPipes(Level level, FluidTankBlockEntity tank) {
        Set<BlockPos> seeds = new HashSet<>();
        BlockPos base = tank.getController();
        for (int x = 0; x < tank.getWidth(); x++) for (int y = 0; y < tank.getHeight(); y++) for (int z = 0; z < tank.getWidth(); z++) {
            BlockPos tankBlock = base.offset(x, y, z);
            for (Direction direction : Direction.values()) {
                BlockPos pipePos = tankBlock.relative(direction);
                if (level.isLoaded(pipePos) && FluidPropagator.getPipe(level, pipePos) != null) seeds.add(pipePos);
            }
        }
        return seeds;
    }

    private static double surface(FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
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
        Map<String, TankContact> deduped = new HashMap<>();
        for (TankContact contact : contacts) deduped.putIfAbsent(contact.tank.getController() + "|" + contact.pipe + "|" + contact.face, contact);
        return new ArrayList<>(deduped.values());
    }

    private static List<OpenEnd> dedupeOpenEnds(List<OpenEnd> ends) {
        Map<String, OpenEnd> deduped = new HashMap<>();
        for (OpenEnd end : ends) deduped.putIfAbsent(end.pipe + "|" + end.face, end);
        return new ArrayList<>(deduped.values());
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

    private static final class HoseContext {
        final FluidFillingBehaviour filler;
        final FluidDrainingBehaviour drainer;
        final HosePulleyFluidHandler handler;
        final BlockPos root;

        HoseContext(FluidTransportBehaviour pipe, BlockPos root) {
            this.root = root;
            SmartFluidTank internalTank = new SmartFluidTank(WORLD_BLOCK_MB, $ -> {});
            filler = new FluidFillingBehaviour(pipe.blockEntity);
            drainer = new FluidDrainingBehaviour(pipe.blockEntity);
            drainer.rebuildContext(root);
            handler = new HosePulleyFluidHandler(internalTank, filler, drainer, () -> root, () -> true);
            primeDrain();
        }

        FluidStack drainable() {
            primeDrain();
            return handler.drain(WORLD_BLOCK_MB, FluidAction.SIMULATE);
        }

        boolean canDeposit(Fluid fluid) {
            for (int i = 0; i < PREPARE_TICKS; i++) {
                if (filler.tryDeposit(fluid, root, true)) return true;
                tick();
            }
            return filler.tryDeposit(fluid, root, true);
        }

        void primeDrain() {
            for (int i = 0; i < PREPARE_TICKS; i++) {
                if (drainer.pullNext(root, true)) return;
                tick();
            }
        }

        void tick() {
            filler.tick();
            drainer.tick();
        }
    }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record EndKey(BlockPos pipe, Direction face) {}
    private record Node(BlockPos pos, int distance) {}
    private record Scan(Set<BlockPos> pipes, List<TankContact> contacts, List<OpenEnd> openEnds) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() { return HosePulleyWorldIOService.cutoffSurface(pipe, face, tank); }
        double topY() { return tank.getController().getY() + tank.getHeight(); }
    }
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() { return pipe.relative(face); }
    }
}
