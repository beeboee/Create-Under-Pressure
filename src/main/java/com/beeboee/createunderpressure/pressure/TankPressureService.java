package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.CreateUnderPressure;
import java.lang.reflect.Field;
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

    private static final int TICK_INTERVAL = 20;
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
        ArrayDeque<PathNode> frontier = new ArrayDeque<>();

        frontier.add(new PathNode(0, start.getPos(), start.getFace()));

        while (!frontier.isEmpty()) {
            PathNode entry = frontier.removeFirst();
            int distance = entry.distance();
            BlockPos currentPos = entry.pos();
            Direction backToTank = entry.backToTank();

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

                boolean pointsBackToTank = face == backToTank;
                boolean pressureIsInbound = pointsBackToTank;

                FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(level, connectedPos);
                boolean hasFluidCapability = FluidPropagator.hasFluidCapability(level, connectedPos, face.getOpposite());
                boolean openEnd = FluidPropagator.isOpenEnd(level, currentPos, face);

                CreateUnderPressure.LOGGER.info(
                    "Connection detail pipePos={} face={} connectedPos={} connectedPipe={} fluidCapability={} openEnd={} connectedY={} tankBottomY={} pointsBackToTank={} pressureInbound={}",
                    currentPos,
                    face,
                    connectedPos,
                    connectedPipe != null,
                    hasFluidCapability,
                    openEnd,
                    connectedPos.getY(),
                    tankBottomY,
                    pointsBackToTank,
                    pressureIsInbound
                );

                if (connectedPos.getY() > tankBottomY && !pointsBackToTank) {
                    continue;
                }

                pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                    .getSecond()
                    .put(face, pressureIsInbound);

                if (connectedPipe == null) {
                    continue;
                }

                if (visited.contains(connectedPos)) {
                    continue;
                }

                frontier.add(new PathNode(distance + 1, connectedPos, face.getOpposite()));
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
                entry.getValue().getSecond(),
                pipe.hasAnyPressure()
            );

            for (Map.Entry<Direction, Boolean> sideEntry : entry.getValue().getSecond().entrySet()) {
                Direction side = sideEntry.getKey();
                boolean pressureIsInbound = sideEntry.getValue();

                pipe.addPressure(side, pressureIsInbound, pressure);

                CreateUnderPressure.LOGGER.info(
                    "Added tank pressure DIRECTIONAL pipePos={} side={} inbound={} pressure={} hasPressureImmediately={} flowBeforeCache={}",
                    pipePos,
                    side,
                    pressureIsInbound,
                    pressure,
                    pipe.hasAnyPressure(),
                    describeFlow(pipe.getFlow(side))
                );
            }

            FluidTransportBehaviour.cacheFlows(level, pipePos);

            CreateUnderPressure.LOGGER.info(
                "Cached flows pipePos={} hasPressureAfterCache={}",
                pipePos,
                pipe.hasAnyPressure()
            );

            for (Direction side : Direction.values()) {
                CreateUnderPressure.LOGGER.info(
                    "Flow detail pipePos={} side={} flow={}",
                    pipePos,
                    side,
                    describeFlow(pipe.getFlow(side))
                );
            }
        }
    }

    private static String describeFlow(Object flow) {
        if (flow == null) {
            return "null";
        }

        StringBuilder description = new StringBuilder(flow.getClass().getSimpleName()).append("{");
        Field[] fields = flow.getClass().getDeclaredFields();

        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            try {
                field.setAccessible(true);
                description.append(field.getName()).append("=").append(field.get(flow));
            } catch (Throwable throwable) {
                description.append(field.getName()).append("=<unreadable:").append(throwable.getClass().getSimpleName()).append(">");
            }

            if (i < fields.length - 1) {
                description.append(", ");
            }
        }

        return description.append("}").toString();
    }

    private record PathNode(int distance, BlockPos pos, Direction backToTank) {}
}
