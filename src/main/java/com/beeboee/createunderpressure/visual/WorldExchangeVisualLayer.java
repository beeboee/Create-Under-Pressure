package com.beeboee.createunderpressure.visual;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.pressure.CreateWorldEndIO;
import com.beeboee.createunderpressure.pressure.NetworkPressurePlanner;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Cosmetic-only world exchange effects for open pipe ends.
 *
 * This layer follows NetworkPressurePlanner's planned actions, but delegates the actual
 * open-end particle style and pipe flow hints to Create's own helpers.
 */
public final class WorldExchangeVisualLayer {
    private WorldExchangeVisualLayer() {}

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_SCAN_DISTANCE = 48;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final float CREATE_FLOW_PRESSURE = 64.0f;

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        VisualScan scan = scan(level, seed);
        if (scan.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;

        logAndSpawnPlannedVisuals(level, scan, owner);
    }

    private static void logAndSpawnPlannedVisuals(Level level, VisualScan scan, BlockPos owner) {
        boolean debug = DebugInfo.isEnabled(level);
        if (debug) DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int planned = 0;
            int skipped = 0;
            FluidStack networkVisualFluid = networkVisualFluid(level, scan);
            List<PlannedEnd> plannedEnds = new ArrayList<>();

            for (OpenEnd end : scan.openEnds) {
                NetworkPressurePlanner.PlannedVisual action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
                BlockPos worldPos = end.worldPos();

                if (action == null) {
                    skipped++;
                    if (debug) DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=noPlannerAction", end.pipe, end.face, worldPos);
                    continue;
                }

                planned++;
                plannedEnds.add(new PlannedEnd(end, action));
                FluidStack visualFluid = visualFluid(level, end, action, networkVisualFluid);
                boolean splashOnRim = action == NetworkPressurePlanner.PlannedVisual.INTAKE;
                CreateWorldEndIO.spawnCreateParticles(level, end.pipe, end.face, visualFluid, splashOnRim);
                applyCreateFlowHint(level, end, action);

                if (debug) {
                    FluidState fluidState = level.getFluidState(worldPos);
                    DebugInfo.log(level, "VISUAL planned pipe={} face={} pos={} action={} fluid={} source={} visualFluid={} networkVisualFluid={} source=CreatePipeConnection flowHint=true",
                            end.pipe, end.face, worldPos, action, fluidState.getType(), fluidState.isSource(), visualFluid, networkVisualFluid);
                }
            }

            int routeHints = applyRouteFlowHints(level, scan, plannedEnds);
            int refreshes = planned == 0 ? refreshIdlePipeCache(level, scan) : 0;
            if (debug) DebugInfo.log(level, "VISUAL scan owner={} openEnds={} planned={} skipped={} routeHints={} refreshes={} networkVisualFluid={} source=NetworkPressurePlanner/CreatePipeConnection",
                    owner, scan.openEnds.size(), planned, skipped, routeHints, refreshes, networkVisualFluid);
        } finally {
            if (debug) DebugInfo.endNetwork();
        }
    }

    private static int refreshIdlePipeCache(Level level, VisualScan scan) {
        int refreshed = 0;
        for (BlockPos pipe : scan.pipes) {
            CreatePipeFlowVisualBridge.refresh(level, pipe);
            refreshed++;
        }
        return refreshed;
    }

    private static int applyRouteFlowHints(Level level, VisualScan scan, List<PlannedEnd> plannedEnds) {
        List<PlannedEnd> intakes = new ArrayList<>();
        List<PlannedEnd> outputs = new ArrayList<>();
        for (PlannedEnd planned : plannedEnds) {
            if (planned.action == NetworkPressurePlanner.PlannedVisual.INTAKE) intakes.add(planned);
            else if (planned.action == NetworkPressurePlanner.PlannedVisual.OUTPUT) outputs.add(planned);
        }

        int applied = 0;
        for (PlannedEnd intake : intakes) {
            for (PlannedEnd output : outputs) {
                List<Step> path = pipePath(level, scan, intake.end.pipe, output.end.pipe);
                if (path == null) continue;
                applyPathFlowHints(level, intake.end, output.end, path);
                applied++;
                DebugInfo.log(level, "VISUAL routeHint intake={} output={} path={}", intake.end.worldPos(), output.end.worldPos(), path.size());
            }
        }
        return applied;
    }

    private static void applyPathFlowHints(Level level, OpenEnd intake, OpenEnd output, List<Step> path) {
        applyCreateFlowHint(level, intake, NetworkPressurePlanner.PlannedVisual.INTAKE);
        for (Step step : path) {
            FluidTransportBehaviour fromPipe = FluidPropagator.getPipe(level, step.from);
            FluidTransportBehaviour toPipe = FluidPropagator.getPipe(level, step.to);
            CreatePipeFlowVisualBridge.apply(level, fromPipe, step.from, step.face, false, CREATE_FLOW_PRESSURE);
            CreatePipeFlowVisualBridge.apply(level, toPipe, step.to, step.face.getOpposite(), true, CREATE_FLOW_PRESSURE);
        }
        applyCreateFlowHint(level, output, NetworkPressurePlanner.PlannedVisual.OUTPUT);
    }

    private static List<Step> pipePath(Level level, VisualScan scan, BlockPos start, BlockPos target) {
        if (start.equals(target)) return List.of();
        Map<BlockPos, Step> from = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pipePos = queue.removeFirst();
            if (pipePos.equals(target)) break;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) continue;

            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                BlockPos next = pipePos.relative(face);
                if (!scan.pipes.contains(next) || !visited.add(next)) continue;
                from.put(next, new Step(pipePos, face, next));
                queue.add(next);
            }
        }

        if (!from.containsKey(target)) return null;
        List<Step> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(start)) {
            Step step = from.get(cursor);
            if (step == null) return null;
            reversed.add(step);
            cursor = step.from;
        }

        List<Step> path = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) path.add(reversed.get(i));
        return path;
    }

    private static void applyCreateFlowHint(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, end.pipe);
        boolean inbound = action == NetworkPressurePlanner.PlannedVisual.INTAKE;
        CreatePipeFlowVisualBridge.apply(level, pipe, end.pipe, end.face, inbound, CREATE_FLOW_PRESSURE);
    }

    private static FluidStack networkVisualFluid(Level level, VisualScan scan) {
        for (OpenEnd end : scan.openEnds) {
            NetworkPressurePlanner.PlannedVisual action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
            if (action != NetworkPressurePlanner.PlannedVisual.INTAKE) continue;

            FluidStack drained = CreateWorldEndIO.simulateDrain(level, end.pipe, end.face);
            if (!drained.isEmpty()) return drained;

            FluidState fluidState = level.getFluidState(end.worldPos());
            if (!fluidState.isEmpty()) return new FluidStack(fluidState.getType(), WORLD_BLOCK_MB);
        }

        for (OpenEnd end : scan.openEnds) {
            FluidState fluidState = level.getFluidState(end.worldPos());
            if (!fluidState.isEmpty()) return new FluidStack(fluidState.getType(), WORLD_BLOCK_MB);
        }

        return new FluidStack(Fluids.WATER, WORLD_BLOCK_MB);
    }

    private static FluidStack visualFluid(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action, FluidStack networkVisualFluid) {
        if (action == NetworkPressurePlanner.PlannedVisual.INTAKE) {
            FluidStack drained = CreateWorldEndIO.simulateDrain(level, end.pipe, end.face);
            if (!drained.isEmpty()) return drained;
        }

        FluidState fluidState = level.getFluidState(end.worldPos());
        if (!fluidState.isEmpty()) return new FluidStack(fluidState.getType(), WORLD_BLOCK_MB);
        return networkVisualFluid.copy();
    }

    private static VisualScan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<OpenEnd> openEnds = new HashSet<>();

        queue.add(new Node(seed, 0));
        visited.add(seed);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_SCAN_DISTANCE) continue;
            if (!level.isLoaded(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;
            pipes.add(node.pos);

            BlockState state = level.getBlockState(node.pos);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null && visited.add(other)) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new VisualScan(pipes, openEnds);
    }

    private static BlockPos ownerPipe(Set<BlockPos> pipes) {
        BlockPos owner = null;
        for (BlockPos pipe : pipes) {
            if (owner == null || compareBlockPos(pipe, owner) < 0) owner = pipe;
        }
        return owner;
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private record Node(BlockPos pos, int distance) {}
    private record PlannedEnd(OpenEnd end, NetworkPressurePlanner.PlannedVisual action) {}
    private record Step(BlockPos from, Direction face, BlockPos to) {}
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() {
            return pipe.relative(face);
        }
    }
    private record VisualScan(Set<BlockPos> pipes, Set<OpenEnd> openEnds) {}
}
