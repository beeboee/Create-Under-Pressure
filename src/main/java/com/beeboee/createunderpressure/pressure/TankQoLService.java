package com.beeboee.createunderpressure.pressure;

import com.beeboee.createunderpressure.debug.DebugInfo;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class TankQoLService {
    private TankQoLService() {}

    private static final int TICK_INTERVAL = 5;
    private static final int MAX_DISTANCE = 96;
    private static final int EDGE_CLEANUP_MB = 5;
    private static final int COMMON_SNAP_MAX_SPREAD_MB = 32;
    private static final long STABLE_BEFORE_CLEANUP_TICKS = 100;
    private static final Map<BlockPos, TankSnapshot> STABILITY = new HashMap<>();

    public static void tickTank(FluidTankBlockEntity tickTank) {
        Level level = tickTank.getLevel();
        if (level == null || level.isClientSide || !tickTank.isController()) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        TankNetwork network = tankNetwork(level, tickTank);
        if (network.tanks.size() < 2) return;
        if (!network.owner.getController().equals(tickTank.getController())) return;

        FluidStack groupFluid = groupFluid(network.tanks);
        if (groupFluid == null || groupFluid.isEmpty()) return;
        if (!stableLongEnough(level, network)) return;

        Random random = new Random(level.getGameTime() ^ tickTank.getController().asLong());
        int cleaned = cleanupEdges(network.tanks, random);
        int balanced = balanceCommonValue(network.tanks, random);

        if (cleaned > 0 || balanced > 0) {
            DebugInfo.log(level, "TANK qol source={} tanks={} cleanup={} balanced={}", tickTank.getController(), network.tanks.size(), cleaned, balanced);
        }
    }

    private static boolean stableLongEnough(Level level, TankNetwork network) {
        Map<BlockPos, Integer> amounts = tankAmounts(network.tanks);
        BlockPos key = network.owner.getController();
        TankSnapshot previous = STABILITY.get(key);

        if (previous == null || !previous.amounts.equals(amounts)) {
            STABILITY.put(key, new TankSnapshot(level.getGameTime(), amounts));
            return false;
        }

        return level.getGameTime() - previous.stableSince >= STABLE_BEFORE_CLEANUP_TICKS;
    }

    private static Map<BlockPos, Integer> tankAmounts(List<FluidTankBlockEntity> tanks) {
        Map<BlockPos, Integer> amounts = new HashMap<>();
        for (FluidTankBlockEntity tank : tanks) amounts.put(tank.getController(), tank.getTankInventory().getFluidAmount());
        return amounts;
    }

    private static int cleanupEdges(List<FluidTankBlockEntity> tanks, Random random) {
        List<FluidTankBlockEntity> shuffled = new ArrayList<>(tanks);
        Collections.shuffle(shuffled, random);

        int moved = 0;

        for (FluidTankBlockEntity tank : shuffled) {
            int amount = tank.getTankInventory().getFluidAmount();
            if (amount <= 0 || amount > EDGE_CLEANUP_MB) continue;
            moved += moveIntoRandomOtherTank(tank, shuffled, amount);
        }

        Collections.shuffle(shuffled, random);
        for (FluidTankBlockEntity tank : shuffled) {
            int amount = tank.getTankInventory().getFluidAmount();
            int capacity = tank.getTankInventory().getCapacity();
            int room = capacity - amount;
            if (amount <= 0 || room <= 0 || room > EDGE_CLEANUP_MB) continue;
            moved += pullFromRandomOtherTank(tank, shuffled, room);
        }

        return moved;
    }

    private static int balanceCommonValue(List<FluidTankBlockEntity> tanks, Random random) {
        if (tanks.size() < 2) return 0;

        int capacity = tanks.get(0).getTankInventory().getCapacity();
        int total = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (FluidTankBlockEntity tank : tanks) {
            if (tank.getTankInventory().getCapacity() != capacity) return 0;
            int amount = tank.getTankInventory().getFluidAmount();
            total += amount;
            min = Math.min(min, amount);
            max = Math.max(max, amount);
        }

        if (max <= min) return 0;
        if (max - min > COMMON_SNAP_MAX_SPREAD_MB) return 0;
        if (total % tanks.size() != 0) return 0;

        int target = total / tanks.size();
        if (target <= 0 || target >= capacity) return 0;

        List<FluidTankBlockEntity> sources = new ArrayList<>();
        List<FluidTankBlockEntity> targets = new ArrayList<>();
        for (FluidTankBlockEntity tank : tanks) {
            int amount = tank.getTankInventory().getFluidAmount();
            if (amount > target) sources.add(tank);
            if (amount < target) targets.add(tank);
        }

        if (sources.isEmpty() || targets.isEmpty()) return 0;

        Collections.shuffle(sources, random);
        Collections.shuffle(targets, random);

        int movedTotal = 0;
        int sourceIndex = 0;
        for (FluidTankBlockEntity targetTank : targets) {
            int needed = target - targetTank.getTankInventory().getFluidAmount();
            while (needed > 0 && sourceIndex < sources.size()) {
                FluidTankBlockEntity sourceTank = sources.get(sourceIndex);
                int surplus = sourceTank.getTankInventory().getFluidAmount() - target;
                if (surplus <= 0) {
                    sourceIndex++;
                    continue;
                }

                int moved = moveFluid(sourceTank, targetTank, Math.min(needed, surplus));
                if (moved <= 0) {
                    sourceIndex++;
                    continue;
                }

                needed -= moved;
                movedTotal += moved;
            }
        }

        return movedTotal;
    }

    private static int moveIntoRandomOtherTank(FluidTankBlockEntity from, List<FluidTankBlockEntity> shuffled, int amount) {
        for (FluidTankBlockEntity to : shuffled) {
            if (sameTank(from, to)) continue;
            int moved = moveFluid(from, to, amount);
            if (moved > 0) return moved;
        }
        return 0;
    }

    private static int pullFromRandomOtherTank(FluidTankBlockEntity to, List<FluidTankBlockEntity> shuffled, int amount) {
        for (FluidTankBlockEntity from : shuffled) {
            if (sameTank(from, to)) continue;
            if (from.getTankInventory().getFluidAmount() <= amount) continue;
            int moved = moveFluid(from, to, amount);
            if (moved > 0) return moved;
        }
        return 0;
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

    private static FluidStack groupFluid(List<FluidTankBlockEntity> tanks) {
        FluidStack groupFluid = FluidStack.EMPTY;
        for (FluidTankBlockEntity tank : tanks) {
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (fluid.isEmpty()) continue;
            if (groupFluid.isEmpty()) {
                groupFluid = fluid;
                continue;
            }
            if (!FluidStack.isSameFluidSameComponents(groupFluid, fluid)) return null;
        }
        return groupFluid;
    }

    private static TankNetwork tankNetwork(Level level, FluidTankBlockEntity start) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new HashMap<>();
        Set<BlockPos> pipes = new HashSet<>();
        ArrayDeque<FluidTankBlockEntity> tankQueue = new ArrayDeque<>();
        ArrayDeque<BlockPos> pipeQueue = new ArrayDeque<>();

        addTank(tanks, tankQueue, start);

        while (!tankQueue.isEmpty() || !pipeQueue.isEmpty()) {
            while (!tankQueue.isEmpty()) {
                FluidTankBlockEntity tank = tankQueue.removeFirst();
                for (BlockPos seed : seeds(level, tank)) {
                    if (pipes.add(seed)) pipeQueue.add(seed);
                }
            }

            while (!pipeQueue.isEmpty()) {
                BlockPos pipePos = pipeQueue.removeFirst();
                if (pipes.size() > MAX_DISTANCE) return new TankNetwork(new ArrayList<>(tanks.values()), owner(tanks, start));

                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
                if (pipe == null) continue;

                for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                    BlockPos other = pipePos.relative(face);
                    if (!level.isLoaded(other)) continue;

                    FluidTankBlockEntity tank = tankAt(level, other);
                    if (tank != null) {
                        addTank(tanks, tankQueue, tank);
                        continue;
                    }

                    if (FluidPropagator.getPipe(level, other) != null && pipes.add(other)) pipeQueue.add(other);
                }
            }
        }

        return new TankNetwork(new ArrayList<>(tanks.values()), owner(tanks, start));
    }

    private static void addTank(Map<BlockPos, FluidTankBlockEntity> tanks, ArrayDeque<FluidTankBlockEntity> queue, FluidTankBlockEntity tank) {
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        if (controller == null) controller = tank;
        if (tanks.putIfAbsent(controller.getController(), controller) == null) queue.add(controller);
    }

    private static FluidTankBlockEntity owner(Map<BlockPos, FluidTankBlockEntity> tanks, FluidTankBlockEntity start) {
        FluidTankBlockEntity owner = null;
        for (FluidTankBlockEntity tank : tanks.values()) {
            if (owner == null || compareBlockPos(tank.getController(), owner.getController()) < 0) owner = tank;
        }
        return owner == null ? start : owner;
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

    private static FluidTankBlockEntity tankAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return null;
        FluidTankBlockEntity controller = tank.isController() ? tank : tank.getControllerBE();
        return controller == null ? tank : controller;
    }

    private static boolean sameTank(FluidTankBlockEntity a, FluidTankBlockEntity b) {
        return a.getController().equals(b.getController());
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }

    private record TankNetwork(List<FluidTankBlockEntity> tanks, FluidTankBlockEntity owner) {}
    private record TankSnapshot(long stableSince, Map<BlockPos, Integer> amounts) {}
}
