package com.beeboee.createunderpressure.visual;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Shared bridge between simulation services and the world exchange visual layer.
 *
 * Sim code publishes what actually moved. The visual layer owns particles, Create
 * flow hints, and linger behavior.
 */
public final class WorldExchangeVisualEvents {
    private WorldExchangeVisualEvents() {}

    private static final int DEFAULT_LINGER_TICKS = 20;
    private static final Map<Level, Map<Key, VisualEvent>> EVENTS = new WeakHashMap<>();

    public static void publish(Level level, BlockPos pipe, Direction face, Action action, FluidStack fluid) {
        publish(level, pipe, face, action, fluid, DEFAULT_LINGER_TICKS);
    }

    public static void publish(Level level, BlockPos pipe, Direction face, Action action, FluidStack fluid, int lingerTicks) {
        if (level == null || level.isClientSide || pipe == null || face == null || action == null || fluid == null || fluid.isEmpty()) return;
        EVENTS
                .computeIfAbsent(level, $ -> new HashMap<>())
                .put(new Key(pipe, face), new VisualEvent(action, fluid.copy(), level.getGameTime() + Math.max(1, lingerTicks)));
    }

    public static VisualEvent visualFor(Level level, BlockPos pipe, Direction face) {
        Map<Key, VisualEvent> map = EVENTS.get(level);
        if (map == null) return null;
        Key key = new Key(pipe, face);
        VisualEvent event = map.get(key);
        if (event == null) return null;
        if (level.getGameTime() <= event.untilGameTime()) return event;
        map.remove(key);
        return null;
    }

    public static void prune(Level level) {
        Map<Key, VisualEvent> map = EVENTS.get(level);
        if (map == null) return;
        long now = level.getGameTime();
        for (Iterator<Map.Entry<Key, VisualEvent>> it = map.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().untilGameTime() < now) it.remove();
        }
    }

    public enum Action {
        INTAKE,
        OUTPUT
    }

    private record Key(BlockPos pipe, Direction face) {}
    public record VisualEvent(Action action, FluidStack fluid, long untilGameTime) {}
}
