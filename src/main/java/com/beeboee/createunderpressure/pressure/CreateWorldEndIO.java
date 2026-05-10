package com.beeboee.createunderpressure.pressure;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.PipeConnection;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Boundary adapter between our pressure planner and Create's open-ended pipe logic.
 *
 * The planner should decide whether a pipe end is allowed to interact with the world.
 * This class delegates the actual world fluid pickup/deposit/particles to Create so
 * our add-on behaves like a Create add-on instead of reimplementing vanilla fluid IO.
 */
public final class CreateWorldEndIO {
    private CreateWorldEndIO() {}

    private static final int WORLD_BLOCK_MB = 1000;

    public static FluidStack simulateDrain(Level level, BlockPos pipePos, Direction face) {
        return drain(level, pipePos, face, WORLD_BLOCK_MB, FluidAction.SIMULATE);
    }

    public static FluidStack drain(Level level, BlockPos pipePos, Direction face, int maxAmount, FluidAction action) {
        if (level == null || pipePos == null || face == null || maxAmount <= 0) return FluidStack.EMPTY;
        IFluidHandler handler = handler(level, pipePos, face);
        if (handler == null) return FluidStack.EMPTY;
        return handler.drain(Math.min(maxAmount, WORLD_BLOCK_MB), action);
    }

    public static int simulateFill(Level level, BlockPos pipePos, Direction face, FluidStack stack) {
        return fill(level, pipePos, face, stack, FluidAction.SIMULATE);
    }

    public static int fill(Level level, BlockPos pipePos, Direction face, FluidStack stack, FluidAction action) {
        if (level == null || pipePos == null || face == null || stack == null || stack.isEmpty()) return 0;
        IFluidHandler handler = handler(level, pipePos, face);
        if (handler == null) return 0;
        return handler.fill(stack, action);
    }

    public static boolean canDrain(Level level, BlockPos pipePos, Direction face) {
        return !simulateDrain(level, pipePos, face).isEmpty();
    }

    public static boolean canFill(Level level, BlockPos pipePos, Direction face, FluidStack stack) {
        return simulateFill(level, pipePos, face, stack) > 0;
    }

    public static void spawnCreateParticles(Level level, BlockPos pipePos, Direction face, FluidStack stack, boolean splashOnRim) {
        if (level == null || pipePos == null || face == null) return;
        FluidStack visualStack = stack == null || stack.isEmpty() ? new FluidStack(Fluids.WATER, WORLD_BLOCK_MB) : stack;
        PipeConnection connection = new PipeConnection(face);
        if (splashOnRim) connection.spawnSplashOnRim(level, pipePos, visualStack);
        else connection.spawnParticles(level, pipePos, visualStack);
    }

    private static IFluidHandler handler(Level level, BlockPos pipePos, Direction face) {
        if (!level.isLoaded(pipePos.relative(face))) return null;
        OpenEndedPipe openEnd = new OpenEndedPipe(new BlockFace(pipePos, face));
        BlockEntity blockEntity = level.getBlockEntity(pipePos);
        openEnd.manageSource(level, blockEntity);
        return openEnd.provideHandler().getCapability();
    }
}
