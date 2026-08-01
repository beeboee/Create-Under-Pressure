# Deferred: Passive In-Pipe Pressure

Passive gravity-fed pressure through arbitrary Create pipe networks is outside the first Head Turbine milestone.

## Intended rule

A higher fluid surface may drive fluid toward a genuinely lower destination. A branch that rises beyond the available head must stop carrying that pressure.

```text
higher reservoir -> lower pipe route -> lower destination: valid
higher reservoir -> route climbs above available head: blocked
```

## Why implementation is deferred

Create's pressure values are additive and shared with mechanical pumps. A mod that periodically calls `addPressure(...)` needs a safe ownership and replacement strategy; repeatedly adding the same value is incorrect.

A per-pipe mixin also scales badly because every pipe can trigger another graph walk. General passive pressure needs a cached connected-component service with explicit invalidation and one update owner.

## Requirements

Before this feature is implemented, the graph design must define:

- source and destination fluid identity
- finite amount and transfer limits
- source surface height
- route elevation limits
- branch capacity and direction
- component ownership and invalidation
- pressure contribution ownership
- turbine pressure/head consumption
- world-source consumption and infinite-body rules

## Relationship to the turbine

The Head Turbine MVP uses a local, explicit source-to-turbine-to-destination transaction. That implementation should establish the transfer, load, and head-consumption rules that a later passive graph can reuse.

Do not reintroduce passive pressure by injecting a full network scan into every `FluidTransportBehaviour.tick()` call.
