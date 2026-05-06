# Design Notes

## Goal

Add head pressure behavior to Create fluid systems without replacing Create's pipes.

The mod should feel like an extension of Create, not a parallel fluid system.

## Core mechanic

A fluid source above a turbine or outlet provides head pressure.

Pressure can come from:

- Create fluid tanks
- world fluid source blocks
- large/infinite fluid bodies

Pressure can drive:

- a Head Turbine that generates rotational power
- later, gravity-fed outlets or passive fluid movement

## Head Turbine

The Head Turbine is the first important block.

It behaves like an inverse hose pulley:

- Create hose pulley: kinetic energy moves fluid
- Head Turbine: fluid movement creates kinetic energy

## World-fluid consumption

When drawing from world fluids:

- small upstream pools should lose source blocks
- large enough bodies should be treated as renewable
- the threshold should mirror or respect Create's infinite-fluid-body logic when possible

This avoids making a tiny elevated puddle into infinite power while still allowing large reservoirs to work.

## Kinetic output

The turbine outputs RPM and stress capacity based on:

- fluid type
- head height
- available flow
- config multipliers

Suggested defaults:

```text
Water:
- higher RPM
- lower stress capacity

Lava:
- lower RPM
- higher stress capacity
```

## Overstress behavior

If the turbine's kinetic network is overstressed:

- turbine rotation stops
- fluid movement stops
- upstream source blocks are not consumed

This prevents fluid from being wasted into a locked machine and makes overstress mechanically meaningful.

## Mixin strategy

Mixins are risky only when they become broad, fragile, or mixed directly into gameplay logic.

Acceptable Mixins:

- accessors/invokers for Create internals with no public API
- tiny injections at specific transfer decision points
- compatibility shims isolated to one package

Avoid:

- replacing whole Create methods
- duplicating Create pipe-network logic
- spreading Create-internal assumptions across block entities
- depending on obfuscated names where stable mapped names are available

Internal structure should be:

```text
content/head_turbine
  gameplay logic

pressure
  pressure math and source selection

integration/create
  Create-facing adapter classes

mixin
  tiny accessors/injections only
```

If Create internals change later, most repairs should happen inside `integration/create` and `mixin`, not across the whole mod.

## First implementation pass

1. Set up NeoForge/Create scaffold.
2. Register Head Turbine block and block entity.
3. Add config values for water/lava RPM and stress.
4. Implement standalone pressure math with tests or debug logging.
5. Add Create integration adapter.
6. Add minimal Mixins only after inspecting the exact Create 6.0.10 classes.

## Non-goals for v1

- custom pipe blocks
- pipe bursting
- full fluid pressure simulation
- arbitrary fluid dynamics
- infinite turbine chains
