package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.mixin.PipeConnectionAccessor;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * One runtime owns scan -> plan -> pressure projection.
 *
 * The pipe mixin only observes active pipe behaviours. At LevelTickEvent.Post,
 * after Create's block entities and pumps have ticked, this runtime builds each
 * network once, clears native pressure guesses, and projects the selected plan as
 * exact Create connection pressure for the next tick.
 */
public final class HydraulicRuntime {
    private HydraulicRuntime() {}

    public static final float CREATE_PRESSURE_PER_MB = 2.0f;

    private static final Map<Level, Set<BlockPos>> OBSERVED_PIPES = new WeakHashMap<>();

    public static void observePipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide || pipe.getPos() == null) return;
        OBSERVED_PIPES.computeIfAbsent(level, $ -> new HashSet<>()).add(pipe.getPos().immutable());
    }

    public static void tickLevel(ServerLevel level) {
        Set<BlockPos> observed = OBSERVED_PIPES.remove(level);
        if (observed == null || observed.isEmpty()) return;

        ArrayList<BlockPos> seeds = new ArrayList<>(observed);
        seeds.sort(HydraulicRuntime::compareBlockPos);
        Set<BlockPos> processed = new HashSet<>();

        for (BlockPos seed : seeds) {
            if (processed.contains(seed) || !level.isLoaded(seed)) continue;
            FluidTransportBehaviour seedPipe = FluidPropagator.getPipe(level, seed);
            if (seedPipe == null) continue;

            Set<String> leases = HydraulicPlanRuntime.lastSelectedRouteKeysContaining(level, seed);
            HydraulicPlanBuilder.BuildResult result = HydraulicPlanBuilder.build(level, seed, leases);
            if (result.pipes().isEmpty()) continue;

            processed.addAll(result.pipes());
            project(level, result);
            HydraulicPlanRuntime.remember(level, result, level.getGameTime());
            HydraulicPlannerDebugService.logPlan(level, result);
        }
    }

    private static void project(ServerLevel level, HydraulicPlanBuilder.BuildResult result) {
        // Exact replacement prevents the previous visual/addPressure layer and
        // Create's native pump pressure from stacking into uncontrolled flow rates.
        Set<BlockPos> changed = new HashSet<>();
        for (BlockPos pipePos : result.pipes()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                PipeConnection connection = pipe.getConnection(face);
                if (connection == null) continue;
                Couple<Float> pressure = ((PipeConnectionAccessor) (Object) connection).createUnderPressure$getPressure();
                pressure.set(true, 0.0f);
                pressure.set(false, 0.0f);
            }
            changed.add(pipePos);
        }

        Map<ConnectionKey, Float> desired = new HashMap<>();
        for (HydraulicPlan.Action action : result.plan().actions()) {
            float pressure = Math.max(1.0f, Math.min(256.0f, action.amountMb() * CREATE_PRESSURE_PER_MB));
            for (HydraulicPlan.ConnectionUse use : action.route().connections()) {
                ConnectionKey key = new ConnectionKey(use.pipe(), use.face(), use.inbound());
                desired.merge(key, pressure, Math::max);
            }
        }

        for (Map.Entry<ConnectionKey, Float> entry : desired.entrySet()) {
            ConnectionKey key = entry.getKey();
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, key.pipe);
            if (pipe == null) continue;
            PipeConnection connection = pipe.getConnection(key.face);
            if (connection == null) continue;
            Couple<Float> pressure = ((PipeConnectionAccessor) (Object) connection).createUnderPressure$getPressure();
            pressure.set(key.inbound, entry.getValue());
            changed.add(key.pipe);
        }

        for (BlockPos pipePos : changed) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe != null) pipe.blockEntity.sendData();
        }
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private record ConnectionKey(BlockPos pipe, Direction face, boolean inbound) {}
}
