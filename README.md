# Create: Under Pressure

Current dev version: `0.1.2`

A Create addon that adds fluid pressure to Create's existing pipe and fluid systems.

The goal is to make height, fluid type, and flow direction matter without replacing Create's pipes. Tanks, reservoirs, outlets, and machines should be able to interact through pressure instead of relying only on pumps.

This is being built for:

- Minecraft `1.21.1`
- NeoForge `21.1.227`
- Create `6.0.10`
- Java `21`

## Version notes

### `0.1.2`

Head-graph pressure pass:

- Replaced the single best-source route picker with a graph-style head solver.
- Pipe networks now evaluate all valid higher-head endpoints against lower-head receivers instead of stopping at one selected source.
- Removed the old blocker/ownership path logic that caused larger multi-tank networks to repeatedly report `GRAPH blocked`.
- Added simple pipe resistance based on route length so longer paths apply less pressure than short direct paths.
- Tank-to-tank pressure now keeps most of its head force instead of being heavily reduced.
- Debug output now reports `GRAPH settle` and per-route head/conductance/pressure details.

### `0.1.1`

Development/debug cleanup baseline:

- Added improved debug-stick logging sessions.
- Debug logs now write to `run/logs/create-under-pressure/`.
- Right-clicking the debug stick extends logging by 10 seconds.
- Shift-right-clicking the debug stick stops logging.
- Debug stick gets temporary enchantment glint while logging.
- Removed the direct tank-equalizer experiment that caused bucket-sized transfers.
- Kept pressure-service cleanup, tank surface math improvements, and fluid compatibility checks.

### `0.1.0`

Initial scaffold and early pressure experiments.

## Planned features

### Head pressure

Fluid above a pipe, outlet, or turbine can create pressure below it.

A tall tank should feel different from a tank sitting on the floor. Same fluid, more height, more push.

### Head Turbine

The main planned block is the **Head Turbine**.

Think of it like a hose pulley in reverse:

- a hose pulley uses rotation to move fluid
- a head turbine uses moving fluid to make rotation

Put fluid above it, give that fluid somewhere to go, and the turbine generates Create rotational power.

### Water vs lava

Water and lava should not feel like copy-pasted fluids.

Planned behavior:

- **Water**: faster RPM, lower stress capacity
- **Lava**: slower RPM, higher stress capacity

So water is better for speed, lava is better for heavier machines.

### Source block behavior

For world fluids, the turbine should behave a bit like an inverse hose pulley.

Small pools should get drained. Big enough bodies should count as renewable, using Create/Minecraft's infinite-fluid behavior where possible.

That means a tiny floating puddle should not become a free infinite power plant, but a real reservoir should work.

### Overstress stops flow

If the turbine is connected to an overstressed kinetic network, the turbine should stop and the fluid should stop moving.

No free fluid transfer through a jammed machine.

## What this mod is not trying to do

At least for now, this is not trying to add a whole new pipe system.

Create already has pipes. The goal is to make pressure interact with Create's existing fluid machinery, not reinvent everything from scratch.

Also probably not v1 stuff:

- pipe bursting
- full fluid simulation per pipe segment
- custom pressure pipe blocks
- infinite turbine chains
- complicated real-world fluid physics

## Development plan

First goal: get a simple Head Turbine working in a tiny test setup.

After that:

1. Add pressure math.
2. Detect valid upstream fluid sources.
3. Consume source blocks when appropriate.
4. Generate RPM/stress based on fluid type and head height.
5. Hook into Create's pipe/fluid behavior carefully.

Mixins will probably be needed, but they should be small and focused. The mod should use Create's systems where it can and only patch internals where it has to.

## Status

Early development. Pressure experiments are playable in dev, but behavior is still being refined.
