# Create: Under Pressure

A Create addon about making fluids feel heavy.

The basic idea is simple: if you build a tank or reservoir above your machinery, that height should matter. Water should be able to push downward through pipes. Lava should churn out slower, stronger power. A machine that is overstressed should stop the flow instead of letting fluid magically pass through anyway.

This is being built for:

- Minecraft `1.21.1`
- NeoForge `21.1.227`
- Create `6.0.10`
- Java `21`

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

Early planning/scaffold stage. Nothing playable yet.
