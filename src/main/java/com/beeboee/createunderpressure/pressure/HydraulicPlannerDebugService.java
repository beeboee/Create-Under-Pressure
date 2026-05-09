package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Diagnostic projection for the shared hydraulic planner core.
 *
 * This intentionally does not move fluid. It asks HydraulicPlanBuilder for the
 * same plan object that executor/visual code can consume later, then logs it.
 */
public final class HydraulicPlannerDebugService {
    private HydraulicPlannerDebugService() {}

    private static final int TICK_INTERVAL = 20;
    private static final int MAX_PORT_LOGS = 32;
    private static final int MAX_ROUTE_LOGS = 16;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Set<String>>> LAST_SELECTED_ROUTES = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide || !DebugInfo.isEnabled(level)) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        HydraulicPlanBuilder.BuildResult result = HydraulicPlanBuilder.build(level, seed, lastSelectedRouteKeys(level, seed));
        HydraulicPlan plan = result.plan();
        if (result.pipes().isEmpty()) return;

        BlockPos owner = plan.owner();
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(result.pipes());
        rememberSelectedRoutes(level, owner, plan.actions());

        DebugInfo.beginNetwork(level, result.pipes(), owner);
        try {
            DebugInfo.log(level, "HYDRAULIC_PLAN snapshot owner={} pipes={} ports={} candidates={} selected={} rejected={} pumps={} leasedCandidates={} note=sharedPlan diagnosticOnly deadBand={} flowScale={} baseR={} pipeR={} bendR={} maxActions={} maxWorldActions={}",
                    owner, plan.pipeCount(), plan.ports().size(), result.candidateCount(), plan.actions().size(), plan.rejectedActions().size(), result.pumpCount(), result.leasedCandidateCount(),
                    HydraulicPlanBuilder.HEAD_DEAD_BAND, HydraulicPlanBuilder.FLOW_SCALE, HydraulicPlanBuilder.BASE_ROUTE_RESISTANCE,
                    HydraulicPlanBuilder.PIPE_RESISTANCE, HydraulicPlanBuilder.BEND_RESISTANCE, HydraulicPlanBuilder.MAX_SELECTED_ACTIONS, HydraulicPlanBuilder.MAX_WORLD_ACTIONS);

            int portLogs = 0;
            for (HydraulicPlan.Port port : plan.ports()) {
                if (portLogs++ >= MAX_PORT_LOGS) {
                    DebugInfo.log(level, "HYDRAULIC_PLAN portsTruncated total={} shown={}", plan.ports().size(), MAX_PORT_LOGS);
                    break;
                }
                DebugInfo.log(level, "HYDRAULIC_PLAN port id={} type={} owner={} pipe={} face={} head={} amount={} capacity={} fluid={} contacts={}",
                        port.id(), port.type(), port.owner(), port.pipe(), port.face(), port.head(), port.amountMb(), port.capacityMb(), port.fluid(), port.contacts());
            }

            int selectedLogs = 0;
            for (HydraulicPlan.Action action : plan.actions()) {
                if (selectedLogs++ >= MAX_ROUTE_LOGS) break;
                HydraulicPlan.Route route = action.route();
                DebugInfo.log(level, "HYDRAULIC_PLAN selected action={} source={} sink={} deltaHead={} routeLength={} bends={} resistance={} flowEstimate={} amountHint={} leased={} sourceType={} sinkType={} fluid={} note=sharedPlan executableReservedOnly",
                        action.type(), route.source().id(), route.sink().id(), route.deltaHead(), route.routeLength(), route.bends(), route.resistance(), route.flowEstimateMb(), action.amountMb(), route.leased(), route.source().type(), route.sink().type(), route.source().fluid());
            }

            int rejectedLogs = 0;
            for (HydraulicPlan.RejectedAction rejected : plan.rejectedActions()) {
                if (rejectedLogs++ >= MAX_ROUTE_LOGS) break;
                HydraulicPlan.Route route = rejected.route();
                DebugInfo.log(level, "HYDRAULIC_PLAN rejected reason={} action={} source={} sink={} deltaHead={} routeLength={} bends={} resistance={} flowEstimate={} amountHint={} leased={} fluid={} note=sharedPlan diagnosticOnly",
                        rejected.reason(), rejected.type(), route.source().id(), route.sink().id(), route.deltaHead(), route.routeLength(), route.bends(), route.resistance(), route.flowEstimateMb(), rejected.amountMb(), route.leased(), route.source().fluid());
            }
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

    private static Set<String> lastSelectedRouteKeys(Level level, BlockPos owner) {
        Map<BlockPos, Set<String>> levelRoutes = LAST_SELECTED_ROUTES.get(level);
        if (levelRoutes == null) return Set.of();
        Set<String> routes = levelRoutes.get(owner);
        return routes == null ? Set.of() : routes;
    }

    private static void rememberSelectedRoutes(Level level, BlockPos owner, java.util.List<HydraulicPlan.Action> selected) {
        Map<BlockPos, Set<String>> levelRoutes = LAST_SELECTED_ROUTES.computeIfAbsent(level, $ -> new HashMap<>());
        Set<String> routeKeys = new HashSet<>();
        for (HydraulicPlan.Action action : selected) routeKeys.add(action.reservationKey());
        levelRoutes.put(owner, routeKeys);
    }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
}
