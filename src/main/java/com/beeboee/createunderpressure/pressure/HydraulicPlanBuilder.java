package com.beeboee.createunderpressure.pressure;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Scans Create's real pipe topology and produces a contact-aware hydraulic plan.
 * Head determines reachability and direction; throughput is a separate 128 mB/t
 * budget that HydraulicRuntime projects into Create's own fluid network.
 */
public final class HydraulicPlanBuilder {
    private HydraulicPlanBuilder() {}

    public static final int MAX_SCAN_DISTANCE = 128;
    public static final int MAX_SELECTED_ACTIONS = 4;
    public static final int MAX_WORLD_ACTIONS = MAX_SELECTED_ACTIONS;
    public static final int WORLD_BLOCK_MB = 1000;
    public static final int FLOW_RATE_MB_PER_TICK = 128;
    public static final int MAX_FLOW_MB = FLOW_RATE_MB_PER_TICK;
    public static final double HEAD_DEAD_BAND = 0.01;

    // Compatibility/debug constants; route resistance no longer controls flow.
    public static final double FLOW_SCALE = FLOW_RATE_MB_PER_TICK;
    public static final double BASE_ROUTE_RESISTANCE = 1.0;
    public static final double PIPE_RESISTANCE = 0.0;
    public static final double BEND_RESISTANCE = 0.0;

    private static final double EPSILON = 0.001;
    private static final int MAX_STATES_PER_NODE = 8;

    public static BuildResult build(Level level, BlockPos seed, Set<String> leasedRouteKeys) {
        Snapshot snapshot = scan(level, seed);
        if (snapshot.pipes.isEmpty()) {
            return new BuildResult(new HydraulicPlan(seed, 0, List.of(), List.of(), List.of()), Set.of(), 0, 0, 0);
        }

        BlockPos owner = ownerPipe(snapshot.pipes);
        List<Candidate> candidates = candidates(level, snapshot, leasedRouteKeys == null ? Set.of() : leasedRouteKeys);
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

        List<HydraulicPlan.Port> ports = snapshot.portStates.stream().map(PortState::port).toList();
        HydraulicPlan plan = new HydraulicPlan(owner, snapshot.pipes.size(), ports, actions, rejected);
        return new BuildResult(plan, Set.copyOf(snapshot.pipes), candidates.size(), countLeased(candidates), snapshot.pumps.size());
    }

