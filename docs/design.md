# Hydraulic Runtime Design

## Goal

Add gameplay-readable hydraulic head to Create's existing fluid system without replacing Create's pipes or rebuilding its transfer engine.

The core boundary is:

- Under Pressure owns hydraulic topology, head reachability, direction, and route selection.
- Create owns fluid simulation/execution, endpoint capabilities, branching, pipe flow state, animation, particles, and hose-pulley pool behavior.

## Current milestone

Stabilize passive hydraulic movement through tanks, world endpoints, generic fluid handlers, and Create pumps.

The Head Turbine is downstream of this work. It should consume a trustworthy route and measured flow; it should not become another execution path while the base hydraulic runtime is still being corrected.

## Graph model

### Nodes

The graph uses physical connection points rather than collapsing whole blocks into one abstract endpoint.

- individual pipe faces
- individual tank contacts
- world open ends
- generic fluid-handler contacts
- pump input and output sides
- future valve/gate sides

### Edges

- Normal pipe internal connection: bidirectional, zero head gain.
- Adjacent pipe connection: bidirectional, zero head gain.
- Powered pump input → output: directional head gain.
- Pump output → input: allowed with no gain unless later made one-way by design.
- Future one-way gate: directed edge.
- Future valve: edge with a throughput multiplier.

Tanks join nearby pipe components into one planning domain, but fluid still enters and exits through real Create tank capabilities. The planner must never directly teleport inventory between tank contacts.

## Tank contacts

Every physical pipe-to-tank connection is a separate hydraulic port.

For each contact the planner records:

- tank controller
- pipe and face
- actual fluid surface
- contact cutoff elevation
- total stored amount
- amount reachable above that cutoff
- fluid identity

Rules:

- Fluid below a source contact's cutoff is unreachable from that contact.
- A tank can receive only from head above its current surface/contact requirement.
- Different-height contacts on the same tank remain distinct.
- Equalization stops at the shared waterline or the source cutoff, whichever is reached first.
- A tank may receive on one contact and provide from another only through Create's real handler behavior.

## Sources and head

- Tank source: actual fluid-surface elevation.
- World source: fluid-surface elevation at the selected open end.
- Generic handler: contact elevation unless a more specific adapter supplies a surface model.
- Pump: adds `round(abs(RPM) / 8)` blocks of head from input to output, capped at 32.

A route is eligible only when delivered head exceeds the receiving requirement. Routes cannot climb above their available head.

This is a maximum-reachable-head gameplay solver, not a full pressure simulation.

## Throughput

Head determines eligibility, direction, and route priority.

Base throughput is `128 mB/t`.

Create pressure projection uses the amount the selected action can actually move, up to that cap. Pipe length and bends may be deterministic tie-breakers but do not reduce flow. Later valves may deliberately reduce throughput.

## Route selection

Selection order should remain stable and explainable:

1. Continue a still-valid recent lease.
2. Require executable source supply and destination demand.
3. Prefer lower receiving head.
4. Prefer stronger delivered-head margin where needed.
5. Use route length and coordinates only as deterministic tie-breakers.

Leases are short-lived. A cached route must not survive after its plan expires and influence a later unrelated scan.

## Create pressure projection

One runtime owns:

```text
observe pipes
→ scan each network once
→ build one plan
→ project selected pressure once
→ cache and log that same plan
```

The runtime projects selected routes into Create pipe connections. It must not run a second direct inventory/world executor for the same action.

Create then owns:

- simulation before execution
- drain/fill capability calls
- fluid compatibility
- branch distribution
- pipe flow continuity
- collisions
- particles and rendered flow state

## World endpoints

An active world endpoint uses a persistent adapter built from Create's own hose-pulley classes:

```text
Create FluidNetwork
→ OpenEndedPipe
→ Under Pressure endpoint adapter
→ persistent HosePulleyFluidHandler
→ Create FluidFillingBehaviour / FluidDrainingBehaviour
```

Requirements:

- key context to the physical open end
- use the actual open-end position as the root
- persist context between ticks
- tick behavior once per game tick
- respect Create's configured infinite-fluid threshold
- use Create's counterpart behavior to prevent immediate reversal
- do not manually search for substitute roots
- do not fast-forward dozens of pseudo-ticks
- do not drain before the output can accept the transaction

## Mixins

Mixins should remain narrow:

- observe `FluidTransportBehaviour.tick`
- expose exact pressure storage when no public setter exists
- replace an active `OpenEndedPipe` handler with the hose adapter

Avoid broad method replacement and gameplay logic inside mixins.

## Deferred turbine design

Once the hydraulic acceptance tests pass, a turbine can become a route component that:

- requires actual selected flow
- generates rotation from fluid type and available head
- consumes head so chained turbines cannot duplicate power
- stops fluid when its kinetic network is overstressed

Until then, turbine code would hide hydraulic bugs behind another system and is intentionally out of scope.

## Non-goals

- custom replacement pipes
- full computational fluid dynamics
- pipe bursting in the initial release
- arbitrary resistance on every segment
- duplicate direct executors alongside Create's fluid network
- infinite turbine chains
