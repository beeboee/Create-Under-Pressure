# Create: Under Pressure

Current development version: `0.1.3`

A Create addon that adds hydraulic head to Create's existing fluid pipes without replacing Create's transfer engine.

The intended division of responsibility is:

- **Under Pressure** decides whether fluid can move, which direction it should move, and which route wins.
- **Create** performs the actual transfer, capability handling, branching, pipe state, animation, particles, and world-pool behavior.

Built for:

- Minecraft `1.21.1`
- NeoForge `21.1.227`
- Create `6.0.10`
- Java `21`

## Current priority

The hydraulic runtime has to be dependable before turbine work begins.

The current milestone is a stable shared planner that supports tanks, world fluids, generic handlers, and Create pumps while conserving fluid and respecting physical contact height. The Head Turbine remains planned, but it is deliberately deferred until the acceptance tests in [`docs/hydraulic-acceptance-tests.md`](docs/hydraulic-acceptance-tests.md) pass.

## Current architecture

### Contact-aware hydraulic graph

The planner scans Create's real pipe topology and models each physical connection separately.

- Every tank-pipe contact is its own hydraulic port.
- A tank outlet can only reach fluid above that contact's cutoff elevation.
- Generic fluid handlers are discovered through NeoForge capabilities.
- Pump input and output sides are directional graph edges.
- World endpoints use the exact open-pipe end as their root.

### Head solver

Head controls eligibility and direction rather than transfer speed.

- Tank source head is its actual fluid surface.
- World source head is the fluid surface at the open end.
- Powered Create pumps add `round(abs(RPM) / 8)` blocks of head from input to output, capped at 32.
- Reverse pump traversal receives no head gain.
- Routes cannot climb above their delivered head.

### Fixed Create transfer rate

Selected routes project pressure into Create at a target rate of `128 mB/t`.

Create remains responsible for simulation, execution, fluid compatibility, endpoint capabilities, branch behavior, and native pipe flow state. Pipe length and bends can break ties but do not reduce throughput.

### World-fluid adapter

When a selected route uses an open pipe end, the normal one-block open-end handler is replaced with a persistent adapter built from Create's hose-pulley filling and draining behavior.

This is intended to preserve Create's pool search, infinite-body configuration, source consumption order, and counterpart behavior instead of reproducing them in custom executors.

## Version notes

### `0.1.3`

Hydraulic runtime cutover and stabilization:

- Added one shared scan → plan → pressure-projection runtime.
- Added physical tank contact ports with cutoff-aware reachable volume.
- Changed planned throughput to a fixed `128 mB/t` instead of head- or distance-scaled flow.
- Corrected pump head gain to input → output.
- Added persistent Create hose behavior for active world endpoints.
- Prevented stale cached plans and route leases from influencing later networks indefinitely.
- Deferred the Head Turbine until the hydraulic acceptance suite passes.

### `0.1.2`

Earlier head-graph experiments. This version accumulated several manual transfer and visual systems that have since been superseded by the shared runtime.

### `0.1.1`

Debug logging and early pressure experiments.

## Required behavior before turbine work

The hydraulic runtime is ready to build on only when all of these are repeatable:

1. Uneven tanks settle without draining fluid below an outlet's cutoff.
2. World-to-tank transfer conserves every consumed source block.
3. Tank-to-world transfer never drains before the world output accepts fluid.
4. World-to-world routes do not lose fluid, duplicate fluid, or ping-pong newly placed sources.
5. Generic Create/addon fluid handlers work without custom inventory code.
6. Pumps add directional head while transfer remains capped at `128 mB/t`.
7. Branch selection remains deterministic and does not alternate routes without a hydraulic reason.
8. Native Create pipe flow and particles accurately represent the transfer that actually occurred.

## Planned later features

Once the hydraulic runtime is stable:

- Head Turbine
- water/lava-specific kinetic output
- valves and one-way gates
- turbine head consumption to prevent free chained generation
- additional configuration and compatibility work

## Non-goals

- a replacement pipe system
- full real-world computational fluid dynamics
- pipe bursting in the first release
- arbitrary per-segment pressure simulation
- free infinite turbine chains

## Development status

Active hydraulic-runtime testing. The turbine is not the current implementation milestone.
