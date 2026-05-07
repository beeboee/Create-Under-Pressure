package com.beeboee.createunderpressure.visual;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Cosmetic-only world exchange effects for open pipe ends.
 *
 * This intentionally does not move fluid, consume world sources, or place world blocks.
 * The pressure/world-exchange service owns simulation and conservation; this layer only
 * gives the player a short Create-like visual cue that an inlet or outlet is active.
 */
public final class WorldExchangeVisualLayer {
    private WorldExchangeVisualLayer() {}

    private static final int TICK_INTERVAL = 2;
    private static final int MAX_SCAN_DISTANCE = 48;
    private static final int MAX_PARTICLES_PER_END = 4;

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || !level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        VisualScan scan = scan(level, seed);
        if (scan.pipes.isEmpty()) return;

        for (OpenEnd end : scan.openEnds) {
            BlockPos worldPos = end.pipe.relative(end.face);
            if (!level.isLoaded(worldPos)) continue;

            FluidState worldFluid = level.getFluidState(worldPos);
            if (!worldFluid.isEmpty()) {
                spawnIntakeParticles(level, end, worldFluid, scan.strength());
            } else if (level.getBlockState(worldPos).isAir() && scan.hasFluidSupply()) {
                spawnOutputParticles(level, end, false, scan.strength());
            }
        }
    }

    private static VisualScan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<OpenEnd> openEnds = new HashSet<>();
        boolean hasFluidSupply = false;
        int supplyScore = 0;

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

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    FluidStack stack = tank.getTankInventory().getFluid();
                    if (!stack.isEmpty()) {
                        hasFluidSupply = true;
                        supplyScore = Math.max(supplyScore, Math.min(1000, stack.getAmount() / 4));
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) {
                    openEnds.add(new OpenEnd(node.pos, face));
                    FluidState fluidState = level.getFluidState(other);
                    if (!fluidState.isEmpty()) {
                        hasFluidSupply = true;
                        supplyScore = Math.max(supplyScore, fluidState.isSource() ? 1000 : 450);
                    }
                }
            }
        }

        return new VisualScan(pipes, openEnds, hasFluidSupply, supplyScore);
    }

    private static void spawnIntakeParticles(Level level, OpenEnd end, FluidState fluidState, double strength) {
        boolean underwater = !fluidState.isEmpty();
        ParticleOptions particle = underwater ? ParticleTypes.BUBBLE : ParticleTypes.SPLASH;
        spawnDirectionalParticles(level, end, particle, -1.0, underwater, strength);
    }

    private static void spawnOutputParticles(Level level, OpenEnd end, boolean underwater, double strength) {
        ParticleOptions particle = underwater ? ParticleTypes.BUBBLE : ParticleTypes.SPLASH;
        spawnDirectionalParticles(level, end, particle, 1.0, underwater, strength);
    }

    private static void spawnDirectionalParticles(Level level, OpenEnd end, ParticleOptions particle, double directionMultiplier,
                                                  boolean underwater, double strength) {
        RandomSource random = level.random;
        Direction face = end.face;
        BlockPos pipePos = end.pipe;
        BlockPos outPos = pipePos.relative(face);

        double baseX = outPos.getX() + 0.5 - (face.getStepX() * 0.42);
        double baseY = outPos.getY() + 0.5 - (face.getStepY() * 0.42);
        double baseZ = outPos.getZ() + 0.5 - (face.getStepZ() * 0.42);

        double speed = 0.035 + (0.12 * strength);
        int count = 1 + Math.min(MAX_PARTICLES_PER_END - 1, (int) Math.round(strength * (MAX_PARTICLES_PER_END - 1)));

        for (int i = 0; i < count; i++) {
            double jitterX = (random.nextDouble() - 0.5) * 0.16;
            double jitterY = (random.nextDouble() - 0.5) * 0.16;
            double jitterZ = (random.nextDouble() - 0.5) * 0.16;

            double vx = (face.getStepX() * speed * directionMultiplier) + (jitterX * 0.12);
            double vy = (face.getStepY() * speed * directionMultiplier) + (underwater ? 0.035 + (0.08 * strength) : 0.015) + (jitterY * 0.12);
            double vz = (face.getStepZ() * speed * directionMultiplier) + (jitterZ * 0.12);

            level.addParticle(particle, baseX + jitterX, baseY + jitterY, baseZ + jitterZ, vx, vy, vz);
        }
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private record Node(BlockPos pos, int distance) {}
    private record OpenEnd(BlockPos pipe, Direction face) {}
    private record VisualScan(Set<BlockPos> pipes, Set<OpenEnd> openEnds, boolean hasFluidSupply, int supplyScore) {
        public double strength() {
            return Math.max(0.15, Math.min(1.0, supplyScore / 1000.0));
        }
    }
}
