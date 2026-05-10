package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.visual.WorldExchangeVisualEvents;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
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
 * Shared-plan world boundary executor.
 *
 * This replaces the old world action ordering with selected HydraulicPlan actions,
 * while still delegating actual world placement/removal to Create's hose pulley
 * filler/drainer behaviours.
 */
public final class HydraulicPlanWorldIOService {
    private HydraulicPlanWorldIOService() {}

    private static final int TICK_INTERVAL = 8;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int PREPARE_TICKS = 96;
    private static final int SOURCE_SEARCH_DISTANCE = 6;
    private static final int FILLABLE_GUARD_RADIUS = 5;
    private static final int VISUAL_LINGER_TICKS = 24;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<EndKey, HoseContext>> CONTEXTS = new WeakHashMap<>();

    public static int tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return 0;
        if (level.getGameTime() % TICK_INTERVAL != 0) return 0;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return 0;

        HydraulicPlanBuilder.BuildResult result = HydraulicPlanRuntime.acquire(level, seed, level.getGameTime());
        HydraulicPlan plan = result.plan();
        if (result.pipes().isEmpty()) return 0;

        BlockPos owner = plan.owner();
        if (!seed.equals(owner)) return 0;
        processed.pipes.addAll(result.pipes());
        tickContexts(level, plan);

        DebugInfo.beginNetwork(level, result.pipes(), owner);
        try {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO scan owner={} pipes={} actions={} source=SharedPlan cached=true", owner, result.pipes().size(), plan.actions().size());
            for (HydraulicPlan.Action action : plan.actions()) {
                int moved = switch (action.type()) {
                    case WORLD_TO_TANK -> worldToTank(level, pipe, action);
                    case TANK_TO_WORLD -> tankToWorld(level, pipe, action);
                    case WORLD_TO_WORLD -> worldToWorld(level, pipe, action);
                    default -> 0;
                };
                if (moved > 0) {
                    DebugInfo.log(level, "HYDRAULIC_WORLD_IO moved={} action={} source=SharedPlan cached=true", moved, action.type());
                    return moved;
                }
            }
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO result owner={} moved=0 source=SharedPlan cached=true", owner);
            return 0;
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static int worldToTank(Level level, FluidTransportBehaviour ownerPipe, HydraulicPlan.Action action) {
        HydraulicPlan.Route route = action.route();
        HydraulicPlan.Port input = route.source();
        HydraulicPlan.Port target = route.sink();
        FluidTankBlockEntity tank = tankAt(level, target.owner());
        if (tank == null) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->tank skip reason=missingTank target={}", target.owner());
            return 0;
        }

        BlockPos drainRoot = drainRoot(level, input.owner());
        if (drainRoot == null) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->tank skip input={} reason=noDrainableSource", input.owner());
            return 0;
        }

        HoseContext ctx = context(level, ownerPipe, input, drainRoot);
        FluidStack available = ctx.drainable();
        if (available.isEmpty() || available.getAmount() < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->tank skip input={} reason=hoseDrainWarmingOrEmpty available={}", input.owner(), available);
            return 0;
        }

        int canFill = tank.getTankInventory().fill(copyWithAmount(available, WORLD_BLOCK_MB), FluidAction.SIMULATE);
        if (canFill < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->tank skip tank={} reason=tankRejected canFill={} stack={}", tank.getController(), canFill, available);
            return 0;
        }

