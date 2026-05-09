package com.beeboee.createunderpressure.pressure;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts Create mechanical pump speed into a simple hydraulic head boost.
 *
 * Design rule:
 * - Unpowered pumps are neutral.
 * - Powered pumps add round(abs(rpm) / 8), clamped to 1..32 blocks.
 * - Create pump facing is treated as downstream/output.
 * - The opposite side is treated as upstream/input.
 */
public final class PumpHeadPressure {
    private PumpHeadPressure() {}

    public static final int RPM_PER_HEAD_BLOCK = 8;
    public static final int MAX_HEAD_BOOST = 32;

    public static boolean isPump(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        return level.getBlockEntity(pos) instanceof PumpBlockEntity;
    }

    public static int boost(Level level, BlockPos pumpPos) {
        if (level == null || pumpPos == null || !level.isLoaded(pumpPos)) return 0;
        BlockEntity be = level.getBlockEntity(pumpPos);
        if (!(be instanceof PumpBlockEntity pump)) return 0;

        float rpm = Math.abs(pump.getSpeed());
        if (rpm <= 0.0f) return 0;
        return Math.max(1, Math.min(MAX_HEAD_BOOST, Math.round(rpm / RPM_PER_HEAD_BLOCK)));
    }

    public static Direction outputSide(Level level, BlockPos pumpPos) {
        if (level == null || pumpPos == null || !level.isLoaded(pumpPos)) return null;
        BlockState state = level.getBlockState(pumpPos);
        if (!PumpBlock.isPump(state)) return null;
        return state.getValue(PumpBlock.FACING);
    }

    public static Direction inputSide(Level level, BlockPos pumpPos) {
        Direction output = outputSide(level, pumpPos);
        return output == null ? null : output.getOpposite();
    }

    public static boolean isInputNeighbor(Level level, BlockPos pumpPos, BlockPos neighbor) {
        Direction input = inputSide(level, pumpPos);
        return input != null && pumpPos.relative(input).equals(neighbor);
    }

    public static boolean isOutputNeighbor(Level level, BlockPos pumpPos, BlockPos neighbor) {
        Direction output = outputSide(level, pumpPos);
        return output != null && pumpPos.relative(output).equals(neighbor);
    }

    /**
     * Returns the head boost gained when a path uses this pump from output side to input side.
     * This matches the current mod design: the upstream/input side has extra pseudo-head over
     * the downstream/output side.
     */
    public static int upstreamBoostForCrossing(Level level, BlockPos from, BlockPos pumpPos, BlockPos to) {
        int boost = boost(level, pumpPos);
        if (boost == 0) return 0;
        return isOutputNeighbor(level, pumpPos, from) && isInputNeighbor(level, pumpPos, to) ? boost : 0;
    }
}
