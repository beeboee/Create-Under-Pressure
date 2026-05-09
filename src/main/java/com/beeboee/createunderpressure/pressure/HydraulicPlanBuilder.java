package com.beeboee.createunderpressure.pressure;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Shared diagnostic planner core.
 *
 * This is the first extraction toward the directive's scanner -> planner -> executor -> visuals contract.
 * It still does not move fluid. It only returns an immutable HydraulicPlan plus scan metadata.
 */
public final class HydraulicPlanBuilder {
    private HydraulicPlanBuilder() {}

    public static final int MAX_SCAN_DISTANCE = 128;
    public static final int MAX_SELECTED_ACTIONS = 4;
    public static final int MAX_WORLD_ACTIONS = 1;
    public static final int WORLD_BLOCK_MB = 1000;
    public static final int MAX_FLOW_MB = 1000;
    public static final double HEAD_DEAD_BAND = 0.05;
    public static final double FLOW_SCALE = 600.0;
    public static final double BASE_ROUTE_RESISTANCE = 1.0;
    public static final double PIPE_RESISTANCE = 0.01;
    public static final double BEND_RESISTANCE = 0.08;

    public static BuildResult build(Level level, BlockPos seed, Set<String> leasedRouteKeys) {
        Snapshot snapshot = scan(level, seed);
        if (snapshot.pipes.isEmpty()) {
            return new BuildResult(new HydraulicPlan(seed, 0, List.of(), List.of(), List.of()), Set.of(), 0, 0, 0);
        }

        BlockPos owner = ownerPipe(snapshot.pipes);
        List<Candidate> candidates = candidates(snapshot.ports, leasedRouteKeys == null ? Set.of() : leasedRouteKeys);
        Selection selection = select(candidates);

        List<HydraulicPlan.Action> actions = new ArrayList<>();
        for (Candidate candidate : selection.selected) {
            actions.add(new HydraulicPlan.Action(candidate.type, candidate.route, candidate.amountHint, candidate.routeKey));
        }

        List<HydraulicPlan.RejectedAction> rejected = new ArrayList<>();
        for (RejectedCandidate rejectedCandidate : selection.rejected) {
            Candidate candidate = rejectedCandidate.candidate;
            rejected.add(new HydraulicPlan.RejectedAction(candidate.type, candidate.route, candidate.amountHint, rejectedCandidate.reason));
        }

        HydraulicPlan plan = new HydraulicPlan(owner, snapshot.pipes.size(), snapshot.ports, actions, rejected);
        return new BuildResult(plan, snapshot.pipes, candidates.size(), countLeased(candidates), snapshot.pumps.size());
    }

    private static Snapshot scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Map<BlockPos, TankPortBuilder> tanks = new HashMap<>();
        List<HydraulicPlan.Port> worldPorts = new ArrayList<>();
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

        List<HydraulicPlan.Port> ports = new ArrayList<>();
        for (TankPortBuilder builder : tanks.values()) ports.add(builder.build());
        ports.addAll(worldPorts);
        ports.sort(Comparator.comparing(HydraulicPlan.Port::id));

