package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Diagnostic-only skeleton for the next hydraulic layer.
 *
 * This intentionally does not move fluid. It samples the network as ports and
 * routes so we can compare one planned source of truth against the current
 * HOSE_IO / HEAD_BRIDGE / VISUAL split before replacing behavior.
 */
public final class HydraulicPlannerDebugService {
    private HydraulicPlannerDebugService() {}

    private static final int TICK_INTERVAL = 20;
    private static final int MAX_SCAN_DISTANCE = 128;
    private static final int MAX_ROUTE_LOGS = 16;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide || !DebugInfo.isEnabled(level)) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Snapshot snapshot = scan(level, seed);
        if (snapshot.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(snapshot.pipes);
        if (!seed.equals(owner)) return;
        processed.pipes.addAll(snapshot.pipes);

        DebugInfo.beginNetwork(level, snapshot.pipes, owner);
        try {
            List<PlannedRoute> routes = routes(snapshot.ports);
            DebugInfo.log(level, "HYDRAULIC_PLAN snapshot owner={} pipes={} ports={} routes={} pumps={} note=diagnosticOnly",
                    owner, snapshot.pipes.size(), snapshot.ports.size(), routes.size(), snapshot.pumps.size());

            for (HydraulicPort port : snapshot.ports) {
                DebugInfo.log(level, "HYDRAULIC_PLAN port id={} type={} owner={} pipe={} face={} head={} amount={} capacity={} fluid={} contacts={}",
                        port.id, port.type, port.owner, port.pipe, port.face, port.head, port.amount, port.capacity, port.fluid, port.contacts);
            }

            int logged = 0;
            for (PlannedRoute route : routes) {
                if (logged++ >= MAX_ROUTE_LOGS) break;
                DebugInfo.log(level, "HYDRAULIC_PLAN route source={} sink={} deltaHead={} sourceType={} sinkType={} note=uncommitted",
                        route.source.id, route.sink.id, route.deltaHead, route.source.type, route.sink.type);
            }
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static ProcessedTick processed(Level level) {
        long time = level.getGameTime();
        ProcessedTick processed = PROCESSED.get(level);
        if (processed == null || processed.gameTime != time) {
            processed = new ProcessedTick(time, new HashSet<>());
            PROCESSED.put(level, processed);
        }
        return processed;
    }

    private static Snapshot scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Map<BlockPos, TankPortBuilder> tanks = new HashMap<>();
        List<HydraulicPort> worldPorts = new ArrayList<>();
        Set<BlockPos> pumps = new HashSet<>();
        Set<String> worldKeys = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(seed, 0));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_SCAN_DISTANCE) continue;
            if (!level.isLoaded(node.pos) || !pipes.add(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;

            BlockState state = level.getBlockState(node.pos);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                if (PumpHeadPressure.isPump(level, other)) {
                    pumps.add(other.immutable());
                    continue;
                }

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    tanks.computeIfAbsent(tank.getController(), $ -> new TankPortBuilder(tank))
                            .addContact(node.pos, face);
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) {
                    String key = node.pos + "|" + face;
                    if (worldKeys.add(key)) worldPorts.add(worldPort(level, node.pos, face));
                }
            }

            for (Direction direction : Direction.values()) {
                BlockPos other = node.pos.relative(direction);
                if (level.isLoaded(other) && PumpHeadPressure.isPump(level, other)) pumps.add(other.immutable());
            }
        }

        List<HydraulicPort> ports = new ArrayList<>();
        for (TankPortBuilder builder : tanks.values()) ports.add(builder.build());
        ports.addAll(worldPorts);
        ports.sort(Comparator.comparing(port -> port.id));

        return new Snapshot(pipes, ports, pumps);
    }

    private static HydraulicPort worldPort(Level level, BlockPos pipe, Direction face) {
        BlockPos worldPos = pipe.relative(face);
        FluidState fluidState = level.getFluidState(worldPos);
        String fluid = fluidState.isEmpty() ? "empty" : String.valueOf(fluidState.getType());
        int amount = fluidState.isEmpty() ? 0 : 1000;
        double head = fluidState.isEmpty() ? worldPos.getY() : worldPos.getY() + 1.0;
        return new HydraulicPort("world:" + pipe.toShortString() + ":" + face.getName(), "WORLD", worldPos, pipe, face, head, amount, 1000, fluid, 1);
    }

    private static List<PlannedRoute> routes(List<HydraulicPort> ports) {
        List<PlannedRoute> routes = new ArrayList<>();
        for (HydraulicPort source : ports) {
            if (source.amount <= 0) continue;
            for (HydraulicPort sink : ports) {
                if (source == sink || sink.capacity - sink.amount <= 0) continue;
                double deltaHead = source.head - sink.head;
                if (deltaHead <= 0.0) continue;
                routes.add(new PlannedRoute(source, sink, deltaHead));
            }
        }
        routes.sort(Comparator
                .comparingDouble((PlannedRoute route) -> route.deltaHead).reversed()
                .thenComparing(route -> route.source.id)
                .thenComparing(route -> route.sink.id));
        return routes;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static double tankSurface(FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / (double) Math.max(1, tank.getHeight());
    }

    private static BlockPos ownerPipe(Set<BlockPos> pipes) {
        BlockPos owner = null;
        for (BlockPos pipe : pipes) if (owner == null || compareBlockPos(pipe, owner) < 0) owner = pipe;
        return owner;
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static final class TankPortBuilder {
        final FluidTankBlockEntity tank;
        int contacts;
        BlockPos firstPipe;
        Direction firstFace;

        TankPortBuilder(FluidTankBlockEntity tank) {
            this.tank = tank;
        }

        void addContact(BlockPos pipe, Direction face) {
            contacts++;
            if (firstPipe == null || compareBlockPos(pipe, firstPipe) < 0) {
                firstPipe = pipe;
                firstFace = face;
            }
        }

        HydraulicPort build() {
            FluidStack stack = tank.getTankInventory().getFluid();
            String fluid = stack.isEmpty() ? "empty" : String.valueOf(stack.getFluid());
            return new HydraulicPort(
                    "tank:" + tank.getController().toShortString(),
                    "TANK",
                    tank.getController(),
                    firstPipe,
                    firstFace,
                    tankSurface(tank),
                    tank.getTankInventory().getFluidAmount(),
                    tank.getTankInventory().getCapacity(),
                    fluid,
                    contacts);
        }
    }

    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Snapshot(Set<BlockPos> pipes, List<HydraulicPort> ports, Set<BlockPos> pumps) {}
    private record HydraulicPort(String id, String type, BlockPos owner, BlockPos pipe, Direction face, double head, int amount, int capacity, String fluid, int contacts) {}
    private record PlannedRoute(HydraulicPort source, HydraulicPort sink, double deltaHead) {}
}
