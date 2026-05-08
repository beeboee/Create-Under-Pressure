package com.beeboee.createunderpressure.visual;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.pressure.NetworkPressurePlanner;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Cosmetic-only world exchange effects for open pipe ends.
 *
 * This layer now follows NetworkPressurePlanner's planned actions instead of trying
 * to independently infer whether an end should look active. That keeps visuals from
 * disagreeing with the actual sim.
 */
public final class WorldExchangeVisualLayer {
    private WorldExchangeVisualLayer() {}

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_SCAN_DISTANCE = 48;

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

            for (OpenEnd end : scan.openEnds) {
                NetworkPressurePlanner.PlannedVisual action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
                BlockPos worldPos = end.worldPos();

                if (action == null) {
                    skipped++;
                    if (debug) DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=noPlannerAction", end.pipe, end.face, worldPos);
                    continue;
                }

                planned++;
                FluidState fluidState = level.getFluidState(worldPos);
                boolean underwater = !fluidState.isEmpty();
                ParticleOptions particle = underwater ? ParticleTypes.BUBBLE : ParticleTypes.SPLASH;

                spawnPlannedParticles(level, end, action, particle, underwater);
                if (debug) {
                    DebugInfo.log(level, "VISUAL planned pipe={} face={} pos={} action={} underwater={} fluid={} source={}",
                            end.pipe, end.face, worldPos, action, underwater, fluidState.getType(), fluidState.isSource());
                }
            }

            if (debug) DebugInfo.log(level, "VISUAL scan owner={} openEnds={} planned={} skipped={} source=NetworkPressurePlanner",
                    owner, scan.openEnds.size(), planned, skipped);
        } finally {
            if (debug) DebugInfo.endNetwork();
        }
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

    private static void spawnPlannedParticles(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action,
                                              ParticleOptions particle, boolean underwater) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        Direction face = end.face;
        BlockPos worldPos = end.worldPos();
        double baseOffset = action == NetworkPressurePlanner.PlannedVisual.INTAKE ? 0.25 : -0.42;
        double x = worldPos.getX() + 0.5 + (face.getStepX() * baseOffset);
        double y = worldPos.getY() + 0.5 + (face.getStepY() * baseOffset);
        double z = worldPos.getZ() + 0.5 + (face.getStepZ() * baseOffset);

        // Server particles do not support the same clean directional velocity as the old client-only path.
        // Keep them sparse and tied strictly to actual planned actions; correctness matters more than spam.
        int count = underwater ? 2 : 1;
        double spread = underwater ? 0.08 : 0.04;
        double speed = action == NetworkPressurePlanner.PlannedVisual.OUTPUT ? 0.045 : 0.025;
        serverLevel.sendParticles(particle, x, y, z, count, spread, spread, spread, speed);
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
