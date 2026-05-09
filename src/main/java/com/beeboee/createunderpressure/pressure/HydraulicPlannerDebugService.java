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
    private static final int MAX_PORT_LOGS = 32;
    private static final int MAX_ROUTE_LOGS = 16;
    private static final int MAX_SELECTED_ACTIONS = 4;
    private static final int MAX_WORLD_ACTIONS = 1;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final double HEAD_DEAD_BAND = 0.05;

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
            List<PlannedRoute> candidates = routes(snapshot.ports);
            Selection selection = select(candidates);
            DebugInfo.log(level, "HYDRAULIC_PLAN snapshot owner={} pipes={} ports={} candidates={} selected={} rejected={} pumps={} note=diagnosticOnly deadBand={} maxActions={} maxWorldActions={}",
                    owner, snapshot.pipes.size(), snapshot.ports.size(), candidates.size(), selection.selected.size(), selection.rejected.size(), snapshot.pumps.size(), HEAD_DEAD_BAND, MAX_SELECTED_ACTIONS, MAX_WORLD_ACTIONS);

            int portLogs = 0;
            for (HydraulicPort port : snapshot.ports) {
                if (portLogs++ >= MAX_PORT_LOGS) {
                    DebugInfo.log(level, "HYDRAULIC_PLAN portsTruncated total={} shown={}", snapshot.ports.size(), MAX_PORT_LOGS);
                    break;
                }
                DebugInfo.log(level, "HYDRAULIC_PLAN port id={} type={} owner={} pipe={} face={} head={} amount={} capacity={} fluid={} contacts={}",
                        port.id, port.type, port.owner, port.pipe, port.face, port.head, port.amount, port.capacity, port.fluid, port.contacts);
            }

            int selectedLogs = 0;
            for (PlannedRoute route : selection.selected) {
                if (selectedLogs++ >= MAX_ROUTE_LOGS) break;
                DebugInfo.log(level, "HYDRAULIC_PLAN selected action={} source={} sink={} deltaHead={} amountHint={} sourceType={} sinkType={} fluid={} note=executableReservedOnly",
                        route.action, route.source.id, route.sink.id, route.deltaHead, route.amountHint, route.source.type, route.sink.type, route.source.fluid);
            }

            int rejectedLogs = 0;
            for (RejectedRoute rejected : selection.rejected) {
                if (rejectedLogs++ >= MAX_ROUTE_LOGS) break;
                PlannedRoute route = rejected.route;
                DebugInfo.log(level, "HYDRAULIC_PLAN rejected reason={} action={} source={} sink={} deltaHead={} amountHint={} fluid={} note=diagnosticOnly",
                        rejected.reason, route.action, route.source.id, route.sink.id, route.deltaHead, route.amountHint, route.source.fluid);
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
        int amount = fluidState.isEmpty() ? 0 : WORLD_BLOCK_MB;
        double head = fluidState.isEmpty() ? worldPos.getY() : worldPos.getY() + 1.0;
        return new HydraulicPort("world:" + pipe.toShortString() + ":" + face.getName(), PortType.WORLD, worldPos, pipe, face, head, amount, WORLD_BLOCK_MB, fluid, 1);
    }

    private static List<PlannedRoute> routes(List<HydraulicPort> ports) {
        List<PlannedRoute> routes = new ArrayList<>();
        for (HydraulicPort source : ports) {
            if (source.amount <= 0 || source.fluid.equals("empty")) continue;
            for (HydraulicPort sink : ports) {
                if (source == sink || sink.capacity - sink.amount <= 0) continue;
                if (!compatible(source, sink)) continue;

                double deltaHead = source.head - sink.head;
                if (deltaHead <= HEAD_DEAD_BAND) continue;

                int amountHint = Math.min(source.amount, Math.min(sink.capacity - sink.amount, WORLD_BLOCK_MB));
                if (amountHint <= 0) continue;

                routes.add(new PlannedRoute(action(source, sink), source, sink, deltaHead, amountHint));
            }
        }
        routes.sort(Comparator
                .comparingDouble((PlannedRoute route) -> route.deltaHead).reversed()
                .thenComparing(route -> route.source.id)
                .thenComparing(route -> route.sink.id));
        return routes;
    }

    private static Selection select(List<PlannedRoute> candidates) {
        ReservationSet reservations = new ReservationSet();
        List<PlannedRoute> selected = new ArrayList<>();
        List<RejectedRoute> rejected = new ArrayList<>();
        int worldActions = 0;

        for (PlannedRoute route : candidates) {
            RejectReason executabilityReject = executabilityReject(route);
            if (executabilityReject != null) {
                rejected.add(new RejectedRoute(route, executabilityReject));
                continue;
            }
            if (selected.size() >= MAX_SELECTED_ACTIONS) {
                rejected.add(new RejectedRoute(route, RejectReason.ACTION_LIMIT));
                continue;
            }
            if (route.involvesWorld() && worldActions >= MAX_WORLD_ACTIONS) {
                rejected.add(new RejectedRoute(route, RejectReason.WORLD_ACTION_LIMIT));
                continue;
            }
            if (!reservations.reserve(route)) {
                rejected.add(new RejectedRoute(route, RejectReason.RESERVED_PORT));
                continue;
            }
            selected.add(route);
            if (route.involvesWorld()) worldActions++;
        }

        return new Selection(selected, rejected);
    }

    private static RejectReason executabilityReject(PlannedRoute route) {
        if (!route.involvesWorld()) return null;
        if (route.amountHint < WORLD_BLOCK_MB) return RejectReason.WORLD_BUCKET_REQUIRED;
        if (route.source.type == PortType.WORLD && route.source.amount < WORLD_BLOCK_MB) return RejectReason.WORLD_SOURCE_BELOW_BUCKET;
        if (route.sink.type == PortType.WORLD && route.source.amount < WORLD_BLOCK_MB) return RejectReason.WORLD_OUTPUT_SOURCE_BELOW_BUCKET;
        if (route.sink.type == PortType.WORLD && route.sink.capacity - route.sink.amount < WORLD_BLOCK_MB) return RejectReason.WORLD_OUTPUT_NOT_EMPTY;
        return null;
    }

    private static boolean compatible(HydraulicPort source, HydraulicPort sink) {
        return sink.fluid.equals("empty") || source.fluid.equals(sink.fluid);
    }

    private static Action action(HydraulicPort source, HydraulicPort sink) {
        if (source.type == PortType.WORLD && sink.type == PortType.WORLD) return Action.WORLD_TO_WORLD;
        if (source.type == PortType.WORLD) return Action.WORLD_TO_TANK;
        if (sink.type == PortType.WORLD) return Action.TANK_TO_WORLD;
        return Action.TANK_TO_TANK;
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
                    PortType.TANK,
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

    private static final class ReservationSet {
        private final Set<String> sourceKeys = new HashSet<>();
        private final Set<String> sinkKeys = new HashSet<>();
        private final Set<String> worldKeys = new HashSet<>();

        boolean reserve(PlannedRoute route) {
            String sourceKey = sourceKey(route.source);
            String sinkKey = sinkKey(route.sink);
            String worldSourceKey = route.source.type == PortType.WORLD ? worldKey(route.source) : null;
            String worldSinkKey = route.sink.type == PortType.WORLD ? worldKey(route.sink) : null;

            if (sourceKeys.contains(sourceKey) || sinkKeys.contains(sinkKey)) return false;
            if (worldSourceKey != null && worldKeys.contains(worldSourceKey)) return false;
            if (worldSinkKey != null && worldKeys.contains(worldSinkKey)) return false;

            sourceKeys.add(sourceKey);
            sinkKeys.add(sinkKey);
            if (worldSourceKey != null) worldKeys.add(worldSourceKey);
            if (worldSinkKey != null) worldKeys.add(worldSinkKey);
            return true;
        }

        private static String sourceKey(HydraulicPort port) {
            return port.type + ":source:" + port.owner;
        }

        private static String sinkKey(HydraulicPort port) {
            return port.type + ":sink:" + port.owner;
        }

        private static String worldKey(HydraulicPort port) {
            return "WORLD:" + port.owner;
        }
    }

    private enum PortType { TANK, WORLD }
    private enum Action { TANK_TO_TANK, TANK_TO_WORLD, WORLD_TO_TANK, WORLD_TO_WORLD }
    private enum RejectReason { ACTION_LIMIT, WORLD_ACTION_LIMIT, RESERVED_PORT, WORLD_BUCKET_REQUIRED, WORLD_SOURCE_BELOW_BUCKET, WORLD_OUTPUT_SOURCE_BELOW_BUCKET, WORLD_OUTPUT_NOT_EMPTY }
    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}
    private record Node(BlockPos pos, int distance) {}
    private record Snapshot(Set<BlockPos> pipes, List<HydraulicPort> ports, Set<BlockPos> pumps) {}
    private record HydraulicPort(String id, PortType type, BlockPos owner, BlockPos pipe, Direction face, double head, int amount, int capacity, String fluid, int contacts) {}
    private record PlannedRoute(Action action, HydraulicPort source, HydraulicPort sink, double deltaHead, int amountHint) {
        boolean involvesWorld() { return source.type == PortType.WORLD || sink.type == PortType.WORLD; }
    }
    private record RejectedRoute(PlannedRoute route, RejectReason reason) {}
    private record Selection(List<PlannedRoute> selected, List<RejectedRoute> rejected) {}
}
