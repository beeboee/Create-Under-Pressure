package com.beeboee.createunderpressure.visual;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.beeboee.createunderpressure.pressure.CreateWorldEndIO;
import com.beeboee.createunderpressure.pressure.HydraulicPlanRuntime;
import com.beeboee.createunderpressure.pressure.NetworkPressurePlanner;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Cosmetic-only world exchange effects for open pipe ends.
 *
 * This layer follows shared hydraulic plans, legacy planner visuals, and shared
 * simulation events, then delegates particles and Create pipe flow/fullness
 * hints to Create's helpers.
 */
public final class WorldExchangeVisualLayer {
    private WorldExchangeVisualLayer() {}

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_SCAN_DISTANCE = 48;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int LINGER_TICKS = 16;
    private static final float CREATE_FLOW_PRESSURE = 96.0f;
    private static final float PASSIVE_FULLNESS_PRESSURE = 16.0f;
    private static final double EPSILON = 0.01;

    private static final Map<Level, Map<VisualKey, LingeringVisual>> LINGERING_VISUALS = new WeakHashMap<>();

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        VisualScan scan = scan(level, seed);
        if (scan.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(scan.pipes);
        if (!seed.equals(owner)) return;

        WorldExchangeVisualEvents.prune(level);
        logAndSpawnPlannedVisuals(level, scan, owner);
    }

