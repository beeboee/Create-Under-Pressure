package com.beeboee.createunderpressure.pressure;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Passive data model for the eventual single hydraulic planner.
 *
 * This is intentionally not wired to execution yet. The current diagnostic
 * planner can keep evolving while these records become the shared contract for
 * scanner -> planner -> executor -> visuals.
 */
public record HydraulicPlan(
        BlockPos owner,
        int pipeCount,
        List<Port> ports,
        List<Action> actions,
        List<RejectedAction> rejectedActions) {

    public enum PortType {
        TANK,
        WORLD,
        GENERIC_HANDLER
    }

    public enum ActionType {
        TANK_TO_TANK,
        TANK_TO_WORLD,
        WORLD_TO_TANK,
        WORLD_TO_WORLD,
        GENERIC_TRANSFER
    }

    public enum RejectReason {
        NONE,
        HEAD_BELOW_DEADBAND,
        INCOMPATIBLE_FLUID,
        NO_CAPACITY,
        ACTION_LIMIT,
        WORLD_ACTION_LIMIT,
        RESERVED_PORT,
        WORLD_BUCKET_REQUIRED,
        WORLD_SOURCE_BELOW_BUCKET,
        WORLD_OUTPUT_SOURCE_BELOW_BUCKET,
        WORLD_OUTPUT_NOT_EMPTY,
        EXECUTOR_REJECTED
    }

    public record Port(
            String id,
            PortType type,
            BlockPos owner,
            BlockPos pipe,
            Direction face,
            double head,
            int amountMb,
            int capacityMb,
            String fluid,
            int contacts) {}

    public record Route(
            Port source,
            Port sink,
            double deltaHead,
            int routeLength,
            int bends,
            double resistance,
            int flowEstimateMb,
            boolean leased) {}

    public record Action(
            ActionType type,
            Route route,
            int amountMb,
            String reservationKey) {}

    public record RejectedAction(
            ActionType type,
            Route route,
            int amountMb,
            RejectReason reason) {}
}
