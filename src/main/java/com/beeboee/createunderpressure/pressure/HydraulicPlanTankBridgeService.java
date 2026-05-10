package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * First real executor backed by the shared HydraulicPlan.
 *
 * Scope is intentionally narrow: only selected TANK_TO_TANK actions are executed
 * here. World I/O remains with the hose service until the planner executor grows
 * enough to replace it safely.
 */
public final class HydraulicPlanTankBridgeService {
    private HydraulicPlanTankBridgeService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_MOVE_MB = 125;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        HydraulicPlanBuilder.BuildResult result = HydraulicPlanRuntime.acquire(level, seed, level.getGameTime());
        HydraulicPlan plan = result.plan();
        if (result.pipes().isEmpty()) return;

        BlockPos owner = plan.owner();
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(result.pipes());

        DebugInfo.beginNetwork(level, result.pipes(), owner);
        try {
            int moved = executeFirstTankBridge(level, plan);
            if (moved > 0) DebugInfo.log(level, "HYDRAULIC_EXEC tankBridge moved={}mb source=SharedPlan cached=true", moved);
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static int executeFirstTankBridge(Level level, HydraulicPlan plan) {
        for (HydraulicPlan.Action action : plan.actions()) {
            if (action.type() != HydraulicPlan.ActionType.TANK_TO_TANK) continue;

            HydraulicPlan.Route route = action.route();
            FluidTankBlockEntity source = tankAt(level, route.source().owner());
            FluidTankBlockEntity sink = tankAt(level, route.sink().owner());
            if (source == null || sink == null) {
                DebugInfo.log(level, "HYDRAULIC_EXEC skip action={} reason=missingTank source={} sink={}", action.type(), route.source().owner(), route.sink().owner());
                continue;
            }

            int amount = Math.min(MAX_MOVE_MB, action.amountMb());
            int moved = moveFluid(source, sink, amount);
            if (moved <= 0) {
                DebugInfo.log(level, "HYDRAULIC_EXEC skip action={} reason=noMove source={} sink={} amountHint={} fluid={}",
                        action.type(), source.getController(), sink.getController(), action.amountMb(), route.source().fluid());
                continue;
            }

            DebugInfo.log(level,
                    "HYDRAULIC_EXEC tank->tank source={} sink={} moved={} amountHint={} deltaHead={} flowEstimate={} routeLength={} resistance={} leased={} source=SharedPlan cached=true",
                    source.getController(), sink.getController(), moved, action.amountMb(), route.deltaHead(), route.flowEstimateMb(), route.routeLength(), route.resistance(), route.leased());
            return moved;
        }
        return 0;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos controllerPos) {
        if (!(level.getBlockEntity(controllerPos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
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

    private static ProcessedTick processed(Level level) {
        long time = level.getGameTime();
        ProcessedTick processed = PROCESSED.get(level);
        if (processed == null || processed.gameTime != time) {
            processed = new ProcessedTick(time, new HashSet<>());
            PROCESSED.put(level, processed);
        }
        return processed;
    }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
}