    private static void logAndSpawnPlannedVisuals(Level level, VisualScan scan, BlockPos owner) {
        boolean debug = DebugInfo.isEnabled(level);
        if (debug) DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int livePlanned = 0;
            int eventPlanned = 0;
            int sharedPlanPlanned = 0;
            int lingered = 0;
            int skipped = 0;
            FluidStack networkVisualFluid = networkVisualFluid(level, scan);
            List<PlannedEnd> plannedEnds = new ArrayList<>();
            Set<VisualKey> activeFaces = new HashSet<>();

            for (OpenEnd end : scan.openEnds) {
                NetworkPressurePlanner.PlannedVisual action = HydraulicPlanRuntime.visualFor(level, end.pipe, end.face);
                String visualSource = "sharedPlan";
                boolean live = action != null;
                if (live) sharedPlanPlanned++;

                if (action == null) {
                    action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
                    visualSource = "planner";
                    live = action != null;
                    if (live) livePlanned++;
                }

                WorldExchangeVisualEvents.VisualEvent event = WorldExchangeVisualEvents.visualFor(level, end.pipe, end.face);
                FluidStack eventFluid = event == null ? FluidStack.EMPTY : event.fluid();

                if (action != null) {
                    rememberVisual(level, end, action);
                } else if (event != null) {
                    action = toPlannerVisual(event.action());
                    visualSource = "event";
                    eventPlanned++;
                } else {
                    action = lingeringVisual(level, end);
                    visualSource = "linger";
                    if (action != null) lingered++;
                }

                BlockPos worldPos = end.worldPos();
                if (action == null) {
                    skipped++;
                    if (debug) DebugInfo.log(level, "VISUAL skip pipe={} face={} pos={} reason=noPlannerAction", end.pipe, end.face, worldPos);
                    continue;
                }

                activeFaces.add(new VisualKey(end.pipe, end.face));
                plannedEnds.add(new PlannedEnd(end, action));
                FluidStack visualFluid = visualFluid(level, end, action, networkVisualFluid, eventFluid);
                boolean splashOnRim = action == NetworkPressurePlanner.PlannedVisual.INTAKE;
                CreateWorldEndIO.spawnCreateParticles(level, end.pipe, end.face, visualFluid, splashOnRim);
                applyCreateFlowHint(level, end, action);

                if (debug) {
                    FluidState fluidState = level.getFluidState(worldPos);
                    DebugInfo.log(level, "VISUAL planned pipe={} face={} pos={} action={} live={} visualSource={} fluid={} source={} visualFluid={} networkVisualFluid={} flowHint=true",
                            end.pipe, end.face, worldPos, action, live, visualSource, fluidState.getType(), fluidState.isSource(), visualFluid, networkVisualFluid);
                }
            }

            int routeHints = applyRouteFlowHints(level, scan, plannedEnds, activeFaces);
            int passiveHints = applyPassiveFullnessHints(level, scan, activeFaces);
            if (debug) DebugInfo.log(level, "VISUAL scan owner={} openEnds={} tankContacts={} sharedPlanPlanned={} livePlanned={} eventPlanned={} lingered={} skipped={} routeHints={} passiveHints={} networkVisualFluid={} source=SharedPlan/Planner/Event/CreatePipeConnection",
                    owner, scan.openEnds.size(), scan.tankContacts.size(), sharedPlanPlanned, livePlanned, eventPlanned, lingered, skipped, routeHints, passiveHints, networkVisualFluid);
        } finally {
            if (debug) DebugInfo.endNetwork();
        }
    }

    private static NetworkPressurePlanner.PlannedVisual toPlannerVisual(WorldExchangeVisualEvents.Action action) {
        return action == WorldExchangeVisualEvents.Action.INTAKE
                ? NetworkPressurePlanner.PlannedVisual.INTAKE
                : NetworkPressurePlanner.PlannedVisual.OUTPUT;
    }

    private static void rememberVisual(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action) {
        LINGERING_VISUALS
                .computeIfAbsent(level, $ -> new HashMap<>())
                .put(new VisualKey(end.pipe, end.face), new LingeringVisual(action, level.getGameTime() + LINGER_TICKS));
    }

    private static NetworkPressurePlanner.PlannedVisual lingeringVisual(Level level, OpenEnd end) {
        Map<VisualKey, LingeringVisual> map = LINGERING_VISUALS.get(level);
        if (map == null) return null;
        VisualKey key = new VisualKey(end.pipe, end.face);
        LingeringVisual visual = map.get(key);
        if (visual == null) return null;
        if (level.getGameTime() <= visual.untilGameTime) return visual.action;
        map.remove(key);
        return null;
    }

    private static int applyPassiveFullnessHints(Level level, VisualScan scan, Set<VisualKey> activeFaces) {
        int applied = 0;

        for (OpenEnd end : scan.openEnds) {
            VisualKey key = new VisualKey(end.pipe, end.face);
            if (activeFaces.contains(key)) continue;
            FluidState fluidState = level.getFluidState(end.worldPos());
            if (fluidState.isEmpty()) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, end.pipe);
            CreatePipeFlowVisualBridge.apply(level, pipe, end.pipe, end.face, true, PASSIVE_FULLNESS_PRESSURE);
            activeFaces.add(key);
            applied++;
            DebugInfo.log(level, "VISUAL passiveFullness pipe={} face={} pos={} reason=openEndTouchesFluid fluid={} source={}",
                    end.pipe, end.face, end.worldPos(), fluidState.getType(), fluidState.isSource());
        }

        for (TankContact contact : scan.tankContacts) {
            VisualKey key = new VisualKey(contact.pipe, contact.face);
            if (activeFaces.contains(key)) continue;
            if (!tankCoversContact(contact)) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, contact.pipe);
            CreatePipeFlowVisualBridge.apply(level, pipe, contact.pipe, contact.face, true, PASSIVE_FULLNESS_PRESSURE);
            activeFaces.add(key);
            applied++;
            DebugInfo.log(level, "VISUAL passiveFullness pipe={} face={} tank={} reason=tankContactBelowSurface surface={} cutoff={}",
                    contact.pipe, contact.face, contact.tank.getController(), tankSurface(contact.tank), contact.cutoffSurface());
        }

        return applied;
    }

    private static int applyRouteFlowHints(Level level, VisualScan scan, List<PlannedEnd> plannedEnds, Set<VisualKey> activeFaces) {
        List<PlannedEnd> intakes = new ArrayList<>();
        List<PlannedEnd> outputs = new ArrayList<>();
        for (PlannedEnd planned : plannedEnds) {
            if (planned.action == NetworkPressurePlanner.PlannedVisual.INTAKE) intakes.add(planned);
            else if (planned.action == NetworkPressurePlanner.PlannedVisual.OUTPUT) outputs.add(planned);
        }

        int applied = 0;
        for (PlannedEnd intake : intakes) {
            for (PlannedEnd output : outputs) {
                List<Step> path = pipePath(level, scan, intake.end.pipe, output.end.pipe);
                if (path == null) continue;
                applyPathFlowHints(level, intake.end, output.end, path, activeFaces);
                applied++;
                DebugInfo.log(level, "VISUAL routeHint intake={} output={} path={}", intake.end.worldPos(), output.end.worldPos(), path.size());
            }
        }
        return applied;
    }

    private static void applyPathFlowHints(Level level, OpenEnd intake, OpenEnd output, List<Step> path, Set<VisualKey> activeFaces) {
        applyCreateFlowHint(level, intake, NetworkPressurePlanner.PlannedVisual.INTAKE);
        activeFaces.add(new VisualKey(intake.pipe, intake.face));
        for (Step step : path) {
            FluidTransportBehaviour fromPipe = FluidPropagator.getPipe(level, step.from);
            FluidTransportBehaviour toPipe = FluidPropagator.getPipe(level, step.to);
            CreatePipeFlowVisualBridge.apply(level, fromPipe, step.from, step.face, false, CREATE_FLOW_PRESSURE);
            CreatePipeFlowVisualBridge.apply(level, toPipe, step.to, step.face.getOpposite(), true, CREATE_FLOW_PRESSURE);
            activeFaces.add(new VisualKey(step.from, step.face));
            activeFaces.add(new VisualKey(step.to, step.face.getOpposite()));
        }
        applyCreateFlowHint(level, output, NetworkPressurePlanner.PlannedVisual.OUTPUT);
        activeFaces.add(new VisualKey(output.pipe, output.face));
    }

    private static List<Step> pipePath(Level level, VisualScan scan, BlockPos start, BlockPos target) {
        if (start.equals(target)) return List.of();
        Map<BlockPos, Step> from = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pipePos = queue.removeFirst();
            if (pipePos.equals(target)) break;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) continue;

            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                BlockPos next = pipePos.relative(face);
                if (!scan.pipes.contains(next) || !visited.add(next)) continue;
                from.put(next, new Step(pipePos, face, next));
                queue.add(next);
            }
        }

        if (!from.containsKey(target)) return null;
        List<Step> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(start)) {
            Step step = from.get(cursor);
            if (step == null) return null;
            reversed.add(step);
            cursor = step.from;
        }

        List<Step> path = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) path.add(reversed.get(i));
        return path;
    }

    private static void applyCreateFlowHint(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, end.pipe);
        boolean inbound = action == NetworkPressurePlanner.PlannedVisual.INTAKE;
        CreatePipeFlowVisualBridge.apply(level, pipe, end.pipe, end.face, inbound, CREATE_FLOW_PRESSURE);
    }

    private static FluidStack networkVisualFluid(Level level, VisualScan scan) {
        for (OpenEnd end : scan.openEnds) {
            WorldExchangeVisualEvents.VisualEvent event = WorldExchangeVisualEvents.visualFor(level, end.pipe, end.face);
            if (event != null && event.action() == WorldExchangeVisualEvents.Action.INTAKE && !event.fluid().isEmpty()) return event.fluid().copy();

            NetworkPressurePlanner.PlannedVisual action = HydraulicPlanRuntime.visualFor(level, end.pipe, end.face);
            if (action == null) action = NetworkPressurePlanner.visualFor(level, end.pipe, end.face);
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

    private static FluidStack visualFluid(Level level, OpenEnd end, NetworkPressurePlanner.PlannedVisual action, FluidStack networkVisualFluid, FluidStack eventFluid) {
        if (eventFluid != null && !eventFluid.isEmpty()) return eventFluid.copy();

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
        Set<TankContact> tankContacts = new HashSet<>();

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
                    tankContacts.add(new TankContact(tank, node.pos, face));
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, node.pos, face)) openEnds.add(new OpenEnd(node.pos, face));
            }
        }

        return new VisualScan(pipes, openEnds, tankContacts);
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static boolean tankCoversContact(TankContact contact) {
        return tankSurface(contact.tank) > contact.cutoffSurface() + EPSILON;
    }

    private static double tankSurface(FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        return (double) tank.getTankInventory().getCapacity() / (double) Math.max(1, tank.getHeight());
    }

    private static double cutoffSurface(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
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
    private record PlannedEnd(OpenEnd end, NetworkPressurePlanner.PlannedVisual action) {}
    private record Step(BlockPos from, Direction face, BlockPos to) {}
    private record VisualKey(BlockPos pipe, Direction face) {}
    private record LingeringVisual(NetworkPressurePlanner.PlannedVisual action, long untilGameTime) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face) {
        double cutoffSurface() {
            return WorldExchangeVisualLayer.cutoffSurface(pipe, face, tank);
        }
    }
    private record OpenEnd(BlockPos pipe, Direction face) {
        BlockPos worldPos() {
            return pipe.relative(face);
        }
    }
    private record VisualScan(Set<BlockPos> pipes, Set<OpenEnd> openEnds, Set<TankContact> tankContacts) {}
}
