# Create: Under Pressure

A Create addon for Minecraft 1.21.1 / NeoForge that adds gravity-fed head pressure and fluid-driven kinetic generation.

## Target

- Minecraft: `1.21.1`
- NeoForge: `21.1.227`
- Create: `6.0.10`
- Java: `21`

## Core idea

Create already has good pipe mechanics. This mod should not replace Create pipes unless there is no practical alternative.

Instead, the mod adds pressure-aware behavior around existing Create fluid infrastructure:

- tanks or source fluids above an outlet/turbine create head pressure
- flowing fluids can generate rotational power through a turbine
- fluid movement stops when the connected kinetic network is overstressed
- water is faster but weaker
- lava is slower but stronger

## Main block: Head Turbine

The Head Turbine acts like an inverse hose pulley.

- Hose pulley: rotation moves fluid.
- Head turbine: fluid movement creates rotation.

The turbine looks upstream for usable pressure, consumes fluid when appropriate, and outputs Create rotational power.

For world fluids:

- below the infinite-fluid-body threshold, source blocks are consumed upstream
- above the infinite-fluid-body threshold, the fluid body behaves as renewable
- output is still limited by head height, fluid type, and config

## Mixin policy

Mixins are allowed and probably necessary, but they must stay narrow.

Do not scatter Mixins through gameplay logic.

Use this shape instead:

```text
our turbine/block logic
  -> pressure service
    -> Create integration adapter
      -> small Mixins/accessors where Create has no public API
```

That gives the mod a stable internal design even if Create internals move later.

## Early scope

1. Compileable NeoForge/Create scaffold.
2. Head Turbine registration and placeholder block entity.
3. Pressure math separated from Create internals.
4. Create integration adapter.
5. Narrow Mixins/accessors for pipe/hose-pulley/fluid-network internals.
6. Real turbine behavior.

## Avoid early

- new custom pipe network
- full pressure simulation per pipe segment
- pipe bursting
- infinite turbine chains
- broad invasive Mixins