    private static Snapshot scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Map<BlockPos, List<Direction>> facesByPipe = new HashMap<>();
        List<PortState> ports = new ArrayList<>();
        Set<String> portKeys = new HashSet<>();
        Set<BlockPos> pumps = new HashSet<>();
        Set<BlockPos> expandedTanks = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(seed, 0));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_SCAN_DISTANCE || !level.isLoaded(node.pos) || pipes.contains(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;

            BlockPos pipePos = node.pos.immutable();
            pipes.add(pipePos);
            if (PumpHeadPressure.isPump(level, pipePos)) pumps.add(pipePos);

            List<Direction> faces = new ArrayList<>(FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe));
            faces.sort(Comparator.comparingInt(Direction::ordinal));
            facesByPipe.put(pipePos, List.copyOf(faces));

            for (Direction face : faces) {
                BlockPos other = pipePos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                String contactKey = pipePos + "|" + face;
                if (!portKeys.add(contactKey)) continue;

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    ports.add(tankPort(pipePos, face, tank));
                    if (expandedTanks.add(tank.getController())) {
                        queueTankPipes(level, tank, queue, node.distance + 1);
                    }
                    continue;
                }

                IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, other, face.getOpposite());
                if (handler != null) {
                    ports.add(genericPort(pipePos, face, other, handler));
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, pipePos, face)) ports.add(worldPort(level, pipePos, face));
            }
        }

        Graph graph = buildGraph(level, pipes, facesByPipe, pumps);
        ports.sort(Comparator.comparing(state -> state.port.id()));
        return new Snapshot(pipes, facesByPipe, ports, pumps, graph);
    }

    /**
     * Tanks join separate pipe components into one planning domain, but are not
     * represented as zero-cost pipe edges. Create must actually fill the tank and
     * later drain another physical contact; fluid never teleports through storage.
     */
    private static void queueTankPipes(Level level, FluidTankBlockEntity tank, ArrayDeque<Node> queue, int distance) {
        BlockPos base = tank.getController();
        for (int x = 0; x < tank.getWidth(); x++) {
            for (int y = 0; y < tank.getHeight(); y++) {
                for (int z = 0; z < tank.getWidth(); z++) {
                    BlockPos tankBlock = base.offset(x, y, z);
                    for (Direction direction : Direction.values()) {
                        BlockPos pipePos = tankBlock.relative(direction);
                        if (level.isLoaded(pipePos) && FluidPropagator.getPipe(level, pipePos) != null) {
                            queue.add(new Node(pipePos, distance));
                        }
                    }
                }
            }
        }
    }

    private static Graph buildGraph(Level level, Set<BlockPos> pipes,
                                    Map<BlockPos, List<Direction>> facesByPipe, Set<BlockPos> pumps) {
        Map<FaceNode, List<Edge>> edges = new HashMap<>();

        for (BlockPos pipePos : pipes) {
            List<Direction> faces = facesByPipe.getOrDefault(pipePos, List.of());
            for (Direction face : faces) edges.computeIfAbsent(new FaceNode(pipePos, face), $ -> new ArrayList<>());

            if (pumps.contains(pipePos)) {
                Direction input = PumpHeadPressure.inputSide(level, pipePos);
                Direction output = PumpHeadPressure.outputSide(level, pipePos);
                if (input != null && output != null && faces.contains(input) && faces.contains(output)) {
                    int boost = PumpHeadPressure.boost(level, pipePos);
                    addEdge(edges, new Edge(new FaceNode(pipePos, input), new FaceNode(pipePos, output), boost, pipePos, false));
                    addEdge(edges, new Edge(new FaceNode(pipePos, output), new FaceNode(pipePos, input), 0, pipePos, false));
                }
            } else {
                for (Direction from : faces) {
                    for (Direction to : faces) {
                        if (from != to) addEdge(edges, new Edge(new FaceNode(pipePos, from), new FaceNode(pipePos, to), 0, null, false));
                    }
                }
            }
        }

        for (BlockPos pipePos : pipes) {
            for (Direction face : facesByPipe.getOrDefault(pipePos, List.of())) {
                BlockPos other = pipePos.relative(face);
                if (!pipes.contains(other)) continue;
                Direction opposite = face.getOpposite();
                if (!facesByPipe.getOrDefault(other, List.of()).contains(opposite)) continue;
                addEdge(edges, new Edge(new FaceNode(pipePos, face), new FaceNode(other, opposite), 0, null, true));
            }
        }

        for (List<Edge> list : edges.values()) {
            list.sort(Comparator
                    .comparing((Edge edge) -> edge.to.pipe, HydraulicPlanBuilder::compareBlockPos)
                    .thenComparingInt(edge -> edge.to.face.ordinal())
                    .thenComparingInt(edge -> -edge.headGain));
        }
        return new Graph(edges);
    }

    private static void addEdge(Map<FaceNode, List<Edge>> edges, Edge edge) {
        edges.computeIfAbsent(edge.from, $ -> new ArrayList<>()).add(edge);
    }

    private static PortState tankPort(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        int stored = tank.getTankInventory().getFluidAmount();
        int capacity = tank.getTankInventory().getCapacity();
        double surface = tankSurface(tank, stored);
        double cutoff = tankCutoff(pipe, face, tank);
        int floor = amountForSurface(tank, cutoff);
        int reachable = Math.max(0, stored - floor);
        FluidStack stack = tank.getTankInventory().getFluid().copy();

        HydraulicPlan.Port port = new HydraulicPlan.Port(
                "tank:" + tank.getController().toShortString() + ":" + pipe.toShortString() + ":" + face.getName(),
                HydraulicPlan.PortType.TANK,
                tank.getController(), pipe, face, surface, reachable, capacity,
                fluidName(stack), 1, cutoff, stored);
        return new PortState(port, stack, tank.getTankInventory());
    }

    private static PortState genericPort(BlockPos pipe, Direction face, BlockPos owner, IFluidHandler handler) {
        int stored = 0;
        int capacity = 0;
        FluidStack contained = FluidStack.EMPTY;
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack stack = handler.getFluidInTank(i);
            stored += Math.max(0, stack.getAmount());
            capacity += Math.max(0, handler.getTankCapacity(i));
            if (contained.isEmpty() && !stack.isEmpty()) contained = stack.copy();
        }

        FluidStack drainable = simulateDrain(handler, WORLD_BLOCK_MB);
        if (contained.isEmpty()) contained = drainable.copy();
        double contact = faceElevation(pipe, face);
        HydraulicPlan.Port port = new HydraulicPlan.Port(
                "handler:" + owner.toShortString() + ":" + pipe.toShortString() + ":" + face.getName(),
                HydraulicPlan.PortType.GENERIC_HANDLER,
                owner, pipe, face, contact, drainable.getAmount(), Math.max(capacity, stored),
                fluidName(contained), 1, contact, stored);
        return new PortState(port, contained, handler);
    }

    private static PortState worldPort(Level level, BlockPos pipe, Direction face) {
        BlockPos worldPos = pipe.relative(face);
        FluidState state = level.getFluidState(worldPos);
        Fluid fluid = state.isEmpty() ? Fluids.EMPTY : FluidHelper.convertToStill(state.getType());
        FluidStack stack = fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, WORLD_BLOCK_MB);
        int stored = stack.isEmpty() ? 0 : WORLD_BLOCK_MB;
        double cutoff = faceElevation(pipe, face);
        double sourceHead = stack.isEmpty() ? cutoff : worldPos.getY() + 1.0;
        HydraulicPlan.Port port = new HydraulicPlan.Port(
                "world:" + pipe.toShortString() + ":" + face.getName(),
                HydraulicPlan.PortType.WORLD,
                worldPos, pipe, face, sourceHead, stored, WORLD_BLOCK_MB,
                fluidName(stack), 1, cutoff, stored);
        return new PortState(port, stack, null);
    }

    private static List<Candidate> candidates(Level level, Snapshot snapshot, Set<String> leasedRouteKeys) {
        List<Candidate> routes = new ArrayList<>();

        for (PortState sourceState : snapshot.portStates) {
            HydraulicPlan.Port source = sourceState.port;
            if (source.amountMb() <= 0 || sourceState.fluid.isEmpty()) continue;

            for (PortState sinkState : snapshot.portStates) {
                HydraulicPlan.Port sink = sinkState.port;
                if (source.id().equals(sink.id()) || source.owner().equals(sink.owner())) continue;
                if (!compatible(sourceState, sinkState)) continue;

                int sinkRoom = sinkRoom(level, sinkState, sourceState.fluid);
                if (sinkRoom <= 0) continue;

                double requiredHead = requiredSinkHead(sink);
                PathResult path = findPath(snapshot.graph, source, sink, requiredHead);
                if (path == null) continue;

                double deltaHead = path.deliveredHead - requiredHead;
                if (deltaHead <= HEAD_DEAD_BAND) continue;

                int amount = Math.min(FLOW_RATE_MB_PER_TICK, Math.min(source.amountMb(), sinkRoom));
                if (amount <= 0) continue;

                HydraulicPlan.ActionType type = actionType(source, sink);
                String routeKey = routeKey(type, source, sink);
                boolean leased = leasedRouteKeys.contains(routeKey);
                HydraulicPlan.Route route = new HydraulicPlan.Route(
                        source, sink, deltaHead, path.length, path.bends, 1.0, amount, leased,
                        path.deliveredHead, path.pumpBoost, path.connections);
                routes.add(new Candidate(type, route, routeKey, amount));
            }
        }

        Comparator<Candidate> byDeltaDescending = Comparator.comparingDouble(
                (Candidate candidate) -> candidate.route.deltaHead()).reversed();
        routes.sort(Comparator
                .comparing((Candidate candidate) -> candidate.route.leased()).reversed()
                .thenComparingInt(HydraulicPlanBuilder::actionPriority)
                .thenComparingDouble(candidate -> requiredSinkHead(candidate.route.sink()))
                .thenComparing(byDeltaDescending)
                .thenComparingInt(candidate -> candidate.route.routeLength())
                .thenComparing(candidate -> candidate.route.source().id())
                .thenComparing(candidate -> candidate.route.sink().id()));
        return routes;
    }

    private static PathResult findPath(Graph graph, HydraulicPlan.Port source,
                                       HydraulicPlan.Port sink, double requiredHead) {
        FaceNode start = new FaceNode(source.pipe(), source.face());
        FaceNode target = new FaceNode(sink.pipe(), sink.face());
        if (!graph.edges.containsKey(start) || !graph.edges.containsKey(target)) return null;

        Comparator<SearchState> order = Comparator
                .comparingInt((SearchState state) -> state.steps)
                .thenComparingDouble(state -> -state.head)
                .thenComparing(state -> state.node.pipe, HydraulicPlanBuilder::compareBlockPos)
                .thenComparingInt(state -> state.node.face.ordinal());
        PriorityQueue<SearchState> frontier = new PriorityQueue<>(order);
        Map<FaceNode, List<SearchState>> best = new HashMap<>();
        SearchState initial = new SearchState(start, source.head(), 0, 0, 0, null, null, Set.of());
        frontier.add(initial);
        best.computeIfAbsent(start, $ -> new ArrayList<>()).add(initial);

        while (!frontier.isEmpty()) {
            SearchState current = frontier.poll();
            if (current.node.equals(target) && current.head > requiredHead + HEAD_DEAD_BAND) return pathResult(current);

            for (Edge edge : graph.edges.getOrDefault(current.node, List.of())) {
                Set<BlockPos> usedPumps = current.usedPumps;
                if (edge.pump != null && edge.headGain > 0) {
                    if (usedPumps.contains(edge.pump)) continue;
                    Set<BlockPos> copy = new HashSet<>(usedPumps);
                    copy.add(edge.pump);
                    usedPumps = Set.copyOf(copy);
                }

                double nextHead = current.head + edge.headGain;
                if (faceElevation(edge.to.pipe, edge.to.face) > nextHead + EPSILON) continue;

                int nextSteps = current.steps + 1;
                int nextLength = current.length + (edge.external ? 1 : 0);
                int nextBends = current.bends + bendCost(edge);
                SearchState next = new SearchState(edge.to, nextHead, nextSteps, nextLength, nextBends,
                        current, edge, usedPumps);
                List<SearchState> states = best.computeIfAbsent(edge.to, $ -> new ArrayList<>());
                if (isDominated(states, next)) continue;
                states.removeIf(existing -> dominates(next, existing));
                states.add(next);
                states.sort(order);
                if (states.size() > MAX_STATES_PER_NODE) states.subList(MAX_STATES_PER_NODE, states.size()).clear();
                if (states.contains(next)) frontier.add(next);
            }
        }
        return null;
    }

    private static boolean isDominated(List<SearchState> states, SearchState candidate) {
        for (SearchState existing : states) if (dominates(existing, candidate)) return true;
        return false;
    }

    private static boolean dominates(SearchState a, SearchState b) {
        return a.steps <= b.steps && a.head + EPSILON >= b.head && a.usedPumps.size() <= b.usedPumps.size();
    }

    private static int bendCost(Edge edge) {
        if (edge.external || !edge.from.pipe.equals(edge.to.pipe)) return 0;
        return edge.from.face.getOpposite() == edge.to.face ? 0 : 1;
    }

    private static PathResult pathResult(SearchState end) {
        List<FaceNode> nodes = new ArrayList<>();
        SearchState cursor = end;
        while (cursor != null) {
            nodes.add(cursor.node);
            cursor = cursor.previous;
        }
        java.util.Collections.reverse(nodes);

        LinkedHashMap<String, HydraulicPlan.ConnectionUse> uses = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            FaceNode node = nodes.get(i);
            boolean inbound;
            if (i == 0) inbound = true;
            else if (i == nodes.size() - 1) inbound = false;
            else {
                FaceNode previous = nodes.get(i - 1);
                FaceNode next = nodes.get(i + 1);
                inbound = !previous.pipe.equals(node.pipe) && next.pipe.equals(node.pipe);
                if (previous.pipe.equals(node.pipe) && !next.pipe.equals(node.pipe)) inbound = false;
            }
            uses.putIfAbsent(node.pipe + "|" + node.face + "|" + inbound,
                    new HydraulicPlan.ConnectionUse(node.pipe, node.face, inbound));
        }

        int boost = 0;
        cursor = end;
        while (cursor != null && cursor.via != null) {
            boost += cursor.via.headGain;
            cursor = cursor.previous;
        }
        return new PathResult(end.head, boost, end.length, end.bends, List.copyOf(uses.values()));
    }

    private static int sinkRoom(Level level, PortState sink, FluidStack fluid) {
        if (sink.port.type() == HydraulicPlan.PortType.WORLD) return worldSinkRoom(level, sink.port.owner(), fluid);
        if (sink.handler == null) return 0;
        FluidStack probe = fluid.copy();
        probe.setAmount(FLOW_RATE_MB_PER_TICK);
        return sink.handler.fill(probe, FluidAction.SIMULATE);
    }

    private static int worldSinkRoom(Level level, BlockPos pos, FluidStack fluid) {
        BlockState block = level.getBlockState(pos);
        FluidState state = block.getFluidState();
        if (!state.isEmpty()) {
            Fluid existing = FluidHelper.convertToStill(state.getType());
            return existing.isSame(fluid.getFluid()) ? WORLD_BLOCK_MB : 0;
        }
        if (block.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return fluid.getFluid().isSame(Fluids.WATER) && !block.getValue(BlockStateProperties.WATERLOGGED)
                    ? WORLD_BLOCK_MB : 0;
        }
        return block.canBeReplaced() || !block.blocksMotion() ? WORLD_BLOCK_MB : 0;
    }

    private static boolean compatible(PortState source, PortState sink) {
        return sink.fluid.isEmpty() || FluidStack.isSameFluidSameComponents(source.fluid, sink.fluid);
    }

    private static FluidStack simulateDrain(IFluidHandler handler, int amount) {
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack contained = handler.getFluidInTank(i);
            if (contained.isEmpty()) continue;
            FluidStack request = contained.copy();
            request.setAmount(Math.min(amount, contained.getAmount()));
            FluidStack drained = handler.drain(request, FluidAction.SIMULATE);
            if (!drained.isEmpty()) return drained;
        }
        return handler.drain(amount, FluidAction.SIMULATE);
    }

    private static Selection select(List<Candidate> candidates) {
        ReservationSet reservations = new ReservationSet();
        List<Candidate> selected = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();

        for (Candidate candidate : candidates) {
            if (selected.size() >= MAX_SELECTED_ACTIONS) {
                rejected.add(new RejectedCandidate(candidate, HydraulicPlan.RejectReason.ACTION_LIMIT));
                continue;
            }
            HydraulicPlan.RejectReason conflict = reservations.reserve(candidate);
            if (conflict != null) {
                rejected.add(new RejectedCandidate(candidate, conflict));
                continue;
            }
            selected.add(candidate);
        }
        return new Selection(selected, rejected);
    }

    private static int actionPriority(Candidate candidate) {
        return switch (candidate.type) {
            case WORLD_TO_TANK -> 0;
            case TANK_TO_WORLD -> 1;
            case WORLD_TO_WORLD -> 2;
            case GENERIC_TRANSFER -> 3;
            case TANK_TO_TANK -> 4;
        };
    }

    private static HydraulicPlan.ActionType actionType(HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        if (source.type() == HydraulicPlan.PortType.WORLD && sink.type() == HydraulicPlan.PortType.WORLD) return HydraulicPlan.ActionType.WORLD_TO_WORLD;
        if (source.type() == HydraulicPlan.PortType.WORLD && sink.type() == HydraulicPlan.PortType.TANK) return HydraulicPlan.ActionType.WORLD_TO_TANK;
        if (source.type() == HydraulicPlan.PortType.TANK && sink.type() == HydraulicPlan.PortType.WORLD) return HydraulicPlan.ActionType.TANK_TO_WORLD;
        if (source.type() == HydraulicPlan.PortType.TANK && sink.type() == HydraulicPlan.PortType.TANK) return HydraulicPlan.ActionType.TANK_TO_TANK;
        return HydraulicPlan.ActionType.GENERIC_TRANSFER;
    }

    private static double requiredSinkHead(HydraulicPlan.Port sink) {
        return sink.type() == HydraulicPlan.PortType.TANK ? Math.max(sink.cutoffHead(), sink.head()) : sink.cutoffHead();
    }

    private static String routeKey(HydraulicPlan.ActionType action, HydraulicPlan.Port source, HydraulicPlan.Port sink) {
        return action + ":" + source.id() + "->" + sink.id();
    }

    private static int countLeased(List<Candidate> candidates) {
        int count = 0;
        for (Candidate candidate : candidates) if (candidate.route.leased()) count++;
        return count;
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static double tankSurface(FluidTankBlockEntity tank, int amount) {
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + amount / layerCapacity(tank);
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        double height = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(tank.getTankInventory().getCapacity(),
                (int) Math.round(height * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / Math.max(1, tank.getHeight());
    }

    private static double tankCutoff(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
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

    private static double faceElevation(BlockPos pipe, Direction face) {
        return pipe.getY() + (face == Direction.UP ? 1.0 : 0.0);
    }

    private static String fluidName(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        Fluid still = FluidHelper.convertToStill(stack.getFluid());
        return BuiltInRegistries.FLUID.getKey(still).toString();
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

    private static final class ReservationSet {
        private final Set<String> sources = new HashSet<>();
        private final Set<String> sinks = new HashSet<>();
        private final Set<String> routeFaces = new HashSet<>();

        HydraulicPlan.RejectReason reserve(Candidate candidate) {
            String source = candidate.route.source().type() + ":" + candidate.route.source().owner();
            String sink = candidate.route.sink().type() + ":" + candidate.route.sink().owner();
            if (sources.contains(source) || sinks.contains(sink)) return HydraulicPlan.RejectReason.RESERVED_PORT;

            for (HydraulicPlan.ConnectionUse use : candidate.route.connections()) {
                String face = use.pipe() + "|" + use.face();
                if (routeFaces.contains(face)) return HydraulicPlan.RejectReason.RESERVED_ROUTE;
            }

            sources.add(source);
            sinks.add(sink);
            for (HydraulicPlan.ConnectionUse use : candidate.route.connections()) {
                routeFaces.add(use.pipe() + "|" + use.face());
            }
            return null;
        }
    }

    public record BuildResult(HydraulicPlan plan, Set<BlockPos> pipes,
                              int candidateCount, int leasedCandidateCount, int pumpCount) {}
    private record Snapshot(Set<BlockPos> pipes, Map<BlockPos, List<Direction>> facesByPipe,
                            List<PortState> portStates, Set<BlockPos> pumps, Graph graph) {}
    private record PortState(HydraulicPlan.Port port, FluidStack fluid, IFluidHandler handler) {}
    private record Graph(Map<FaceNode, List<Edge>> edges) {}
    private record FaceNode(BlockPos pipe, Direction face) {}
    private record Edge(FaceNode from, FaceNode to, int headGain, BlockPos pump, boolean external) {}
    private record Node(BlockPos pos, int distance) {}
    private record Candidate(HydraulicPlan.ActionType type, HydraulicPlan.Route route,
                             String routeKey, int amountHint) {}
    private record RejectedCandidate(Candidate candidate, HydraulicPlan.RejectReason reason) {}
    private record Selection(List<Candidate> selected, List<RejectedCandidate> rejected) {}
    private record PathResult(double deliveredHead, int pumpBoost, int length, int bends,
                              List<HydraulicPlan.ConnectionUse> connections) {}

    private static final class SearchState {
        final FaceNode node;
        final double head;
        final int steps;
        final int length;
        final int bends;
        final SearchState previous;
        final Edge via;
        final Set<BlockPos> usedPumps;

        SearchState(FaceNode node, double head, int steps, int length, int bends,
                    SearchState previous, Edge via, Set<BlockPos> usedPumps) {
            this.node = node;
            this.head = head;
            this.steps = steps;
            this.length = length;
            this.bends = bends;
            this.previous = previous;
            this.via = via;
            this.usedPumps = usedPumps;
        }
    }
}
