# Create: Under Pressure

Current dev version: `0.2.0-dev`

A Create addon built around useful fluid head: elevated fluid can drive machinery, with height, fluid type, available flow, and load all affecting the result.

Built for:

- Minecraft `1.21.1`
- NeoForge `21.1.227`
- Create `6.0.10`
- Java `21`

## Product goal

The core feature is the **Head Turbine**: a Create-style kinetic generator powered by fluid moving from a higher reservoir toward a lower destination.

The turbine should make the setup visible and understandable in-world:

- more vertical head produces more useful output
- available flow limits output
- water favors speed
- lava favors stress capacity
- an overstressed kinetic network stops both rotation and fluid consumption
- a turbine consumes usable head so chains cannot duplicate free power

Create's existing pipes and fluid handlers remain the transport layer. Integration code should stay small and isolated.

## First playable milestone

The first complete test setup is deliberately narrow:

1. A Create fluid tank sits above the turbine.
2. The turbine has a valid lower fluid destination.
3. Fluid passes through the turbine.
4. The turbine produces RPM and stress capacity from head and flow.
5. Overstress stops transfer.

The first pass supports tank-fed water and lava. World source blocks, infinite fluid bodies, passive tank balancing, valves, and general gravity-fed pipe networks are deferred until this loop works reliably.

## Current implementation

The repo now contains a standalone, unit-tested pressure/output model:

- `HeadPressureModel` calculates usable head, flow factor, RPM, and stress capacity.
- `TurbineProfile` keeps balance values independent from Minecraft and Create internals.
- provisional water and lava profiles establish the intended speed/stress tradeoff.

No passive pressure is currently injected into Create pipe ticks.

## Why the earlier pressure graph was removed

The first prototype tried to solve general pipe pressure before the turbine existed. That created several structural problems:

- every pipe repeatedly scanned the full connected network
- pressure was added to Create's existing value on each scan, allowing it to grow indefinitely
- tank cleanup moved fluid directly between handlers and bypassed the visible pipe route
- world-source behavior appeared before source consumption and infinite-body rules existed
- most development effort was going into a global solver rather than the first player-facing block

The prototype remains available in Git history, but it is no longer active runtime code.

## Next implementation steps

1. Register the Head Turbine block, block entity, item, and kinetic behavior.
2. Detect one upstream tank surface and one valid lower destination.
3. Use `HeadPressureModel` to calculate the operating point.
4. Move a bounded amount of fluid through normal NeoForge fluid capabilities.
5. Couple fluid movement to kinetic load and stop transfer while overstressed.
6. Add in-game diagnostics and a small Ponder scene.
7. Move provisional balance values into server config.

## Deferred systems

These may return after the Head Turbine MVP is stable:

- passive gravity-fed pipe pressure
- world-fluid source consumption
- Create-compatible infinite reservoir detection
- tanks as pass-through reservoir nodes
- one-way valves
- inline turbines and pressure loss
- multiblock waterfall turbines

See `docs/design.md` for the implementation boundaries and `docs/pressure-graph-notes.md` for lessons from the retired graph prototype.
