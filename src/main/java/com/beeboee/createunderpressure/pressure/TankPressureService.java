package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class TankPressureService {
    private TankPressureService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final int MAX_ROUTES = 32;
    private static final int MAX_HYDRAULIC_SETTLE_MB = 250;
    private static final int MAX_WORLD_INTAKE_MB = 250;
    private static final int MAX_WORLD_OUTPUT_MB = 1000;
    private static final int WORLD_BLOCK_MB = 1000;
    private static final int SETTLE_EPSILON_MB = 25;
    private static final int MAX_WORLD_SOURCE_SEARCH = 96;
    private static final double HYDRAULIC_SETTLE_DEADBAND = 0.05;
    private static final double HYDRAULIC_SETTLE_FULL_HEAD = 1.0;

    private static final float LAVA_PRESSURE_MULTIPLIER = 0.35f;
    private static final float TRICKLE_PRESSURE = 8.0f;
    private static final float MAX_PRESSURE = 256.0f;

    private static final float TANK_SETTLE_MAX_PRESSURE = 0.25f;
    private static final double TANK_SETTLE_DEADBAND = 0.35;
    private static final double TANK_SETTLE_FULL_HEAD = 4.0;

    private static final double DEAD_HEAD = 0.125;
    private static final double TANK_SETTLE_HEAD = 0.0;
    private static final double TRICKLE_HEAD = 1.0;
    private static final double MAX_HEAD = 32.0;
    private static final double PIPE_RESISTANCE_PER_STEP = 0.08;
    private static final double EPSILON = 0.01;

    private static final Map<Level, ProcessedTick> PROCESSED = new WeakHashMap<>();
    private static final Map<Level, Map<BlockPos, Integer>> WORLD_SOURCE_DRAWS = new WeakHashMap<>();

    public static void tickTank(FluidTankBlockEntity tank) {
        // Intentionally disabled. Tank movement should come from connected pipe pressure,
        // not direct cosmetic tank-to-tank inventory transfers.
    }

    public static void tickPipe(FluidTransportBehaviour pipe) {
        Level level = pipe.getWorld();
        if (level == null || level.isClientSide) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        BlockPos seed = pipe.getPos();
        ProcessedTick processed = processed(level);
        if (processed.pipes.contains(seed)) return;

        Scan scan = scan(level, seed);
        if (scan == null || scan.pipes.isEmpty()) return;

        BlockPos owner = ownerPipe(scan);
        if (!seed.equals(owner)) return;
        if (overlaps(scan.pipes, processed.pipes)) return;
        processed.pipes.addAll(scan.pipes);

        DebugInfo.beginNetwork(level, scan.pipes, owner);
        try {
            int settled = settleConnectedTanks(level, scan);
            if (settled > 0) DebugInfo.log(level, "HYDRAULIC settle owner={} moved={}mb", seed, settled);

            int worldMoved = exchangeWithWorld(level, scan);
            if (worldMoved > 0) DebugInfo.log(level, "WORLD exchange owner={} moved={}mb", seed, worldMoved);

            List<PressureRoute> routes = pressureRoutes(level, scan);
            if (routes.isEmpty()) {
                DebugInfo.log(level, "GRAPH idle owner={} endpoints={} pipes={}", seed, scan.ends.size(), scan.pipes.size());
                return;
            }

            DebugInfo.log(level, "GRAPH settle owner={} routes={} endpoints={} pipes={}", seed, routes.size(), scan.ends.size(), scan.pipes.size());
            for (PressureRoute route : routes) apply(level, route);
        } finally {
            DebugInfo.endNetwork();
        }
    }

    private static ProcessedTick processed(Level level) {
        long gameTime = level.getGameTime();
        ProcessedTick processed = PROCESSED.get(level);
        if (processed == null || processed.gameTime != gameTime) {
            processed = new ProcessedTick(gameTime, new HashSet<>());
            PROCESSED.put(level, processed);
        }
        return processed;
    }

    private static boolean overlaps(Set<BlockPos> scanPipes, Set<BlockPos> processedPipes) {
        for (BlockPos pipe : scanPipes) {
            if (processedPipes.contains(pipe)) return true;
        }
        return false;
    }

    private static Scan scan(Level level, BlockPos seed) {
        Set<BlockPos> pipes = new HashSet<>();
        Set<BlockPos> bridgedTanks = new HashSet<>();
        List<End> ends = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(seed, 0));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_DISTANCE) continue;
            if (!level.isLoaded(node.pos) || !pipes.add(node.pos)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;

            BlockState state = level.getBlockState(node.pos);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!level.isLoaded(other)) continue;

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank != null) {
                    ends.add(End.tank(node.pos, face, tank));
                    if (bridgedTanks.add(tank.getController())) {
                        for (BlockPos tankSeed : seeds(level, tank)) {
                            Direction targetFace = faceToTank(level, tank, tankSeed);
                            if (targetFace != null && tankCanProvideToPipe(tankSeed, targetFace, tank)) {
                                queue.add(new Node(tankSeed, node.distance + 1));
                            }
                        }
                    }
                    continue;
                }

                FluidTransportBehaviour otherPipe = FluidPropagator.getPipe(level, other);
                if (otherPipe != null) {
                    queue.add(new Node(other, node.distance + 1));
                    continue;
                }

                boolean fluidCap = FluidPropagator.hasFluidCapability(level, other, face.getOpposite());
                boolean openEnd = FluidPropagator.isOpenEnd(level, node.pos, face);
                boolean sourceBlock = openEnd && level.getFluidState(other).isSource();
                if (fluidCap || openEnd) ends.add(End.external(node.pos, face, other.getY(), fluidCap, openEnd, sourceBlock));
            }
        }

        return new Scan(pipes, ends);
    }

    private static int settleConnectedTanks(Level level, Scan scan) {
        List<TankContact> contacts = tankContacts(level, scan);
        if (contacts.size() < 2) return 0;

        List<FluidTankBlockEntity> tanks = uniqueTanks(contacts);
        if (tanks.size() < 2 || !singleFluidGroup(tanks)) return 0;

        Map<BlockPos, Integer> startAmounts = snapshotAmounts(tanks);
        Map<BlockPos, Integer> plannedAmounts = new HashMap<>(startAmounts);
        List<HydraulicMovePlan> plans = new ArrayList<>();
        int plannedTotal = 0;

        DebugInfo.log(level, "HYDRAULIC basin contacts={} tanks={} pipes={}", contacts.size(), tanks.size(), scan.pipes.size());

        for (TankContact source : contacts) {
            if (plannedTotal >= MAX_HYDRAULIC_SETTLE_MB) break;
            if (!tankCanProvideToPipe(source.pipe, source.face, source.tank, startAmounts)) continue;

            double sourceHead = surface(source.tank, startAmounts);
            int sourceCurrent = Math.min(amountOf(source.tank, startAmounts), amountOf(source.tank, plannedAmounts));
            int sourceFloor = amountForSurface(source.tank, source.cutoffSurface());
            int sourceAvailable = sourceCurrent - sourceFloor;
            if (sourceAvailable <= 0) continue;

            List<TankContact> receivers = reachableHydraulicTargets(level, scan, contacts, source, startAmounts, plannedAmounts);
            if (receivers.isEmpty()) {
                DebugInfo.log(level, "HYDRAULIC source idle tank={} pipe={} face={} surface={} cutoff={}",
                        source.tank.getController(), source.pipe, source.face, sourceHead, source.cutoffSurface());
                continue;
            }

            int receiverCapacity = 0;
            List<TankContact> activeReceivers = new ArrayList<>();
            for (TankContact target : receivers) {
                int current = amountOf(target.tank, plannedAmounts);
                int maxAtSourceHead = amountForSurface(target.tank, Math.min(sourceHead, target.topY()));
                int needed = maxAtSourceHead - current;
                if (needed > 0) {
                    activeReceivers.add(target);
                    receiverCapacity += needed;
                }
            }
            if (activeReceivers.isEmpty() || receiverCapacity <= 0) continue;

            double sharedSurface = sharedSurfaceWithSource(source, activeReceivers, sourceHead, plannedAmounts);
            double sourceStopSurface = Math.max(source.cutoffSurface(), sharedSurface);
            int sourceAvailableToShared = sourceCurrent - amountForSurface(source.tank, sourceStopSurface);
            if (sourceAvailableToShared <= 0) continue;

            int receiverNeededToShared = receiverNeededAtSurface(activeReceivers, sharedSurface, plannedAmounts);
            if (receiverNeededToShared <= 0) continue;

            int budget = Math.min(MAX_HYDRAULIC_SETTLE_MB - plannedTotal, Math.min(sourceAvailable, receiverCapacity));
            budget = Math.min(budget, sourceAvailableToShared);
            budget = Math.min(budget, receiverNeededToShared);
            budget = Math.min(budget, curvedHydraulicBudget(sourceHead, activeReceivers, plannedAmounts));
            if (budget <= 0) continue;

            activeReceivers.sort((a, b) -> {
                int surfaceCompare = Double.compare(surface(a.tank, plannedAmounts), surface(b.tank, plannedAmounts));
                if (surfaceCompare != 0) return surfaceCompare;
                int distanceCompare = Integer.compare(a.distance, b.distance);
                if (distanceCompare != 0) return distanceCompare;
                return compareBlockPos(a.tank.getController(), b.tank.getController());
            });

            DebugInfo.log(level, "HYDRAULIC basin plan source={} sourceSurface={} sourceCutoff={} sourceAvailable={} receivers={} budget={} sharedSurface={} receiverNeeded={} sourceToShared={}",
                    source.tank.getController(), sourceHead, source.cutoffSurface(), sourceAvailable,
                    activeReceivers.size(), budget, sharedSurface, receiverNeededToShared, sourceAvailableToShared);

            for (TankContact target : activeReceivers) {
                if (plannedTotal >= MAX_HYDRAULIC_SETTLE_MB || budget <= 0) break;

                int targetCurrent = amountOf(target.tank, plannedAmounts);
                int targetAtSharedSurface = amountForSurface(target.tank, Math.min(sharedSurface, target.topY()));
                int targetNeeded = targetAtSharedSurface - targetCurrent;
                if (targetNeeded <= 0) continue;

                int move = Math.min(budget, targetNeeded);
                addAmount(plannedAmounts, source.tank, -move);
                addAmount(plannedAmounts, target.tank, move);
                plannedTotal += move;
                budget -= move;

                plans.add(new HydraulicMovePlan(source, target, move, sourceHead, sharedSurface));
            }
        }

        int movedTotal = 0;
        for (HydraulicMovePlan plan : plans) {
            int moved = moveFluid(plan.source.tank, plan.target.tank, plan.amount);
            if (moved <= 0) {
                DebugInfo.log(level, "HYDRAULIC basin move failed source={} target={} requested={} sharedSurface={}",
                        plan.source.tank.getController(), plan.target.tank.getController(), plan.amount, plan.sharedSurface);
                continue;
            }

            movedTotal += moved;
            DebugInfo.log(level, "HYDRAULIC basin move source={} target={} sourcePipe={} targetPipe={} distance={} moved={} requested={} sourceSurface={} sourceCutoff={} targetSurface={} sharedSurface={} targetCutoff={} receivers={}",
                    plan.source.tank.getController(), plan.target.tank.getController(), plan.source.pipe, plan.target.pipe, plan.target.distance,
                    moved, plan.amount, plan.sourceSurface, plan.source.cutoffSurface(), surface(plan.target.tank), plan.sharedSurface,
                    plan.target.cutoffSurface(), 1);
        }

        if (movedTotal > 0) DebugInfo.log(level, "HYDRAULIC basin result moved={}mb planned={}mb", movedTotal, plannedTotal);
        return movedTotal;
    }

    private static int exchangeWithWorld(Level level, Scan scan) {
        List<TankContact> contacts = tankContacts(level, scan);

        int intakeMoved = 0;
        int outputMoved = 0;
        for (End end : scan.ends) {
            if (!end.openEnd || end.tank != null) continue;
            BlockPos worldPos = end.pipe.relative(end.face);
            if (!level.isLoaded(worldPos)) continue;

            FluidState fluidState = level.getFluidState(worldPos);
            if (!fluidState.isEmpty()) {
                WorldIntake intake = resolveWorldIntake(level, worldPos, fluidState);
                if (intake == null) {
                    DebugInfo.log(level, "WORLD intake skip end={} pos={} reason=noSourceFound touchedFluid={}", end.name(), worldPos, fluidState.getType());
                    continue;
                }

                if (!contacts.isEmpty() && intakeMoved < MAX_WORLD_INTAKE_MB) {
                    int filled = fillTanksFromWorldSource(level, scan, contacts, end, intake, MAX_WORLD_INTAKE_MB - intakeMoved);
                    intakeMoved += filled;
                    if (filled > 0) recordWorldSourceDraw(level, intake, filled);
                }

                if (contacts.isEmpty() && outputMoved < MAX_WORLD_OUTPUT_MB) {
                    int transferred = transferWorldToWorld(level, scan, end, intake, MAX_WORLD_OUTPUT_MB - outputMoved);
                    outputMoved += transferred;
                }
            } else if (level.getBlockState(worldPos).isAir()) {
                if (!contacts.isEmpty()) {
                    outputMoved += drainTanksToWorld(level, scan, contacts, end, worldPos, MAX_WORLD_OUTPUT_MB - outputMoved);
                }
            } else {
                DebugInfo.log(level, "WORLD skip end={} pos={} reason=blocked block={} fluid={}", end.name(), worldPos, level.getBlockState(worldPos).getBlock(), fluidState.getType());
            }

            if (intakeMoved >= MAX_WORLD_INTAKE_MB && outputMoved >= MAX_WORLD_OUTPUT_MB) break;
        }
        return intakeMoved + outputMoved;
    }

    private static WorldIntake resolveWorldIntake(Level level, BlockPos touchedPos, FluidState touchedState) {
        if (touchedState.isEmpty()) return null;
        Fluid touchedFluid = touchedState.getType();
        if (touchedState.isSource()) {
            Fluid sourceFluid = sourceFluidFor(touchedFluid);
            DebugInfo.log(level, "WORLD intake sourceResolved touched={} source={} distance=0 fluid={} touchedSource=true", touchedPos, touchedPos, sourceFluid);
            return new WorldIntake(touchedPos, touchedPos, sourceFluid, 0, true);
        }

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(touchedPos, 0));
        visited.add(touchedPos);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.distance > MAX_WORLD_SOURCE_SEARCH) continue;

            FluidState state = level.getFluidState(node.pos);
            if (!sameFluidKind(touchedFluid, state.getType())) continue;
            if (state.isSource()) {
                Fluid sourceFluid = sourceFluidFor(state.getType());
                DebugInfo.log(level, "WORLD intake sourceResolved touched={} source={} distance={} fluid={} touchedSource=false", touchedPos, node.pos, node.distance, sourceFluid);
                return new WorldIntake(touchedPos, node.pos, sourceFluid, node.distance, false);
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) continue;
                FluidState nextState = level.getFluidState(next);
                if (!nextState.isEmpty() && sameFluidKind(touchedFluid, nextState.getType())) {
                    queue.add(new Node(next, node.distance + 1));
                }
            }
        }

        return null;
    }

    private static void recordWorldSourceDraw(Level level, WorldIntake intake, int amount) {
        Map<BlockPos, Integer> draws = WORLD_SOURCE_DRAWS.computeIfAbsent(level, $ -> new HashMap<>());
        int total = draws.getOrDefault(intake.sourcePos, 0) + amount;
        if (total < WORLD_BLOCK_MB) {
            draws.put(intake.sourcePos, total);
            return;
        }

        draws.remove(intake.sourcePos);
        FluidState current = level.getFluidState(intake.sourcePos);
        if (!current.isEmpty() && current.isSource() && sameFluidKind(current.getType(), intake.fluid)) {
            level.setBlockAndUpdate(intake.sourcePos, Blocks.AIR.defaultBlockState());
            DebugInfo.log(level, "WORLD intake consumedSource source={} fluid={} pulled={} touched={} distance={}", intake.sourcePos, intake.fluid, total, intake.touchedPos, intake.distance);
        } else {
            DebugInfo.log(level, "WORLD intake sourceGone source={} fluid={} pulled={} touched={} distance={}", intake.sourcePos, intake.fluid, total, intake.touchedPos, intake.distance);
        }
    }

    private static int fillTanksFromWorldSource(Level level, Scan scan, List<TankContact> contacts, End source, WorldIntake intake, int budget) {
        if (budget <= 0) return 0;
        Fluid fluid = intake.fluid;
        double sourceHead = intake.sourcePos.getY() + 1.0;
        List<TankContact> targets = reachableContactsFrom(level, scan, contacts, source.pipe, sourceHead);
        int moved = 0;

        targets.sort((a, b) -> {
            int surfaceCompare = Double.compare(surface(a.tank), surface(b.tank));
            if (surfaceCompare != 0) return surfaceCompare;
            int distanceCompare = Integer.compare(a.distance, b.distance);
            if (distanceCompare != 0) return distanceCompare;
            return compareBlockPos(a.tank.getController(), b.tank.getController());
        });

        for (TankContact target : targets) {
            if (moved >= budget) break;
            if (!canFillWith(target.tank, fluid)) continue;
            if (target.cutoffSurface() > sourceHead + EPSILON) continue;

            int current = target.tank.getTankInventory().getFluidAmount();
            int maxAtSourceHead = amountForSurface(target.tank, Math.min(sourceHead, target.topY()));
            int needed = maxAtSourceHead - current;
            if (needed <= SETTLE_EPSILON_MB) continue;

            int request = Math.min(budget - moved, Math.min(needed, curvedWorldBudget(sourceHead, surface(target.tank))));
            if (request <= 0) continue;

            FluidStack stack = new FluidStack(fluid, request);
            int filled = target.tank.getTankInventory().fill(stack, FluidAction.EXECUTE);
            if (filled <= 0) continue;

            moved += filled;
            DebugInfo.log(level, "WORLD fill source={} touchedPos={} sourcePos={} target={} targetPipe={} moved={} requested={} sourceHead={} targetSurface={} cutoff={} fluid={} sourceDistance={}",
                    source.name(), intake.touchedPos, intake.sourcePos, target.tank.getController(), target.pipe, filled, request, sourceHead, surface(target.tank), target.cutoffSurface(), fluid, intake.distance);
        }

        if (moved <= 0) DebugInfo.log(level, "WORLD fill idle source={} touchedPos={} sourcePos={} sourceHead={} fluid={} sourceDistance={}", source.name(), intake.touchedPos, intake.sourcePos, sourceHead, fluid, intake.distance);
        return moved;
    }

    private static int transferWorldToWorld(Level level, Scan scan, End source, WorldIntake intake, int budget) {
        if (budget < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "WORLD transfer skip source={} reason=budgetBelowBlock budget={}", source.name(), budget);
            return 0;
        }

        double sourceHead = intake.sourcePos.getY() + 1.0;
        List<End> targets = new ArrayList<>();
        for (End target : scan.ends) {
            if (target == source || !target.openEnd || target.tank != null) continue;
            BlockPos targetPos = target.pipe.relative(target.face);
            if (!level.isLoaded(targetPos)) continue;
            if (!level.getBlockState(targetPos).isAir()) continue;
            if (target.pipe.getY() > sourceHead + EPSILON) continue;
            if (path(level, scan, source, target.pipe) == null) continue;
            targets.add(target);
        }

        targets.sort((a, b) -> {
            int yCompare = Integer.compare(a.pipe.relative(a.face).getY(), b.pipe.relative(b.face).getY());
            if (yCompare != 0) return yCompare;
            return compareBlockPos(a.pipe, b.pipe);
        });

        for (End target : targets) {
            BlockPos targetPos = target.pipe.relative(target.face);
            if (!placeFluidBlock(level, targetPos, intake.fluid)) {
                DebugInfo.log(level, "WORLD transfer skip source={} target={} reason=blockedAtPlace", source.name(), targetPos);
                continue;
            }

            recordWorldSourceDraw(level, intake, WORLD_BLOCK_MB);
            DebugInfo.log(level, "WORLD transfer source={} touchedPos={} sourcePos={} target={} moved={} fluid={} sourceDistance={}",
                    source.name(), intake.touchedPos, intake.sourcePos, targetPos, WORLD_BLOCK_MB, intake.fluid, intake.distance);
            return WORLD_BLOCK_MB;
        }

        DebugInfo.log(level, "WORLD transfer idle source={} touchedPos={} sourcePos={} reason=noValidOutlet", source.name(), intake.touchedPos, intake.sourcePos);
        return 0;
    }

    private static int drainTanksToWorld(Level level, Scan scan, List<TankContact> contacts, End target, BlockPos worldPos, int budget) {
        if (budget < WORLD_BLOCK_MB) {
            DebugInfo.log(level, "WORLD drain skip target={} targetPos={} reason=budgetBelowBlock budget={}", target.name(), worldPos, budget);
            return 0;
        }
        double targetHead = target.head;
        List<TankContact> sources = reachableContactsFrom(level, scan, contacts, target.pipe, MAX_HEAD);
        int moved = 0;

        sources.sort((a, b) -> {
            int surfaceCompare = Double.compare(surface(b.tank), surface(a.tank));
            if (surfaceCompare != 0) return surfaceCompare;
            int distanceCompare = Integer.compare(a.distance, b.distance);
            if (distanceCompare != 0) return distanceCompare;
            return compareBlockPos(a.tank.getController(), b.tank.getController());
        });

        for (TankContact source : sources) {
            if (budget - moved < WORLD_BLOCK_MB) break;

            double sourceHead = surface(source.tank);
            if (!tankCanProvideToPipe(source.pipe, source.face, source.tank)) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=sourceBelowCutoff sourcePipe={} cutoff={} sourceSurface={}",
                        source.tank.getController(), worldPos, source.pipe, source.cutoffSurface(), sourceHead);
                continue;
            }
            if (!reachablePipeWithinHead(level, scan, target.pipe, source.pipe, sourceHead)) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=pathAboveFillY sourceSurface={}", source.tank.getController(), worldPos, sourceHead);
                continue;
            }

            FluidStack fluid = source.tank.getTankInventory().getFluid();
            if (fluid.isEmpty()) continue;
            if (!canPlaceFluidBlock(fluid.getFluid())) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=unsupportedFluid fluid={}", source.tank.getController(), worldPos, fluid.getFluid());
                continue;
            }

            if (sourceHead <= targetHead + HYDRAULIC_SETTLE_DEADBAND) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=targetHeadTooHigh sourceSurface={} targetHead={}", source.tank.getController(), worldPos, sourceHead, targetHead);
                continue;
            }

            int sourceFloor = amountForSurface(source.tank, source.cutoffSurface());
            int available = source.tank.getTankInventory().getFluidAmount() - sourceFloor;
            if (available < WORLD_BLOCK_MB) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=availableBelow1000 available={}", source.tank.getController(), worldPos, available);
                continue;
            }

            FluidStack simulated = source.tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.SIMULATE);
            if (simulated.isEmpty() || simulated.getAmount() < WORLD_BLOCK_MB) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=simulateBelow1000 simulated={}", source.tank.getController(), worldPos, simulated.getAmount());
                continue;
            }

            if (!placeFluidBlock(level, worldPos, simulated.getFluid())) {
                DebugInfo.log(level, "WORLD drain skip source={} target={} reason=blockedAtPlace", source.tank.getController(), worldPos);
                continue;
            }

            FluidStack drained = source.tank.getTankInventory().drain(WORLD_BLOCK_MB, FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                level.setBlockAndUpdate(worldPos, Blocks.AIR.defaultBlockState());
                continue;
            }

            moved += drained.getAmount();
            DebugInfo.log(level, "WORLD drain source={} sourcePipe={} target={} moved={} sourceSurface={} cutoff={} targetHead={} fluid={}",
                    source.tank.getController(), source.pipe, worldPos, drained.getAmount(), sourceHead, source.cutoffSurface(), targetHead, drained.getFluid());
            break;
        }

        if (moved <= 0) DebugInfo.log(level, "WORLD drain idle target={} targetPos={} targetHead={}", target.name(), worldPos, targetHead);
        return moved;
    }

    private static boolean reachablePipeWithinHead(Level level, Scan scan, BlockPos startPipe, BlockPos targetPipe, double maxPipeY) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPipe);
        visited.add(startPipe);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(targetPipe)) return true;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pos), pipe)) {
                BlockPos other = pos.relative(face);
                if (!scan.pipes.contains(other)) continue;
                if (other.getY() > maxPipeY + EPSILON) continue;
                if (visited.add(other)) queue.add(other);
            }
        }
        return false;
    }

    private static int curvedWorldBudget(double sourceHead, double targetSurface) {
        double headDelta = sourceHead - targetSurface;
        if (headDelta <= HYDRAULIC_SETTLE_DEADBAND) return 0;

        double t = (headDelta - HYDRAULIC_SETTLE_DEADBAND) / HYDRAULIC_SETTLE_FULL_HEAD;
        t = Math.max(0.0, Math.min(1.0, t));
        double eased = t * t * (3.0 - (2.0 * t));
        return Math.max(0, (int) Math.round(MAX_WORLD_INTAKE_MB * eased));
    }

    private static List<TankContact> reachableContactsFrom(Level level, Scan scan, List<TankContact> contacts, BlockPos startPipe, double maxPipeY) {
        Map<BlockPos, List<TankContact>> byPipe = contactsByPipe(contacts);
        Map<BlockPos, TankContact> bestByTank = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(startPipe, 0));
        visited.add(startPipe);

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            List<TankContact> pipeContacts = byPipe.get(node.pos);
            if (pipeContacts != null) {
                for (TankContact candidate : pipeContacts) {
                    BlockPos key = candidate.tank.getController();
                    TankContact withDistance = candidate.withDistance(node.distance);
                    TankContact previous = bestByTank.get(key);
                    if (previous == null || withDistance.distance < previous.distance) bestByTank.put(key, withDistance);
                }
            }

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(node.pos), pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!scan.pipes.contains(other)) continue;
                if (other.getY() > maxPipeY + EPSILON) continue;
                if (visited.add(other)) queue.add(new Node(other, node.distance + 1));
            }
        }

        return new ArrayList<>(bestByTank.values());
    }

    private static boolean canFillWith(FluidTankBlockEntity tank, Fluid fluid) {
        FluidStack existing = tank.getTankInventory().getFluid();
        return existing.isEmpty() || FluidStack.isSameFluidSameComponents(existing, new FluidStack(fluid, 1));
    }

    private static boolean sameFluidKind(Fluid a, Fluid b) {
        return (isWater(a) && isWater(b)) || (isLavaFluid(a) && isLavaFluid(b));
    }

    private static Fluid sourceFluidFor(Fluid fluid) {
        if (isWater(fluid)) return Fluids.WATER;
        if (isLavaFluid(fluid)) return Fluids.LAVA;
        return fluid;
    }

    private static boolean isWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isLavaFluid(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private static boolean canPlaceFluidBlock(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER || fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private static boolean placeFluidBlock(Level level, BlockPos pos, Fluid fluid) {
        if (!level.getBlockState(pos).isAir()) return false;
        if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER) return level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) return level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
        return false;
    }

    private static Map<BlockPos, Integer> snapshotAmounts(List<FluidTankBlockEntity> tanks) {
        Map<BlockPos, Integer> amounts = new HashMap<>();
        for (FluidTankBlockEntity tank : tanks) amounts.put(tank.getController(), tank.getTankInventory().getFluidAmount());
        return amounts;
    }

    private static int amountOf(FluidTankBlockEntity tank, Map<BlockPos, Integer> amounts) {
        return amounts.getOrDefault(tank.getController(), tank.getTankInventory().getFluidAmount());
    }

    private static void addAmount(Map<BlockPos, Integer> amounts, FluidTankBlockEntity tank, int delta) {
        BlockPos key = tank.getController();
        int capacity = tank.getTankInventory().getCapacity();
        int amount = Math.max(0, Math.min(capacity, amounts.getOrDefault(key, tank.getTankInventory().getFluidAmount()) + delta));
        amounts.put(key, amount);
    }

    private static double sharedSurfaceWithSource(TankContact source, List<TankContact> activeReceivers, double maxSurface, Map<BlockPos, Integer> amounts) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        tanks.put(source.tank.getController(), source.tank);
        for (TankContact contact : activeReceivers) tanks.putIfAbsent(contact.tank.getController(), contact.tank);

        int totalAmount = 0;
        for (FluidTankBlockEntity tank : tanks.values()) totalAmount += amountOf(tank, amounts);
        return surfaceForAmount(new ArrayList<>(tanks.values()), totalAmount, maxSurface);
    }

    private static int receiverNeededAtSurface(List<TankContact> receivers, double sharedSurface, Map<BlockPos, Integer> amounts) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        for (TankContact contact : receivers) tanks.putIfAbsent(contact.tank.getController(), contact.tank);

        int needed = 0;
        for (FluidTankBlockEntity tank : tanks.values()) {
            int current = amountOf(tank, amounts);
            int target = amountForSurface(tank, Math.min(sharedSurface, tank.getController().getY() + tank.getHeight()));
            needed += Math.max(0, target - current);
        }
        return needed;
    }

    private static int curvedHydraulicBudget(double sourceHead, List<TankContact> activeReceivers, Map<BlockPos, Integer> amounts) {
        double lowestReceiverSurface = sourceHead;
        for (TankContact target : activeReceivers) lowestReceiverSurface = Math.min(lowestReceiverSurface, surface(target.tank, amounts));

        double headDelta = sourceHead - lowestReceiverSurface;
        if (headDelta <= HYDRAULIC_SETTLE_DEADBAND) return 0;

        double t = (headDelta - HYDRAULIC_SETTLE_DEADBAND) / HYDRAULIC_SETTLE_FULL_HEAD;
        t = Math.max(0.0, Math.min(1.0, t));
        double eased = t * t * (3.0 - (2.0 * t));
        return Math.max(0, (int) Math.round(MAX_HYDRAULIC_SETTLE_MB * eased));
    }

    private static List<TankContact> reachableHydraulicTargets(Level level, Scan scan, List<TankContact> contacts, TankContact source,
                                                               Map<BlockPos, Integer> sourceAmounts, Map<BlockPos, Integer> plannedAmounts) {
        Map<BlockPos, List<TankContact>> byPipe = contactsByPipe(contacts);
        Map<BlockPos, TankContact> bestByTank = new HashMap<>();
        Map<BlockPos, Integer> distanceByPipe = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(source.pipe, 0));
        visited.add(source.pipe);
        distanceByPipe.put(source.pipe, 0);

        double sourceHead = surface(source.tank, sourceAmounts);
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            List<TankContact> pipeContacts = byPipe.get(node.pos);
            if (pipeContacts != null) {
                for (TankContact candidate : pipeContacts) {
                    if (sameTank(source.tank, candidate.tank)) continue;
                    if (!canHydraulicReceive(source, candidate, sourceAmounts, plannedAmounts)) continue;

                    BlockPos key = candidate.tank.getController();
                    TankContact withDistance = candidate.withDistance(node.distance);
                    TankContact previous = bestByTank.get(key);
                    if (previous == null || withDistance.distance < previous.distance) {
                        bestByTank.put(key, withDistance);
                    }
                }
            }

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(node.pos), pipe)) {
                BlockPos other = node.pos.relative(face);
                if (!scan.pipes.contains(other)) continue;
                if (other.getY() > sourceHead + EPSILON) continue;
                if (visited.add(other)) {
                    int distance = node.distance + 1;
                    distanceByPipe.put(other, distance);
                    queue.add(new Node(other, distance));
                }
            }
        }

        List<TankContact> targets = new ArrayList<>(bestByTank.values());
        targets.sort((a, b) -> {
            int distanceCompare = Integer.compare(a.distance, b.distance);
            if (distanceCompare != 0) return distanceCompare;
            return compareBlockPos(a.tank.getController(), b.tank.getController());
        });
        return targets;
    }

    private static double surfaceForAmount(List<FluidTankBlockEntity> tanks, int totalAmount, double maxSurface) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        Map<BlockPos, FluidTankBlockEntity> unique = new HashMap<>();
        for (FluidTankBlockEntity tank : tanks) unique.putIfAbsent(tank.getController(), tank);

        for (FluidTankBlockEntity tank : unique.values()) {
            low = Math.min(low, tank.getController().getY());
            high = Math.max(high, Math.min(maxSurface, tank.getController().getY() + tank.getHeight()));
        }
        if (low == Double.MAX_VALUE) return maxSurface;

        high = Math.min(high, maxSurface);
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) * 0.5;
            int amount = 0;
            for (FluidTankBlockEntity tank : unique.values()) amount += amountForSurface(tank, mid);
            if (amount < totalAmount) low = mid;
            else high = mid;
        }
        return (low + high) * 0.5;
    }

    private static boolean canHydraulicReceive(TankContact source, TankContact target, Map<BlockPos, Integer> sourceAmounts, Map<BlockPos, Integer> targetAmounts) {
        if (!compatible(source.tank, target.tank)) return false;
        if (amountOf(target.tank, targetAmounts) >= target.tank.getTankInventory().getCapacity()) return false;
        double sourceHead = surface(source.tank, sourceAmounts);
        if (target.cutoffSurface() > sourceHead + EPSILON) return false;
        return surface(target.tank, targetAmounts) < sourceHead - HYDRAULIC_SETTLE_DEADBAND;
    }

    private static Map<BlockPos, List<TankContact>> contactsByPipe(List<TankContact> contacts) {
        Map<BlockPos, List<TankContact>> byPipe = new HashMap<>();
        for (TankContact contact : contacts) byPipe.computeIfAbsent(contact.pipe, $ -> new ArrayList<>()).add(contact);
        return byPipe;
    }

    private static List<TankContact> tankContacts(Level level, Scan scan) {
        Map<String, TankContact> contacts = new HashMap<>();
        for (BlockPos pipePos : scan.pipes) {
            for (Direction direction : Direction.values()) {
                FluidTankBlockEntity tank = tankAt(level, pipePos.relative(direction));
                if (tank == null) continue;
                contacts.putIfAbsent(tank.getController() + "|" + pipePos + "|" + direction, new TankContact(tank, pipePos, direction, 0));
            }
        }
        return new ArrayList<>(contacts.values());
    }

    private static List<FluidTankBlockEntity> uniqueTanks(List<TankContact> contacts) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        for (TankContact contact : contacts) tanks.putIfAbsent(contact.tank.getController(), contact.tank);
        return new ArrayList<>(tanks.values());
    }

    private static boolean singleFluidGroup(List<FluidTankBlockEntity> tanks) {
        FluidStack group = FluidStack.EMPTY;
        for (FluidTankBlockEntity tank : tanks) {
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (fluid.isEmpty()) continue;
            if (group.isEmpty()) {
                group = fluid;
                continue;
            }
            if (!FluidStack.isSameFluidSameComponents(group, fluid)) return false;
        }
        return !group.isEmpty();
    }

    private static int moveFluid(FluidTankBlockEntity from, FluidTankBlockEntity to, int amount) {
        FluidStack simulatedDrain = from.getTankInventory().drain(amount, FluidAction.SIMULATE);
        if (simulatedDrain.isEmpty()) return 0;

        int canFill = to.getTankInventory().fill(simulatedDrain, FluidAction.SIMULATE);
        int actual = Math.min(amount, canFill);
        if (actual <= 0) return 0;

        FluidStack drained = from.getTankInventory().drain(actual, FluidAction.EXECUTE);
        if (drained.isEmpty()) return 0;

        int filled = to.getTankInventory().fill(drained, FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            FluidStack remainder = drained.copy();
            remainder.setAmount(drained.getAmount() - filled);
            from.getTankInventory().fill(remainder, FluidAction.EXECUTE);
        }
        return filled;
    }

    private static List<PressureRoute> pressureRoutes(Level level, Scan scan) {
        Map<String, PressureRoute> bestRoutesByTarget = new HashMap<>();

        for (End source : scan.ends) {
            if (!source.provides) continue;

            for (End target : scan.ends) {
                if (source == target) continue;
                if (!canReceiveFrom(source, target)) continue;
                if (tankToTank(source, target)) continue;
                if (source.tank != null && target.openEnd) {
                    DebugInfo.log(level, "GRAPH skip raw tank drain source={} target={}", source.name(), target.name());
                    continue;
                }
                if (source.openEnd && target.tank != null) {
                    DebugInfo.log(level, "GRAPH skip raw world fill source={} target={}", source.name(), target.name());
                    continue;
                }
                if (source.openEnd && target.openEnd) {
                    DebugInfo.log(level, "GRAPH skip raw world transfer source={} target={}", source.name(), target.name());
                    continue;
                }

                double head = source.head - target.head;
                double requiredHead = requiredHead(source, target);
                if (head <= requiredHead + EPSILON) continue;

                List<Step> path = path(level, scan, source, target.pipe);
                if (path == null) continue;

                double activeHead = head - requiredHead;
                double conductance = conductance(path);
                float pressure = routePressure(source, target, activeHead, conductance);
                if (pressure <= 0) continue;

                PressureRoute route = new PressureRoute(source, target, path, head, activeHead, conductance, pressure);
                String key = target.nodeKey();
                PressureRoute previous = bestRoutesByTarget.get(key);
                if (previous == null || routeScore(route) > routeScore(previous)) bestRoutesByTarget.put(key, route);
            }
        }

        List<PressureRoute> routes = new ArrayList<>(bestRoutesByTarget.values());
        routes.sort((a, b) -> {
            int pressureCompare = Float.compare(b.pressure, a.pressure);
            if (pressureCompare != 0) return pressureCompare;
            int sourceCompare = a.source.nodeKey().compareTo(b.source.nodeKey());
            if (sourceCompare != 0) return sourceCompare;
            return a.target.nodeKey().compareTo(b.target.nodeKey());
        });

        if (routes.size() <= MAX_ROUTES) return routes;
        return new ArrayList<>(routes.subList(0, MAX_ROUTES));
    }

    private static float routePressure(End source, End target, double activeHead, double conductance) {
        if (tankToTank(source, target)) return tankSettlePressure(source, activeHead, conductance);
        return pressureForHead(activeHead) * (float) conductance * pressureMultiplier(source);
    }

    private static float tankSettlePressure(End source, double activeHead, double conductance) {
        if (activeHead <= TANK_SETTLE_DEADBAND) return 0.0f;

        double t = (activeHead - TANK_SETTLE_DEADBAND) / (TANK_SETTLE_FULL_HEAD - TANK_SETTLE_DEADBAND);
        t = Math.max(0.0, Math.min(1.0, t));
        double eased = t * t * (3.0 - (2.0 * t));

        return (float) (TANK_SETTLE_MAX_PRESSURE * eased * conductance * pressureMultiplier(source));
    }

    private static double routeScore(PressureRoute route) {
        return route.activeHead + (route.pressure * 0.01) - (route.path.size() * 0.001);
    }

    private static boolean canReceiveFrom(End source, End target) {
        if (!target.receives) return false;
        if (sameTank(source, target)) return false;
        return compatible(source, target);
    }

    private static double requiredHead(End source, End target) {
        return tankToTank(source, target) ? TANK_SETTLE_HEAD : DEAD_HEAD;
    }

    private static boolean compatible(End source, End target) {
        if (source.tank == null || target.tank == null) return true;
        return compatible(source.tank, target.tank);
    }

    private static boolean compatible(FluidTankBlockEntity source, FluidTankBlockEntity target) {
        FluidStack sourceFluid = source.getTankInventory().getFluid();
        FluidStack targetFluid = target.getTankInventory().getFluid();
        return sourceFluid.isEmpty() || targetFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
    }

    private static boolean tankToTank(End source, End target) {
        return source.tank != null && target.tank != null;
    }

    private static double conductance(List<Step> path) {
        return 1.0 / (1.0 + (path.size() * PIPE_RESISTANCE_PER_STEP));
    }

    private static BlockPos ownerPipe(Scan scan) {
        BlockPos owner = null;
        for (BlockPos pipe : scan.pipes) {
            if (owner == null || compareBlockPos(pipe, owner) < 0) owner = pipe;
        }
        return owner;
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static boolean sameTank(End a, End b) {
        return a.tank != null && b.tank != null && sameTank(a.tank, b.tank);
    }

    private static boolean sameTank(FluidTankBlockEntity a, FluidTankBlockEntity b) {
        return a.getController().equals(b.getController());
    }

    private static List<Step> path(Level level, Scan scan, End source, BlockPos target) {
        BlockPos start = source.pipe;
        Map<BlockPos, Step> from = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(target)) break;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) continue;

            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pos), pipe)) {
                BlockPos other = pos.relative(face);
                if (!level.isLoaded(other)) continue;

                if (scan.pipes.contains(other) && visited.add(other)) {
                    from.put(other, new Step(pos, face, other, face.getOpposite()));
                    queue.add(other);
                    continue;
                }

                FluidTankBlockEntity tank = tankAt(level, other);
                if (tank == null) continue;
                if (tankBlocksPath(source, tank)) continue;

                for (BlockPos tankSeed : seeds(level, tank)) {
                    if (tankSeed.equals(pos) || !scan.pipes.contains(tankSeed)) continue;
                    Direction targetFace = faceToTank(level, tank, tankSeed);
                    if (targetFace == null || !tankCanProvideToPipe(tankSeed, targetFace, tank)) continue;
                    if (!visited.add(tankSeed)) continue;
                    from.put(tankSeed, new Step(pos, face, tankSeed, targetFace));
                    queue.add(tankSeed);
                }
            }
        }

        if (!start.equals(target) && !from.containsKey(target)) return null;
        List<Step> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(start)) {
            Step step = from.get(cursor);
            if (step == null) return null;
            reversed.add(step);
            cursor = step.from;
        }

        List<Step> out = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) out.add(reversed.get(i));
        return out;
    }

    private static boolean tankBlocksPath(End source, FluidTankBlockEntity tank) {
        if (source.tank != null && source.tank.getController().equals(tank.getController())) return false;
        if (tank.getTankInventory().getFluidAmount() >= tank.getTankInventory().getCapacity()) return false;
        if (surface(tank) >= source.head - TANK_SETTLE_HEAD) return false;
        if (source.tank == null) return true;

        FluidStack sourceFluid = source.tank.getTankInventory().getFluid();
        FluidStack targetFluid = tank.getTankInventory().getFluid();
        return targetFluid.isEmpty() || sourceFluid.isEmpty() || FluidStack.isSameFluidSameComponents(sourceFluid, targetFluid);
    }

    private static Direction faceToTank(Level level, FluidTankBlockEntity tank, BlockPos pipePos) {
        BlockPos controller = tank.getController();
        for (Direction direction : Direction.values()) {
            FluidTankBlockEntity adjacent = tankAt(level, pipePos.relative(direction));
            if (adjacent != null && adjacent.getController().equals(controller)) return direction;
        }
        return null;
    }

    private static void apply(Level level, PressureRoute route) {
        Map<BlockPos, Map<Direction, Boolean>> graph = new HashMap<>();

        graph.computeIfAbsent(route.source.pipe, $ -> new IdentityHashMap<>()).put(route.source.face, true);
        for (Step step : route.path) {
            graph.computeIfAbsent(step.from, $ -> new IdentityHashMap<>()).put(step.fromFace, false);
            graph.computeIfAbsent(step.to, $ -> new IdentityHashMap<>()).put(step.toFace, true);
        }
        graph.computeIfAbsent(route.target.pipe, $ -> new IdentityHashMap<>()).put(route.target.face, false);

        DebugInfo.log(level, "GRAPH route source={} target={} head={} activeHead={} pressure={} conductance={} path={}",
                route.source.name(), route.target.name(), route.head, route.activeHead, route.pressure, route.conductance, route.path.size());

        for (Map.Entry<BlockPos, Map<Direction, Boolean>> pipeEntry : graph.entrySet()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipeEntry.getKey());
            if (pipe == null) continue;
            for (Map.Entry<Direction, Boolean> side : pipeEntry.getValue().entrySet()) {
                pipe.addPressure(side.getKey(), side.getValue(), route.pressure);
                DebugInfo.log(level, "Added graph pressure pipePos={} side={} inbound={} pressure={}", pipeEntry.getKey(), side.getKey(), side.getValue(), route.pressure);
            }
        }

        for (BlockPos pipePos : graph.keySet()) FluidTransportBehaviour.cacheFlows(level, pipePos);
    }

    private static float pressureMultiplier(End source) {
        return fluidPressureMultiplier(source);
    }

    private static float fluidPressureMultiplier(End source) {
        if (source.tank == null) return 1.0f;
        FluidStack fluid = source.tank.getTankInventory().getFluid();
        return isLava(fluid) ? LAVA_PRESSURE_MULTIPLIER : 1.0f;
    }

    private static boolean isLava(FluidStack stack) {
        return !stack.isEmpty() && stack.getFluid() == Fluids.LAVA;
    }

    private static float pressureForHead(double head) {
        if (head < DEAD_HEAD) return 0.0f;
        if (head < TRICKLE_HEAD) {
            double t = (head - DEAD_HEAD) / (TRICKLE_HEAD - DEAD_HEAD);
            return (float) (TRICKLE_PRESSURE * t * (2.0 - t));
        }
        double t = Math.min(1.0, (head - TRICKLE_HEAD) / (MAX_HEAD - TRICKLE_HEAD));
        return (float) (TRICKLE_PRESSURE + (t * t * (MAX_PRESSURE - TRICKLE_PRESSURE)));
    }

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static Set<BlockPos> seeds(Level level, FluidTankBlockEntity tank) {
        Set<BlockPos> seeds = new HashSet<>();
        BlockPos base = tank.getController();
        for (int x = 0; x < tank.getWidth(); x++) {
            for (int y = 0; y < tank.getHeight(); y++) {
                for (int z = 0; z < tank.getWidth(); z++) {
                    BlockPos tankBlock = base.offset(x, y, z);
                    for (Direction direction : Direction.values()) {
                        BlockPos pipePos = tankBlock.relative(direction);
                        if (level.isLoaded(pipePos) && FluidPropagator.getPipe(level, pipePos) != null) seeds.add(pipePos);
                    }
                }
            }
        }
        return seeds;
    }

    private static double surface(FluidTankBlockEntity tank) {
        return surface(tank, tank.getTankInventory().getFluidAmount());
    }

    private static double surface(FluidTankBlockEntity tank, Map<BlockPos, Integer> amounts) {
        return surface(tank, amountOf(tank, amounts));
    }

    private static double surface(FluidTankBlockEntity tank, int amount) {
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
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

    private static boolean tankCanProvideToPipe(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
        return tankCanProvideToPipe(pipe, face, tank, tank.getTankInventory().getFluidAmount());
    }

    private static boolean tankCanProvideToPipe(BlockPos pipe, Direction face, FluidTankBlockEntity tank, Map<BlockPos, Integer> amounts) {
        return tankCanProvideToPipe(pipe, face, tank, amountOf(tank, amounts));
    }

    private static boolean tankCanProvideToPipe(BlockPos pipe, Direction face, FluidTankBlockEntity tank, int amount) {
        if (amount <= 0) return false;
        double cutoffSurface = sourceCutoffSurface(pipe, face, tank);
        return surface(tank, amount) > cutoffSurface + EPSILON && amount > amountForSurface(tank, cutoffSurface);
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        int capacity = tank.getTankInventory().getCapacity();
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return Math.max(0, Math.min(capacity, (int) Math.round(filledHeight * layerCapacity(tank))));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        int capacity = tank.getTankInventory().getCapacity();
        int height = Math.max(1, tank.getHeight());
        return (double) capacity / (double) height;
    }

    private record Scan(Set<BlockPos> pipes, List<End> ends) {}
    private record Node(BlockPos pos, int distance) {}
    private record Step(BlockPos from, Direction fromFace, BlockPos to, Direction toFace) {}
    private record TankContact(FluidTankBlockEntity tank, BlockPos pipe, Direction face, int distance) {
        double head() {
            return surface(tank);
        }

        double cutoffSurface() {
            return sourceCutoffSurface(pipe, face, tank);
        }

        double topY() {
            return tank.getController().getY() + tank.getHeight();
        }

        TankContact withDistance(int distance) {
            return new TankContact(tank, pipe, face, distance);
        }
    }
    private record WorldIntake(BlockPos touchedPos, BlockPos sourcePos, Fluid fluid, int distance, boolean touchedSource) {}
    private record HydraulicMovePlan(TankContact source, TankContact target, int amount, double sourceSurface, double sharedSurface) {}
    private record PressureRoute(End source, End target, List<Step> path, double head, double activeHead, double conductance, float pressure) {}
    private record ProcessedTick(long gameTime, Set<BlockPos> pipes) {}

    private static final class End {
        final BlockPos pipe;
        final Direction face;
        final FluidTankBlockEntity tank;
        final double head;
        final boolean provides;
        final boolean receives;
        final boolean openEnd;

        private End(BlockPos pipe, Direction face, FluidTankBlockEntity tank, double head, boolean provides, boolean receives, boolean openEnd) {
            this.pipe = pipe;
            this.face = face;
            this.tank = tank;
            this.head = head;
            this.provides = provides;
            this.receives = receives;
            this.openEnd = openEnd;
        }

        static End tank(BlockPos pipe, Direction face, FluidTankBlockEntity tank) {
            int amount = tank.getTankInventory().getFluidAmount();
            int capacity = tank.getTankInventory().getCapacity();
            return new End(pipe, face, tank, surface(tank), tankCanProvideToPipe(pipe, face, tank), amount < capacity, false);
        }

        static End external(BlockPos pipe, Direction face, double y, boolean fluidCap, boolean openEnd, boolean sourceBlock) {
            return new End(pipe, face, null, y + (sourceBlock ? 1.0 : 0.0), sourceBlock, fluidCap || openEnd, openEnd);
        }

        String nodeKey() {
            if (tank != null) return "tank:" + tank.getController();
            return "external:" + pipe.relative(face);
        }

        String name() {
            if (tank != null) return "tank@" + tank.getController() + "/pipe=" + pipe + "/face=" + face;
            return "external@" + pipe.relative(face) + "/pipe=" + pipe + "/face=" + face + "/open=" + openEnd;
        }
    }
}
