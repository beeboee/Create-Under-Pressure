package com.beeboee.createunderpressure.pressure;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Always-on lightweight planner pass.
 *
 * This does not move fluid. It only keeps HydraulicPlanRuntime populated so
 * executors and visuals can share one plan instead of separately guessing intent.
 */
public final class HydraulicPlanService {
    private HydraulicPlanService() {}

    private static final int TICK_INTERVAL = 8;
    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        HydraulicPlanBuilder.BuildResult result = HydraulicPlanBuilder.build(level, seed, HydraulicPlanRuntime.lastSelectedRouteKeys(level, seed));
        HydraulicPlan plan = result.plan();
        if (result.pipes().isEmpty()) return;

        BlockPos owner = plan.owner();
        if (!seed.equals(owner)) return;

        processed.pipes.addAll(result.pipes());
        HydraulicPlanRuntime.remember(level, result, level.getGameTime());
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
