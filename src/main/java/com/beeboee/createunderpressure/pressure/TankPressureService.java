package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.CreateUnderPressure;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives Create fluid tanks passive head pressure.
 *
 * - tanks pressurize pipe networks connected at or below their bottom layer
 * - branches that climb above the tank-bottom Y value are cut off
 * - Create's own pipe flow logic still performs the actual fluid movement
 */
public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 10;
    private static final int DEFAULT_MAX_DISTANCE = 64;
    private static final float PRESSURE_PER_BLOCK = 8.0f;
    private static final float MAX_PRESSURE = 256.0f;

    public static void tickTank(FluidTankBlockEntity tank) {
        Level level = tank.getLevel();

        if (level == null || level.isClientSide) {
            return;
        }

        if (!tank.isController()) {
            return;
        }

        if (level.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }

        CreateUnderPressure.LOGGER.info("Tank pressure tick at {}", tank.getBlockPos());

        if (tank.getTankInventory().getFluid().isEmpty()) {
            return;
        }

        BlockPos tankBottom = tank.getController();
        int tankBottomY = tankBottom.getY();

        for (int x = 0; x < tank.getWidth(); x++) {
            for (int z = 0; z < tank.getWidth(); z++) {
                BlockPos bottomTankBlock = tankBottom.offset(x, 0, z);
                applyFromTankBlock(level, bottomTankBlock, tankBottomY);
            }
        }
    }

    private static void applyFromTankBlock(Level level, BlockPos tankBlockPos, int tankBottomY) {
        for (Direction side : Direction.values()) {
            BlockPos pipePos = tankBlockPos.relative(side);
            if (!level.isLoaded(pipePos)) {
                continue;
            }

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);

            CreateUnderPressure.LOGGER.info(
                "Checking side tankBlock={} side={} pipePos={} foundPipe={}",
                tankBlockPos,
                side,
                pipePos,
                pipe != null
            );

            if (pipe == null) {
                continue;
            }

            if (pipePos.getY() > tankBottomY) {
                continue;
            }

            distributePressure(level, new BlockFace(pipePos, side.getOpposite()), tankBottomY);
        }
    }

    private static void distributePressure(Level level, BlockFace start, int tankBottomY) {
        Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();
        Set<BlockPos> visited = new java.util.HashSet<>();
        ArrayDeque<Pair<Integer, BlockPos>> frontier = new ArrayDeque<>();

        frontier.add(Pair.of(0, start.getPos()));

        while (!frontier.isEmpty()) {
            Pair<Integer, BlockPos> entry = frontier.removeFirst();
            int distance = entry.getFirst();
            BlockPos currentPos = entry.getSecond();

            if (distance > DEFAULT_MAX_DISTANCE) {
                continue;
            }

            if (!level.isLoaded(currentPos)) {
                continue;
            }

            if (!visited.add(currentPos)) {
                continue;
            }

            if (currentPos.getY() > tankBottomY) {
                continue;
            }

            BlockState currentState = level.getBlockState(currentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, currentPos);
            if (pipe == null) {
                continue;
            }

            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockFace blockFace = new BlockFace(currentPos, face);
                BlockPos connectedPos = blockFace.getConnectedPos();

                if (!level.isLoaded(connectedPos)) {
                    continue;
                }

                pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                    .getSecond()
                    .put(face, true);

                if (connectedPos.getY() > tankBottomY) {
                    continue;
                }

                FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(level, connectedPos);
                if (connectedPipe == null) {
                    continue;
                }

                if (visited.contains(connectedPos)) {
                    continue;
                }

                frontier.add(Pair.of(distance + 1, connectedPos));
            }
        }

        applyGraphPressure(level, pipeGraph, tankBottomY);
    }

    private static void applyGraphPressure(Level level, Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph, int tankBottomY) {
        for (Map.Entry<BlockPos, Pair<Integer, Map<Direction, Boolean>>> entry : pipeGraph.entrySet()) {
            BlockPos pipePos = entry.getKey();
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) {
                continue;
            }

            int head = tankBottomY - pipePos.getY();

            if (head < 0) {
                CreateUnderPressure.LOGGER.info(
                    "Skipping pressure above tank bottom pipePos={} tankBottomY={} pipeY={}",
                    pipePos,
                    tankBottomY,
                    pipePos.getY()
                );
                continue;
            }

            float pressure = Math.min(MAX_PRESSURE, Math.max(1, head) * PRESSURE_PER_BLOCK);

            CreateUnderPressure.LOGGER.info(
                "Pressure target pipePos={} head={} pressure={} sides={} hasPressureBefore={}",
                pipePos,
                head,
                pressure,
                entry.getValue().getSecond().keySet(),
                pipe.hasAnyPressure()
            );

            for (Direction side : entry.getValue().getSecond().keySet()) {
                pipe.addPressure(side, true, pressure);

                CreateUnderPressure.LOGGER.info(
                    "Added tank pressure TRUE pipePos={} side={} pressure={} hasPressureImmediately={} flowAfter={}",
                    pipePos,
                    side,
                    pressure,
                    pipe.hasAnyPressure(),
                    pipe.getFlow(side)
                );
            }

            FluidTransportBehaviour.cacheFlows(level, pipePos);

            CreateUnderPressure.LOGGER.info(
                "Cached flows pipePos={} hasPressureAfterCache={}",
                pipePos,
                pipe.hasAnyPressure()
            );
        }
    }
}
