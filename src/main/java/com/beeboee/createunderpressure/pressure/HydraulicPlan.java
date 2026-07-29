package com.beeboee.createunderpressure.pressure;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Immutable output of the hydraulic scanner and planner.
 *
 * Ports represent physical contacts, not whole storage blocks. Routes retain the
 * exact Create pipe faces that must be pressurised so execution and rendering are
 * both owned by Create's native fluid network.
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
        NO_ROUTE,
        ROUTE_ABOVE_HEAD,
        ACTION_LIMIT,
        WORLD_ACTION_LIMIT,
        RESERVED_PORT,
        RESERVED_ROUTE,
        WORLD_BUCKET_REQUIRED,
        WORLD_SOURCE_BELOW_BUCKET,
        WORLD_OUTPUT_SOURCE_BELOW_BUCKET,
        WORLD_OUTPUT_NOT_EMPTY,
        EXECUTOR_REJECTED
    }

    /**
     * amountMb is the amount currently reachable from this physical contact.
     * storedMb is the complete storage amount, including fluid below a raised
     * outlet that must remain inaccessible.
     */
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
            int contacts,
            double cutoffHead,
            int storedMb) {}

    /** One exact Create pipe connection used by a selected route. */
    public record ConnectionUse(
            BlockPos pipe,
            Direction face,
            boolean inbound) {}

    public record Route(
            Port source,
            Port sink,
            double deltaHead,
            int routeLength,
            int bends,
            double resistance,
            int flowEstimateMb,
            boolean leased,
            double deliveredHead,
            int pumpBoost,
            List<ConnectionUse> connections) {}

    /** amountMb is the desired transfer for the next game tick, capped at 128. */
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