        FluidStack drained = ctx.handler.drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() < WORLD_BLOCK_MB) return 0;

        int filled = tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            FluidStack remainder = drained.copy();
            remainder.setAmount(drained.getAmount() - filled);
            ctx.handler.fill(remainder, FluidAction.EXECUTE);
        }

        publish(input, WorldExchangeVisualEvents.Action.INTAKE, drained, level);
        DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->tank input={} drainRoot={} tank={} moved={} deltaHead={} flowEstimate={} source=SharedPlan cached=true",
                input.owner(), drainRoot, tank.getController(), filled, route.deltaHead(), route.flowEstimateMb());
        return filled;
    }

    private static int tankToWorld(Level level, FluidTransportBehaviour ownerPipe, HydraulicPlan.Action action) {
        HydraulicPlan.Route route = action.route();
        HydraulicPlan.Port source = route.source();
        HydraulicPlan.Port outlet = route.sink();
        FluidTankBlockEntity tank = tankAt(level, source.owner());
        if (tank == null) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world skip reason=missingTank source={}", source.owner());
            return 0;
        }

        FluidStack stack = tank.getTankInventory().getFluid();
        if (stack.isEmpty() || tank.getTankInventory().getFluidAmount() < WORLD_BLOCK_MB) return 0;
        FluidStack block = copyWithAmount(stack, WORLD_BLOCK_MB);

        if (blocksOutput(level, outlet.owner(), block.getFluid())) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world skip outlet={} reason=blockedByDifferentFluid stack={}", outlet.owner(), block);
            return 0;
        }
        if (!hasLikelyFillableSpace(level, outlet.owner(), block.getFluid())) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world skip outlet={} reason=noNearbyFillableSpace stack={}", outlet.owner(), block);
            return 0;
        }

        HoseContext ctx = context(level, ownerPipe, outlet, outlet.owner());
        if (!ctx.canDeposit(block.getFluid())) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world skip outlet={} reason=hoseFillWarmingOrRejected stack={}", outlet.owner(), block);
            return 0;
        }

        FluidStack drained = tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
        if (drained.isEmpty()) return 0;
        int filled = ctx.handler.fill(drained, FluidAction.EXECUTE);
        if (filled < WORLD_BLOCK_MB) {
            tank.getTankInventory().fill(drained, FluidAction.EXECUTE);
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world rollback tank={} outlet={} filled={}", tank.getController(), outlet.owner(), filled);
            return 0;
        }

        publish(outlet, WorldExchangeVisualEvents.Action.OUTPUT, drained, level);
        DebugInfo.log(level, "HYDRAULIC_WORLD_IO tank->world tank={} outlet={} moved={} deltaHead={} flowEstimate={} source=SharedPlan cached=true",
                tank.getController(), outlet.owner(), filled, route.deltaHead(), route.flowEstimateMb());
        return filled;
    }

    private static int worldToWorld(Level level, FluidTransportBehaviour ownerPipe, HydraulicPlan.Action action) {
        HydraulicPlan.Route route = action.route();
        HydraulicPlan.Port input = route.source();
        HydraulicPlan.Port output = route.sink();
        BlockPos drainRoot = drainRoot(level, input.owner());
        if (drainRoot == null) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->world skip input={} reason=noDrainableSource", input.owner());
            return 0;
        }

        HoseContext inputCtx = context(level, ownerPipe, input, drainRoot);
        FluidStack available = inputCtx.drainable();
        if (available.isEmpty() || available.getAmount() < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->world skip input={} reason=hoseDrainWarmingOrEmpty available={}", input.owner(), available);
            return 0;
        }

        FluidStack block = copyWithAmount(available, WORLD_BLOCK_MB);
        if (blocksOutput(level, output.owner(), block.getFluid()) || !hasLikelyFillableSpace(level, output.owner(), block.getFluid())) return 0;
        HoseContext outputCtx = context(level, ownerPipe, output, output.owner());
        if (!outputCtx.canDeposit(block.getFluid())) return 0;

        FluidStack drained = inputCtx.handler.drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() < WORLD_BLOCK_MB) return 0;
        int filled = outputCtx.handler.fill(drained, FluidAction.EXECUTE);
        if (filled < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->world failedAfterDrain input={} output={} filled={} WARNING=sourceAlreadyPulled", input.owner(), output.owner(), filled);
            return 0;
        }

        publish(input, WorldExchangeVisualEvents.Action.INTAKE, drained, level);
        publish(output, WorldExchangeVisualEvents.Action.OUTPUT, drained, level);
        DebugInfo.log(level, "HYDRAULIC_WORLD_IO world->world input={} output={} moved={} deltaHead={} flowEstimate={} source=SharedPlan cached=true",
                input.owner(), output.owner(), filled, route.deltaHead(), route.flowEstimateMb());
        return filled;
    }

    private static void publish(HydraulicPlan.Port port, WorldExchangeVisualEvents.Action action, FluidStack stack, Level level) {
        WorldExchangeVisualEvents.publish(level, port.pipe(), port.face(), action, stack, VISUAL_LINGER_TICKS);
    }

    private static HoseContext context(Level level, FluidTransportBehaviour ownerPipe, HydraulicPlan.Port port, BlockPos root) {
        Map<EndKey, HoseContext> map = CONTEXTS.computeIfAbsent(level, $ -> new HashMap<>());
        EndKey key = new EndKey(port.pipe(), port.face());
        HoseContext current = map.get(key);
        if (current == null || !current.root.equals(root)) {
            current = new HoseContext(ownerPipe, root);
            map.put(key, current);
        }
        return current;
    }

    private static void tickContexts(Level level, HydraulicPlan plan) {
        Map<EndKey, HoseContext> map = CONTEXTS.get(level);
        if (map == null) return;
        Set<EndKey> active = new HashSet<>();
        for (HydraulicPlan.Port port : plan.ports()) if (port.type() == HydraulicPlan.PortType.WORLD) active.add(new EndKey(port.pipe(), port.face()));
        map.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
        for (HoseContext ctx : map.values()) ctx.tick();
    }

    private static BlockPos drainRoot(Level level, BlockPos root) {
        FluidState state = level.getFluidState(root);
        if (state.isEmpty()) return null;
        if (state.isSource()) return root;

        Fluid fluid = state.getType();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));
        visited.add(root);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (!level.isLoaded(node.pos)) continue;
            FluidState nodeState = level.getFluidState(node.pos);
            if (!nodeState.isEmpty() && nodeState.getType().isSame(fluid) && nodeState.isSource()) return node.pos;
            if (node.distance >= SOURCE_SEARCH_DISTANCE) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos.relative(direction);
                if (!visited.add(next)) continue;
                FluidState nextState = level.getFluidState(next);
                if (!nextState.isEmpty() && nextState.getType().isSame(fluid)) queue.add(new Node(next, node.distance + 1));
            }
        }
        return null;
    }

    private static boolean blocksOutput(Level level, BlockPos pos, Fluid fluid) {
        FluidState state = level.getFluidState(pos);
        return !state.isEmpty() && !state.getType().isSame(fluid);
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
            if (isLikelyFillable(state, fluidState, fluid)) return true;
            if (node.distance >= FILLABLE_GUARD_RADIUS) continue;
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos next = node.pos.relative(direction);
                if (visited.add(next)) queue.add(new Node(next, node.distance + 1));
            }
        }
        return false;
    }

    private static boolean isLikelyFillable(BlockState state, FluidState fluidState, Fluid fluid) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) return fluid.isSame(Fluids.WATER) && !state.getValue(BlockStateProperties.WATERLOGGED);
        if (state.getBlock() instanceof LiquidBlock) return fluidState.getType().isSame(fluid) && state.getValue(LiquidBlock.LEVEL) != 0;
        if (!fluidState.isEmpty()) return false;
        return state.canBeReplaced() || !state.blocksMotion();
    }

    private static FluidStack copyWithAmount(FluidStack stack, int amount) {
        FluidStack copy = stack.copy();
        copy.setAmount(Math.min(amount, stack.getAmount()));
        return copy;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos controllerPos) {
        if (!(level.getBlockEntity(controllerPos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
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
}
