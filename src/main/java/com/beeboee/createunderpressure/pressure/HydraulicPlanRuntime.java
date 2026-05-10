package com.beeboee.createunderpressure.pressure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Shared runtime cache for the lightweight hydraulic planner.
 *
 * This lets diagnostics, executors, and visual projection read the same
 * last-known plan instead of each subsystem re-inferring intent separately.
 */
public final class HydraulicPlanRuntime {
    private HydraulicPlanRuntime() {}

    private static final int MAX_VISUAL_PLAN_AGE = 8;

    private static final Map<Level, Map<BlockPos, CachedPlan>> PLANS = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Set<String>>> LAST_SELECTED_ROUTES = new WeakHashMap<>();

    public static HydraulicPlanBuilder.BuildResult acquire(Level level, BlockPos seed, long gameTime) {
        CachedPlan cached = cachedContaining(level, seed, gameTime);
        if (cached != null) return cached.result();

        HydraulicPlanBuilder.BuildResult result = HydraulicPlanBuilder.build(level, seed, lastSelectedRouteKeys(level, seed));
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
                .put(plan.owner(), routeKeys);
    }

    public static Set<String> lastSelectedRouteKeys(Level level, BlockPos ownerOrSeed) {
        Map<BlockPos, Set<String>> levelRoutes = LAST_SELECTED_ROUTES.get(level);
        if (levelRoutes == null) return Set.of();
        Set<String> routes = levelRoutes.get(ownerOrSeed);
        return routes == null ? Set.of() : routes;
    }

    public static HydraulicPlan plan(Level level, BlockPos owner) {
        CachedPlan cached = cached(level, owner);
        return cached == null ? null : cached.plan();
    }

    public static HydraulicPlanBuilder.BuildResult buildResult(Level level, BlockPos owner) {
        CachedPlan cached = cached(level, owner);
        return cached == null ? null : cached.result();
    }

    public static NetworkPressurePlanner.PlannedVisual visualFor(Level level, BlockPos pipe, Direction face) {
        if (level == null || pipe == null || face == null) return null;
        Map<BlockPos, CachedPlan> levelPlans = PLANS.get(level);
        if (levelPlans == null || levelPlans.isEmpty()) return null;

        long gameTime = level.getGameTime();
        levelPlans.entrySet().removeIf(entry -> gameTime - entry.getValue().gameTime() > MAX_VISUAL_PLAN_AGE);
        for (CachedPlan cached : levelPlans.values()) {
            if (gameTime - cached.gameTime() > MAX_VISUAL_PLAN_AGE) continue;
            NetworkPressurePlanner.PlannedVisual visual = visualFor(cached.plan(), pipe, face);
            if (visual != null) return visual;
        }
        return null;
    }

    private static NetworkPressurePlanner.PlannedVisual visualFor(HydraulicPlan plan, BlockPos pipe, Direction face) {
        if (plan == null) return null;
        for (HydraulicPlan.Action action : plan.actions()) {
            HydraulicPlan.Route route = action.route();
            if (route.source().type() == HydraulicPlan.PortType.WORLD
                    && pipe.equals(route.source().pipe())
                    && face == route.source().face()) {
                return NetworkPressurePlanner.PlannedVisual.INTAKE;
            }
            if (route.sink().type() == HydraulicPlan.PortType.WORLD
                    && pipe.equals(route.sink().pipe())
                    && face == route.sink().face()) {
                return NetworkPressurePlanner.PlannedVisual.OUTPUT;
            }
        }
        return null;
    }

    private static CachedPlan cached(Level level, BlockPos owner) {
        Map<BlockPos, CachedPlan> levelPlans = PLANS.get(level);
        return levelPlans == null ? null : levelPlans.get(owner);
    }

    private static CachedPlan cachedContaining(Level level, BlockPos seed, long gameTime) {
        Map<BlockPos, CachedPlan> levelPlans = PLANS.get(level);
        if (levelPlans == null || levelPlans.isEmpty()) return null;
        for (CachedPlan cached : levelPlans.values()) {
            if (cached.gameTime() == gameTime && cached.result().pipes().contains(seed)) return cached;
        }
        return null;
    }

    public record CachedPlan(HydraulicPlan plan, HydraulicPlanBuilder.BuildResult result, long gameTime) {}
}
