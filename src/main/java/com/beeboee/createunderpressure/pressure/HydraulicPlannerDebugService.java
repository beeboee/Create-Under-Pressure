package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Diagnostic projection of the exact plan that HydraulicRuntime applies. */
public final class HydraulicPlannerDebugService {
    private HydraulicPlannerDebugService() {}

    private static final int TICK_INTERVAL = 20;
    private static final int MAX_PORT_LOGS = 48;
    private static final int MAX_ROUTE_LOGS = 24;
    private static final Map<Level, Map<BlockPos, Long>> LAST_LOGGED = new WeakHashMap<>();

    public static void logPlan(Level level, HydraulicPlanBuilder.BuildResult result) {
        if (level == null || !DebugInfo.isEnabled(level) || result == null || result.plan() == null) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        HydraulicPlan plan = result.plan();
        long last = LAST_LOGGED.computeIfAbsent(level, $ -> new HashMap<>())
                .getOrDefault(plan.owner(), Long.MIN_VALUE);
        if (last == level.getGameTime()) return;
        LAST_LOGGED.get(level).put(plan.owner(), level.getGameTime());

        DebugInfo.beginNetwork(level, result.pipes(), plan.owner());
        try {
            DebugInfo.log(level,
                    "HYDRAULIC_RUNTIME owner={} pipes={} ports={} candidates={} selected={} rejected={} pumps={} leases={} flowRate={}mb/t pressureScale={} headDeadBand={} execution=CreateFluidNetwork worldIO=CreateHosePulley",
                    plan.owner(), plan.pipeCount(), plan.ports().size(), result.candidateCount(), plan.actions().size(),
                    plan.rejectedActions().size(), result.pumpCount(), result.leasedCandidateCount(),
                    HydraulicPlanBuilder.FLOW_RATE_MB_PER_TICK, HydraulicRuntime.CREATE_PRESSURE_PER_MB,
                    HydraulicPlanBuilder.HEAD_DEAD_BAND);

            int portLogs = 0;
            for (HydraulicPlan.Port port : plan.ports()) {
                if (portLogs++ >= MAX_PORT_LOGS) {
                    DebugInfo.log(level, "HYDRAULIC_PORTS truncated total={} shown={}", plan.ports().size(), MAX_PORT_LOGS);
                    break;
                }
                DebugInfo.log(level,
                        "HYDRAULIC_PORT id={} type={} owner={} pipe={} face={} sourceHead={} cutoff={} reachable={} stored={} capacity={} fluid={}",
                        port.id(), port.type(), port.owner(), port.pipe(), port.face(), port.head(), port.cutoffHead(),
                        port.amountMb(), port.storedMb(), port.capacityMb(), port.fluid());
            }

            int selectedLogs = 0;
            for (HydraulicPlan.Action action : plan.actions()) {
                if (selectedLogs++ >= MAX_ROUTE_LOGS) break;
                HydraulicPlan.Route route = action.route();
                DebugInfo.log(level,
                        "HYDRAULIC_ROUTE selected action={} source={} sink={} amount={}mb/t sourceHead={} deliveredHead={} sinkHead={} delta={} pumpBoost={} length={} bends={} faces={} leased={} fluid={}",
                        action.type(), route.source().id(), route.sink().id(), action.amountMb(), route.source().head(),
                        route.deliveredHead(), requiredSinkHead(route.sink()), route.deltaHead(), route.pumpBoost(),
                        route.routeLength(), route.bends(), route.connections().size(), route.leased(), route.source().fluid());
            }

            int rejectedLogs = 0;
            for (HydraulicPlan.RejectedAction rejected : plan.rejectedActions()) {
                if (rejectedLogs++ >= MAX_ROUTE_LOGS) break;
                HydraulicPlan.Route route = rejected.route();
                DebugInfo.log(level,
                        "HYDRAULIC_ROUTE rejected reason={} action={} source={} sink={} amount={} delta={} length={} faces={} leased={}",
                        rejected.reason(), rejected.type(), route.source().id(), route.sink().id(), rejected.amountMb(),
                        route.deltaHead(), route.routeLength(), route.connections().size(), route.leased());
            }
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static double requiredSinkHead(HydraulicPlan.Port sink) {
        return sink.type() == HydraulicPlan.PortType.TANK ? Math.max(sink.cutoffHead(), sink.head()) : sink.cutoffHead();
    }
}
