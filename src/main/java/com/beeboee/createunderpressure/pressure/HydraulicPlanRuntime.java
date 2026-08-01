package com.beeboee.createunderpressure.pressure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Shared current-plan cache for execution adapters, diagnostics, and short route leases. */
public final class HydraulicPlanRuntime {
    private HydraulicPlanRuntime() {}

    private static final int MAX_PLAN_AGE = 4;

    private static final Map<Level, Map<BlockPos, CachedPlan>> PLANS = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Set<String>>> LAST_SELECTED_ROUTES = new WeakHashMap<>();

    public enum WorldMode {
        NONE,
        INTAKE,
        OUTPUT
    }

    public static HydraulicPlanBuilder.BuildResult acquire(Level level, BlockPos seed, long gameTime) {
        CachedPlan cached = cachedContaining(level, seed, gameTime);
        if (cached != null) return cached.result();
        HydraulicPlanBuilder.BuildResult result = HydraulicPlanBuilder.build(level, seed, lastSelectedRouteKeysContaining(level, seed));
        remember(level, result, gameTime);
        return result;
    }

    public static void remember(Level level, HydraulicPlanBuilder.BuildResult result, long gameTime) {
        if (level == null || result == null || result.plan() == null) return;
        HydraulicPlan plan = result.plan();
        if (plan.owner() == null || result.pipes().isEmpty()) return;

        PLANS.computeIfAbsent(level, $ -> new HashMap<>())
                .put(plan.owner(), new CachedPlan(plan, result, gameTime));

        Set<String> routeKeys = new HashSet<>();
        for (HydraulicPlan.Action action : plan.actions()) routeKeys.add(action.reservationKey());
        LAST_SELECTED_ROUTES.computeIfAbsent(level, $ -> new HashMap<>())
                .put(plan.owner(), Set.copyOf(routeKeys));
        prune(level, gameTime);
    }

    public static Set<String> lastSelectedRouteKeys(Level level, BlockPos owner) {
        CachedPlan cached = cached(level, owner);
        if (!isRecent(level, cached)) return Set.of();

        Map<BlockPos, Set<String>> routes = LAST_SELECTED_ROUTES.get(level);
        if (routes == null) return Set.of();
        return routes.getOrDefault(owner, Set.of());
    }

    public static Set<String> lastSelectedRouteKeysContaining(Level level, BlockPos pipe) {
        Map<BlockPos, CachedPlan> plans = PLANS.get(level);
        if (plans == null) return Set.of();
        for (Map.Entry<BlockPos, CachedPlan> entry : plans.entrySet()) {
            CachedPlan cached = entry.getValue();
            if (!isRecent(level, cached)) continue;
            if (cached.result().pipes().contains(pipe)) return lastSelectedRouteKeys(level, entry.getKey());
        }
        return Set.of();
    }

    public static HydraulicPlan plan(Level level, BlockPos owner) {
        CachedPlan cached = cached(level, owner);
        return cached == null ? null : cached.plan();
    }

    public static HydraulicPlanBuilder.BuildResult buildResult(Level level, BlockPos owner) {
        CachedPlan cached = cached(level, owner);
        return cached == null ? null : cached.result();
    }

    public static WorldMode worldMode(Level level, BlockPos pipe, Direction face) {
        if (level == null || pipe == null || face == null) return WorldMode.NONE;
        Map<BlockPos, CachedPlan> plans = PLANS.get(level);
        if (plans == null) return WorldMode.NONE;

        for (CachedPlan cached : plans.values()) {
            if (!isRecent(level, cached)) continue;
            for (HydraulicPlan.Action action : cached.plan().actions()) {
                HydraulicPlan.Route route = action.route();
                if (route.source().type() == HydraulicPlan.PortType.WORLD
                        && pipe.equals(route.source().pipe()) && face == route.source().face()) return WorldMode.INTAKE;
                if (route.sink().type() == HydraulicPlan.PortType.WORLD
                        && pipe.equals(route.sink().pipe()) && face == route.sink().face()) return WorldMode.OUTPUT;
            }
        }
        return WorldMode.NONE;
    }

    public static NetworkPressurePlanner.PlannedVisual visualFor(Level level, BlockPos pipe, Direction face) {
        return switch (worldMode(level, pipe, face)) {
            case INTAKE -> NetworkPressurePlanner.PlannedVisual.INTAKE;
            case OUTPUT -> NetworkPressurePlanner.PlannedVisual.OUTPUT;
            case NONE -> null;
        };
    }

    private static boolean isRecent(Level level, CachedPlan cached) {
        return level != null && cached != null && level.getGameTime() - cached.gameTime() <= MAX_PLAN_AGE;
    }

    private static void prune(Level level, long gameTime) {
        Map<BlockPos, CachedPlan> plans = PLANS.get(level);
        if (plans == null) {
            LAST_SELECTED_ROUTES.remove(level);
            return;
        }

        plans.entrySet().removeIf(entry -> gameTime - entry.getValue().gameTime() > MAX_PLAN_AGE);
        if (plans.isEmpty()) {
            PLANS.remove(level);
            LAST_SELECTED_ROUTES.remove(level);
            return;
        }

        Map<BlockPos, Set<String>> routes = LAST_SELECTED_ROUTES.get(level);
        if (routes != null) {
            routes.keySet().removeIf(owner -> !plans.containsKey(owner));
            if (routes.isEmpty()) LAST_SELECTED_ROUTES.remove(level);
        }
    }

    private static CachedPlan cached(Level level, BlockPos owner) {
        Map<BlockPos, CachedPlan> plans = PLANS.get(level);
        return plans == null ? null : plans.get(owner);
    }

    private static CachedPlan cachedContaining(Level level, BlockPos seed, long gameTime) {
        Map<BlockPos, CachedPlan> plans = PLANS.get(level);
        if (plans == null) return null;
        for (CachedPlan cached : plans.values()) {
            if (cached.gameTime() == gameTime && cached.result().pipes().contains(seed)) return cached;
        }
        return null;
    }

    public record CachedPlan(HydraulicPlan plan, HydraulicPlanBuilder.BuildResult result, long gameTime) {}
}
