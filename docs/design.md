# Design Notes

## Goal

Add useful fluid head to Create through player-facing machinery, beginning with a Head Turbine.

The mod should feel native to Create: visible contraptions, understandable mechanical rules, normal fluid handlers, kinetic load, and limited compatibility code.

## MVP boundary

The first playable system supports exactly this loop:

```text
Create tank above
      |
      | existing pipe / fluid capability
      v
Head Turbine ----> Create kinetic network
      |
      v
valid lower destination
```

The turbine runs only when all of the following are true:

- the upstream tank contains a supported fluid
- the fluid surface is above the turbine
- a valid lower destination can accept fluid
- a bounded transfer can actually occur
- the kinetic network is not overstressed

Tank-fed water and lava are enough for the first milestone.

## Gameplay model

### Head

```text
availableHead = max(0, sourceSurfaceY - turbineY)
```

A minimum head creates a dead zone. Output clamps at a configurable maximum head.

### Flow

Available transfer rate limits both RPM and stress capacity. A turbine cannot claim output from fluid it did not move.

### Fluid profiles

Each supported fluid provides a profile containing:

- minimum and maximum useful head
- design flow rate
- maximum RPM
- maximum stress capacity

Provisional defaults establish this relationship:

```text
Water: higher RPM, lower stress capacity
Lava:  lower RPM, higher stress capacity
```

The values belong in server config once the block works end to end.

### Load and transfer

The block entity should calculate a proposed operating point, simulate the downstream fill, and execute only the amount that can actually move.

When the kinetic network is overstressed:

- generated rotation stops
- fluid extraction stops
- fluid insertion stops

The same fluid movement and head cannot be credited to multiple turbines. The first implementation should enforce a simple single-turbine route before supporting chains.

## Architecture

```text
pressure/
  pure math, profiles, operating-point records

content/head_turbine/
  block, block entity, registration, kinetic behavior

integration/create/
  small adapters for Create tanks, kinetic networks, and presentation

mixin/
  only narrowly justified accessors or injections
```

### Pressure layer

Requirements:

- no Minecraft classes
- no Create classes
- deterministic
- unit tested
- no world mutation

### Content layer

Owns:

- block state
- server tick lifecycle
- fluid capability interaction
- kinetic source behavior
- sync and persistence
- player diagnostics

### Create integration layer

Owns any assumptions about Create internals. A Create update should primarily require repairs here.

### Mixins

A mixin needs a named missing API and a precise injection point. Avoid ticking every Create pipe, replacing transfer methods, or putting graph/gameplay logic inside injections.

## Transfer sequence

A turbine server tick should follow this order:

1. Resolve the upstream source and downstream destination.
2. Read source fluid and source surface height.
3. Calculate the proposed operating point.
4. Derive the proposed transfer amount from the flow factor.
5. Simulate source drain and destination fill.
6. Check kinetic load/overstress state.
7. Execute the matched drain and fill amount.
8. Publish kinetic output based on the fluid that actually moved.
9. Sync only when state changes meaningfully.

No direct tank-to-tank mutation is allowed outside this explicit transfer path.

## Validation cases for the MVP

- source surface at or below turbine: stopped
- source above turbine with no destination: stopped
- destination full: stopped
- water and lava produce different operating points
- partial destination capacity proportionally limits output
- overstress prevents any fluid loss
- chunk unload/reload preserves state without duplicating fluid
- two turbines cannot both claim the same transfer

## Deferred systems

- passive gravity-fed Create pipe networks
- arbitrary pipe graph pressure
- world source blocks
- infinite fluid body detection
- reservoir balancing across multiple tanks
- tanks as graph bridge nodes
- one-way valves
- inline turbine pressure loss
- multiblock waterfall turbines

These require explicit source, sink, edge, ownership, invalidation, and pressure-budget rules. They should not be layered onto per-pipe tick injections.