        return new Snapshot(pipes, ports, pumps);
    }

    private static HydraulicPlan.Port worldPort(Level level, BlockPos pipe, Direction face) {
        BlockPos worldPos = pipe.relative(face);
        FluidState fluidState = level.getFluidState(worldPos);
        String fluid = fluidState.isEmpty() ? "empty" : String.valueOf(fluidState.getType());
        int amount = fluidState.isEmpty() ? 0 : WORLD_BLOCK_MB;
        double head = fluidState.isEmpty() ? worldPos.getY() : worldPos.getY() + 1.0;
        return new HydraulicPlan.Port("world:" + pipe.toShortString() + ":" + face.getName(), HydraulicPlan.PortType.WORLD, worldPos, pipe, face, head, amount, WORLD_BLOCK_MB, fluid, 1);
    }

    private static List<Candidate> candidates(List<HydraulicPlan.Port> ports, Set<String> leasedRouteKeys) {
        List<Candidate> routes = new ArrayList<>();
        for (HydraulicPlan.Port source : ports) {
            if (source.amountMb() <= 0 || source.fluid().equals("empty")) continue;
            for (HydraulicPlan.Port sink : ports) {
                if (source == sink || sink.capacityMb() - sink.amountMb() <= 0) continue;
                if (!compatible(source, sink)) continue;

                double deltaHead = source.head() - sink.head();
                if (deltaHead <= HEAD_DEAD_BAND) continue;

                int routeLength = routeLength(source, sink);
                int bends = routeBends(source, sink);
                double resistance = routeResistance(routeLength, bends);
                int flowEstimate = flowEstimate(deltaHead, resistance);
                int amountHint = Math.min(flowEstimate, Math.min(source.amountMb(), sink.capacityMb() - sink.amountMb()));
                if (amountHint <= 0) continue;

                HydraulicPlan.ActionType type = actionType(source, sink);
                String routeKey = routeKey(type, source, sink);
                boolean leased = leasedRouteKeys.contains(routeKey);
                HydraulicPlan.Route route = new HydraulicPlan.Route(source, sink, deltaHead, routeLength, bends, resistance, flowEstimate, leased);
                routes.add(new Candidate(type, route, routeKey, amountHint));
            }
        }
        routes.sort(Comparator
                .comparing((Candidate candidate) -> candidate.route.leased()).reversed()
                .thenComparingDouble((Candidate candidate) -> candidate.route.deltaHead()).reversed()
                .thenComparingDouble(candidate -> candidate.route.resistance())
                .thenComparing(candidate -> candidate.route.source().id())
                .thenComparing(candidate -> candidate.route.sink().id()));
        return routes;
    }

    private static Selection select(List<Candidate> candidates) {
        ReservationSet reservations = new ReservationSet();
        List<Candidate> selected = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        int worldActions = 0;

        for (Candidate candidate : candidates) {
            HydraulicPlan.RejectReason executabilityReject = executabilityReject(candidate);
            if (executabilityReject != null) {
                rejected.add(new RejectedCandidate(candidate, executabilityReject));
                continue;
            }
            if (selected.size() >= MAX_SELECTED_ACTIONS) {
                rejected.add(new RejectedCandidate(candidate, HydraulicPlan.RejectReason.ACTION_LIMIT));
                continue;
            }
            if (involvesWorld(candidate.route) && worldActions >= MAX_WORLD_ACTIONS) {
                rejected.add(new RejectedCandidate(candidate, HydraulicPlan.RejectReason.WORLD_ACTION_LIMIT));
                continue;
            }
            if (!reservations.reserve(candidate.route)) {
                rejected.add(new RejectedCandidate(candidate, HydraulicPlan.RejectReason.RESERVED_PORT));
                continue;
            }
            selected.add(candidate);
            if (involvesWorld(candidate.route)) worldActions++;
        }

        return new Selection(selected, rejected);
    }

    private static HydraulicPlan.RejectReason executabilityReject(Candidate candidate) {
        HydraulicPlan.Route route = candidate.route;
        if (!involvesWorld(route)) return null;
        if (candidate.amountHint < WORLD_BLOCK_MB) return HydraulicPlan.RejectReason.WORLD_BUCKET_REQUIRED;
        if (route.source().type() == HydraulicPlan.PortType.WORLD && route.source().amountMb() < WORLD_BLOCK_MB) return HydraulicPlan.RejectReason.WORLD_SOURCE_BELOW_BUCKET;
        if (route.sink().type() == HydraulicPlan.PortType.WORLD && route.source().amountMb() < WORLD_BLOCK_MB) return HydraulicPlan.RejectReason.WORLD_OUTPUT_SOURCE_BELOW_BUCKET;
        if (route.sink().type() == HydraulicPlan.PortType.WORLD && route.sink().capacityMb() - route.sink().amountMb() < WORLD_BLOCK_MB) return HydraulicPlan.RejectReason.WORLD_OUTPUT_NOT_EMPTY;
        return null;
    }

    private static boolean involvesWorld(HydraulicPlan.Route route) {
        return route.source().type() == HydraulicPlan.PortType.WORLD || route.sink().type() == HydraulicPlan.PortType.WORLD;
    }

    private static boolean compatible(HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        return sink.fluid().equals("empty") || source.fluid().equals(sink.fluid());
    }

    private static HydraulicPlan.ActionType actionType(HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        if (source.type() == HydraulicPlan.PortType.WORLD && sink.type() == HydraulicPlan.PortType.WORLD) return HydraulicPlan.ActionType.WORLD_TO_WORLD;
        if (source.type() == HydraulicPlan.PortType.WORLD) return HydraulicPlan.ActionType.WORLD_TO_TANK;
        if (sink.type() == HydraulicPlan.PortType.WORLD) return HydraulicPlan.ActionType.TANK_TO_WORLD;
        return HydraulicPlan.ActionType.TANK_TO_TANK;
    }

    private static String routeKey(HydraulicPlan.ActionType action, HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        return action + ":" + source.id() + "->" + sink.id();
    }

    private static int routeLength(HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        if (source.pipe() == null || sink.pipe() == null) return 1;
        return Math.max(1, source.pipe().distManhattan(sink.pipe()));
    }

    private static int routeBends(HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        if (source.pipe() == null || sink.pipe() == null) return 0;
        int axes = 0;
        if (source.pipe().getX() != sink.pipe().getX()) axes++;
        if (source.pipe().getY() != sink.pipe().getY()) axes++;
        if (source.pipe().getZ() != sink.pipe().getZ()) axes++;
        return Math.max(0, axes - 1);
    }

    private static double routeResistance(int routeLength, int bends) {
        return BASE_ROUTE_RESISTANCE + routeLength * PIPE_RESISTANCE + bends * BEND_RESISTANCE;
    }

    private static int flowEstimate(double deltaHead, double resistance) {
        if (deltaHead <= HEAD_DEAD_BAND) return 0;
        return Math.max(1, Math.min(MAX_FLOW_MB, (int) Math.round(FLOW_SCALE * Math.sqrt(deltaHead) / Math.max(0.01, resistance))));
    }

    private static int countLeased(List<Candidate> candidates) {
        int count = 0;
        for (Candidate candidate : candidates) if (candidate.route.leased()) count++;
        return count;
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

        HydraulicPlan.Port build() {
            FluidStack stack = tank.getTankInventory().getFluid();
            String fluid = stack.isEmpty() ? "empty" : String.valueOf(stack.getFluid());
            return new HydraulicPlan.Port(
                    "tank:" + tank.getController().toShortString(),
                    HydraulicPlan.PortType.TANK,
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

        boolean reserve(HydraulicPlan.Route route) {
            String sourceKey = sourceKey(route.source());
            String sinkKey = sinkKey(route.sink());
            String worldSourceKey = route.source().type() == HydraulicPlan.PortType.WORLD ? worldKey(route.source()) : null;
            String worldSinkKey = route.sink().type() == HydraulicPlan.PortType.WORLD ? worldKey(route.sink()) : null;

            if (sourceKeys.contains(sourceKey) || sinkKeys.contains(sinkKey)) return false;
            if (worldSourceKey != null && worldKeys.contains(worldSourceKey)) return false;
            if (worldSinkKey != null && worldKeys.contains(worldSinkKey)) return false;

            sourceKeys.add(sourceKey);
            sinkKeys.add(sinkKey);
            if (worldSourceKey != null) worldKeys.add(worldSourceKey);
            if (worldSinkKey != null) worldKeys.add(worldSinkKey);
            return true;
        }

        private static String sourceKey(HydraulicPlan.Port port) {
            return port.type() + ":source:" + port.owner();
        }

        private static String sinkKey(HydraulicPlan.Port port) {
            return port.type() + ":sink:" + port.owner();
        }

        private static String worldKey(HydraulicPlan.Port port) {
            return "WORLD:" + port.owner();
        }
    }

    public record BuildResult(HydraulicPlan plan, Set<BlockPos> pipes, int candidateCount, int leasedCandidateCount, int pumpCount) {}
    private record Snapshot(Set<BlockPos> pipes, List<HydraulicPlan.Port> ports, Set<BlockPos> pumps) {}
    private record Node(BlockPos pos, int distance) {}
    private record Candidate(HydraulicPlan.ActionType type, HydraulicPlan.Route route, String routeKey, int amountHint) {}
    private record RejectedCandidate(Candidate candidate, HydraulicPlan.RejectReason reason) {}
    private record Selection(List<Candidate> selected, List<RejectedCandidate> rejected) {}
}
