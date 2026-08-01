# Deferred Pressure Graph

This document records lessons from the retired passive-pressure prototype. It is design input for a later system, not an implementation plan for the Head Turbine MVP.

## Why the prototype was retired

The prototype ran from every `FluidTransportBehaviour` tick and scanned the connected network before deciding which pipe owned the update.

That approach had four critical problems:

1. **Pressure accumulation**
   - Create's `PipeConnection.addPressure(...)` adds to the stored value.
   - Reapplying the same graph pressure every few ticks caused unbounded growth.
   - The mod could not safely wipe only its own contribution without also disturbing pump pressure.

2. **Scaling**
   - Every pipe performed a network scan.
   - Ownership was checked after scanning.
   - A network of `n` pipes therefore approached `n` full graph walks per update interval.

3. **Split ownership**
   - Pipe flow, tank balancing, source selection, and world-fluid behavior were mixed together.
   - Direct tank cleanup moved fluid outside the visible pipe route.
   - Pressure, fluid identity, transfer, and source consumption did not share one authoritative transaction.

4. **Wrong development order**
   - The global solver grew before the Head Turbine block existed.
   - The project had no complete player-facing loop to validate whether the pressure model was fun or understandable.

The code remains recoverable from Git history if useful pieces need to be referenced later.

## Requirements before passive pressure returns

A future graph service needs explicit concepts instead of inferred endpoint booleans.

### Nodes

- finite reservoir
- infinite reservoir
- world source body
- fluid handler source/sink
- open outlet
- turbine
- bulk mover

Each node must expose fluid identity, available amount, surface/head, transfer limits, and whether it may provide or receive.

### Edges

- pipe connection
- directionality
- flow resistance or capacity
- valve state
- pressure loss

### Graph lifecycle

The graph must be cached per connected component and invalidated by topology or endpoint changes. It should not be rebuilt from every pipe tick.

A single component owner or level service should perform updates. Component identity must remain stable enough to prevent duplicate work.

### Pressure budget

Pressure needs an owned budget that can be consumed by turbines and losses. Repeated application must replace the mod's previous contribution rather than add another copy.

If Create does not expose a safe way to own or replace a pressure contribution, the later system should use a dedicated transfer scheduler instead of mutating Create's pump-pressure fields.

### Fluid transaction

Every update must couple:

1. source selection
2. route selection
3. simulated drain/fill
4. pressure/head consumption
5. actual transfer
6. turbine output

No source block or tank amount should change unless the corresponding destination accepted the same fluid amount.

### World fluids

Finite world bodies need source-block consumption. Infinite bodies need Create-compatible threshold detection. Fluid identity and body ownership must be known before pressure is applied.

## Safe sequence for revisiting the graph

1. Finish the tank-fed Head Turbine MVP.
2. Add a dedicated component cache with invalidation tests.
3. Implement finite reservoir-to-outlet transfer without turbines.
4. Add explicit pressure budgeting.
5. Add tanks as reservoir nodes.
6. Add directional edges.
7. Add world bodies and infinite-source rules last.

The later graph should extend a proven turbine transfer model rather than replace it.
