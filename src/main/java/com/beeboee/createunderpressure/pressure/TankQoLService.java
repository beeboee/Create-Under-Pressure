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
    private static final int BOOST_MIN_DELTA_MB = 64;
    private static final int WATER_BOOST_CAP_MB = 100;
    private static final int LAVA_BOOST_CAP_MB = 25;
    private static final double WATER_BOOST_FACTOR = 0.12;
    private static final double LAVA_BOOST_FACTOR = 0.04;
    private static final double BOOST_FULL_HEAD = 2.0;

    public static void tickTank(FluidTankBlockEntity tickTank) {
        Level level = tickTank.getLevel();
        if (level == null || level.isClientSide || !tickTank.isController()) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        TankNetwork network = tankNetwork(level, tickTank);
        if (network.tanks.size() < 2) return;
        if (!network.owner.getController().equals(tickTank.getController())) return;

        FluidStack groupFluid = groupFluid(network.tanks);
        if (groupFluid == null || groupFluid.isEmpty()) return;

        Random random = new Random(level.getGameTime() ^ tickTank.getController().asLong());
        int cleaned = cleanupEdges(level, network.tanks, random);
        int boosted = boostLargeDifferences(level, network.tanks, groupFluid, random);

        if (cleaned > 0 || boosted > 0) {
            DebugInfo.log(level, "TANK qol source={} tanks={} cleanup={} boosted={}", tickTank.getController(), network.tanks.size(), cleaned, boosted);
        }
    }

    private static int cleanupEdges(Level level, List<FluidTankBlockEntity> tanks, Random random) {
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

    private static int boostLargeDifferences(Level level, List<FluidTankBlockEntity> tanks, FluidStack groupFluid, Random random) {
        int totalFluid = 0;
        for (FluidTankBlockEntity tank : tanks) totalFluid += tank.getTankInventory().getFluidAmount();
        if (totalFluid <= 0) return 0;

        boolean lava = groupFluid.getFluid().toString().contains("lava");
        int cap = lava ? LAVA_BOOST_CAP_MB : WATER_BOOST_CAP_MB;
        double factor = lava ? LAVA_BOOST_FACTOR : WATER_BOOST_FACTOR;
        double targetSurface = sharedSurface(tanks, totalFluid);

        List<TankDelta> surplus = new ArrayList<>();
        List<TankDelta> deficit = new ArrayList<>();
        for (FluidTankBlockEntity tank : tanks) {
            int current = tank.getTankInventory().getFluidAmount();
            int target = amountForSurface(tank, targetSurface);
            int delta = target - current;
            if (delta >= BOOST_MIN_DELTA_MB) deficit.add(new TankDelta(tank, surface(tank), delta));
            if (delta <= -BOOST_MIN_DELTA_MB) surplus.add(new TankDelta(tank, surface(tank), -delta));
        }

        if (surplus.isEmpty() || deficit.isEmpty()) return 0;

        surplus.sort((a, b) -> Double.compare(b.surface, a.surface));
        deficit.sort((a, b) -> Double.compare(a.surface, b.surface));

        // Slightly randomize equal-priority networks so tiny leftovers do not always come from the same tank.
        if (random.nextBoolean()) Collections.reverse(surplus);
        if (random.nextBoolean()) Collections.reverse(deficit);

        int movedTotal = 0;
        int budget = cap;
        for (TankDelta to : deficit) {
            if (budget <= 0) break;
            int wanted = plannedBoostMove(to, targetSurface, factor, budget);
            for (TankDelta from : surplus) {
                if (budget <= 0 || wanted <= 0) break;
                if (from.amount <= 0) continue;
                int requested = Math.min(Math.min(wanted, from.amount), budget);
                int moved = moveFluid(from.tank, to.tank, requested);
                if (moved <= 0) continue;
                from.amount -= moved;
                wanted -= moved;
                budget -= moved;
                movedTotal += moved;
            }
        }

        return movedTotal;
    }

    private static int plannedBoostMove(TankDelta delta, double targetSurface, double factor, int cap) {
        double head = Math.abs(delta.surface - targetSurface);
        double multiplier = Math.min(1.0, head / BOOST_FULL_HEAD);
        int planned = (int) Math.ceil(delta.amount * factor * multiplier);
        return Math.max(1, Math.min(Math.min(delta.amount, cap), planned));
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

    private static double sharedSurface(List<FluidTankBlockEntity> tanks, int totalFluid) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (FluidTankBlockEntity tank : tanks) {
            double bottom = tank.getController().getY();
            low = Math.min(low, bottom);
            high = Math.max(high, bottom + tank.getHeight());
        }

        for (int i = 0; i < 48; i++) {
            double mid = (low + high) / 2.0;
            double volume = 0.0;
            for (FluidTankBlockEntity tank : tanks) volume += amountAtSurface(tank, mid);
            if (volume < totalFluid) low = mid;
            else high = mid;
        }
        return (low + high) / 2.0;
    }

    private static double amountAtSurface(FluidTankBlockEntity tank, double surfaceY) {
        double filledHeight = Math.max(0.0, Math.min(tank.getHeight(), surfaceY - tank.getController().getY()));
        return filledHeight * layerCapacity(tank);
    }

    private static int amountForSurface(FluidTankBlockEntity tank, double surfaceY) {
        int capacity = tank.getTankInventory().getCapacity();
        return Math.max(0, Math.min(capacity, (int) Math.round(amountAtSurface(tank, surfaceY))));
    }

    private static double surface(FluidTankBlockEntity tank) {
        int amount = tank.getTankInventory().getFluidAmount();
        if (amount <= 0) return tank.getController().getY();
        return tank.getController().getY() + (amount / layerCapacity(tank));
    }

    private static double layerCapacity(FluidTankBlockEntity tank) {
        int capacity = tank.getTankInventory().getCapacity();
        int height = Math.max(1, tank.getHeight());
        return (double) capacity / (double) height;
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

    private static final class TankDelta {
        final FluidTankBlockEntity tank;
        final double surface;
        int amount;

        TankDelta(FluidTankBlockEntity tank, double surface, int amount) {
            this.tank = tank;
            this.surface = surface;
            this.amount = amount;
        }
    }
}
