package com.beeboee.createunderpressure.visual;

import com.beeboee.createunderpressure.debug.DebugInfo;
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

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_SCAN_DISTANCE = 48;
    private static final int MAX_PARTICLES_PER_END = 2;
    private static final double EPSILON = 0.01;

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        VisualScan scan = scan(level, seed);
        if (scan.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;

        if (!level.isClientSide) {
            logVisualDecisions(level, scan, owner);
            return;
        }

        for (OpenEnd end : scan.openEnds) {
            BlockPos worldPos = end.pipe.relative(end.face);
            if (!level.isLoaded(worldPos)) continue;

            FluidState worldFluid = level.getFluidState(worldPos);
            if (!worldFluid.isEmpty()) {
                spawnIntakeParticles(level, end, worldFluid, scan.strength());
            } else if (level.getBlockState(worldPos).isAir() && scan.canOutputFromPipe(end.pipe)) {
                spawnOutputParticles(level, end, false, scan.strength());
            }
        }
    }

    private static void logVisualDecisions(Level level, VisualScan scan, BlockPos owner) {
        if (!DebugInfo.isEnabled(level)) return;
        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            DebugInfo.log(level, "VISUAL scan owner={} openEnds={} tankOutputPipes={} supplyScore={} strength={}",
                    owner, scan.openEnds.size(), scan.tankOutputPipes.size(), scan.supplyScore, scan.strength());

            for (OpenEnd end : scan.openEnds) {
                BlockPos worldPos = end.pipe.relative(end.face);
                if (!level.isLoaded(worldPos)) {
                    DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=unloaded", end.pipe, end.face, worldPos);
                    continue;
                }

                FluidState worldFluid = level.getFluidState(worldPos);
                if (!worldFluid.isEmpty()) {
                    DebugInfo.log(level, "VISUAL intake pipe={} face={} pos={} fluid={} source={} strength={} reason=fluidAtOpenEnd",
                            end.pipe, end.face, worldPos, worldFluid.getType(), worldFluid.isSource(), scan.strength());
                    continue;
                }

                if (!level.getBlockState(worldPos).isAir()) {
                    DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} block={} reason=blocked",
                            end.pipe, end.face, worldPos, level.getBlockState(worldPos).getBlock());
                    continue;
                }

                if (scan.canOutputFromPipe(end.pipe)) {
                    DebugInfo.log(level, "VISUAL output pipe={} face={} pos={} strength={} reason=eligibleTankOutput",
                            end.pipe, end.face, worldPos, scan.strength());
                } else {
                    DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=noEligibleTankOutput",
                            end.pipe, end.face, worldPos);
                }
            }
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static VisualScan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<OpenEnd> openEnds = new HashSet<>();
        Set<BlockPos> tankOutputPipes = new HashSet<>();
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
                    if (!stack.isEmpty() && tankCanProvideToPipe(node.pos, face, tank)) {
                        tankOutputPipes.add(node.pos);
                        supplyScore = Math.max(supplyScore, Math.min(1000, stack.getAmount() / 4));
                    }
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) {
                    openEnds.add(new OpenEnd(node.pos, face));
                    FluidState fluidState = level.getFluidState(other);
                    if (!fluidState.isEmpty()) supplyScore = Math.max(supplyScore, fluidState.isSource() ? 1000 : 450);
                }
            }
        }

        return new VisualScan(pipes, openEnds, tankOutputPipes, supplyScore);
    }

    private static void spawnIntakeParticles(Level level, OpenEnd end, FluidState fluidState, double strength) {
        boolean underwater = !fluidState.isEmpty();
        ParticleOptions particle = underwater ? ParticleTypes.BUBBLE : ParticleTypes.SPLASH;
        spawnDirectionalParticles(level, end, particle, true, underwater, strength);
    }

    private static void spawnOutputParticles(Level level, OpenEnd end, boolean underwater, double strength) {
        ParticleOptions particle = underwater ? ParticleTypes.BUBBLE : ParticleTypes.SPLASH;
        spawnDirectionalParticles(level, end, particle, false, underwater, strength);
    }

    private static void spawnDirectionalParticles(Level level, OpenEnd end, ParticleOptions particle, boolean intake,
                                                  boolean underwater, double strength) {
        RandomSource random = level.random;
        Direction face = end.face;
        BlockPos outPos = end.pipe.relative(face);

        double baseOffset = intake && underwater ? 0.25 : -0.42;
        double baseX = outPos.getX() + 0.5 + (face.getStepX() * baseOffset);
        double baseY = outPos.getY() + 0.5 + (face.getStepY() * baseOffset);
        double baseZ = outPos.getZ() + 0.5 + (face.getStepZ() * baseOffset);

        double directionMultiplier = intake ? -1.0 : 1.0;
        double speed = 0.025 + (0.095 * strength);
        int count = 1 + Math.min(MAX_PARTICLES_PER_END - 1, (int) Math.floor(strength * MAX_PARTICLES_PER_END));

        for (int i = 0; i < count; i++) {
            double jitterX = (random.nextDouble() - 0.5) * 0.14;
            double jitterY = (random.nextDouble() - 0.5) * 0.14;
            double jitterZ = (random.nextDouble() - 0.5) * 0.14;

            double vx = (face.getStepX() * speed * directionMultiplier) + (jitterX * 0.08);
            double vy = (face.getStepY() * speed * directionMultiplier) + (underwater ? (intake ? 0.015 : 0.045 + (0.065 * strength)) : 0.012) + (jitterY * 0.08);
            double vz = (face.getStepZ() * speed * directionMultiplier) + (jitterZ * 0.08);

            level.addParticle(particle, baseX + jitterX, baseY + jitterY, baseZ + jitterZ, vx, vy, vz);
        }
    }

    private static boolean tankCanProvideToPipe(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return false;
        double cutoff = sourceCutoffSurface(pipe, face, tank);
        return surface(tank, amount) > cutoff + EPSILON && amount > amountForSurface(tank, cutoff);
    }

    private static double sourceCutoffSurface(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        BlockPos tankBlock = pipe.relative(face);
        double bottom = tank.getController().getY();
        double top = bottom + tank.getHeight();
        double cutoff = switch (face) {
            case UP -> tankBlock.getY();
            case DOWN -> tankBlock.getY() + 1.0;
            default -> tankBlock.getY();
        };
        return Math.max(bottom, Math.min(top, cutoff));
    }

    private static double surface(FluidTankBlockEntity tank, int amount) {
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        int capacity = tank.getTankInventory().getCapacity();
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(capacity, (int) Math.round(filledHeight * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / (double) Math.max(1, tank.getHeight());
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
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
    private record OpenEnd(BlockPos pipe, Direction face) {}
    private record VisualScan(Set<BlockPos> pipes, Set<OpenEnd> openEnds, Set<BlockPos> tankOutputPipes, int supplyScore) {
        boolean canOutputFromPipe(BlockPos pipe) {
            return tankOutputPipes.contains(pipe);
        }

        public double strength() {
            return Math.max(0.15, Math.min(1.0, supplyScore / 1000.0));
        }
    }
}
