package com.beeboee.createunderpressure.visual;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.pressure.CreateWorldEndIO;
import com.beeboee.createunderpressure.pressure.NetworkPressurePlanner;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.util.ArrayDeque;
import java.util.HashSet;
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
 * open-end particle style to Create's PipeConnection helpers.
 */
public final class WorldExchangeVisualLayer {
    private WorldExchangeVisualLayer() {}

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_SCAN_DISTANCE = 48;
    private static final int WORLD_BLOCK_MB = 1000;

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

            for (OpenEnd end : scan.openEnds) {
                NetworkPressurePlanner.PlannedVisual action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
                BlockPos worldPos = end.worldPos();

                if (action == null) {
                    skipped++;
                    if (debug) DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=noPlannerAction", end.pipe, end.face, worldPos);
                    continue;
                }

                planned++;
                FluidStack visualFluid = visualFluid(level, end, action, networkVisualFluid);
                boolean splashOnRim = action == NetworkPressurePlanner.PlannedVisual.INTAKE;
                CreateWorldEndIO.spawnCreateParticles(level, end.pipe, end.face, visualFluid, splashOnRim);

                if (debug) {
                    FluidState fluidState = level.getFluidState(worldPos);
                    DebugInfo.log(level, "VISUAL planned pipe={} face={} pos={} action={} fluid={} source={} visualFluid={} networkVisualFluid={} source=CreatePipeConnection",
                            end.pipe, end.face, worldPos, action, fluidState.getType(), fluidState.isSource(), visualFluid, networkVisualFluid);
                }
            }

            if (debug) DebugInfo.log(level, "VISUAL scan owner={} openEnds={} planned={} skipped={} networkVisualFluid={} source=NetworkPressurePlanner/CreatePipeConnection",
                    owner, scan.openEnds.size(), planned, skipped, networkVisualFluid);
        } finally {
            if (debug) DebugInfo.endNetwork();
        }
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
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() {
            return pipe.relative(face);
        }
    }
    private record VisualScan(Set<BlockPos> pipes, Set<OpenEnd> openEnds) {}
}
