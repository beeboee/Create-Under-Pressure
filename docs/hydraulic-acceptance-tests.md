# Hydraulic Acceptance Tests

These tests gate turbine work. A test passes only when the fluid result, route selection, Create pipe state, and debug plan agree.

Use water first. Repeat the relevant tests with lava after water behavior is stable.

## Logging

1. Type a short test label in chat or with `/say`.
2. Right-click a pipe in the tested network with a stick.
3. Right-click again to extend logging if needed.
4. Shift-right-click to stop.
5. Attach the matching file from `run/logs/create-under-pressure/` to the test note.

Each log should identify one network owner, one shared plan, selected routes, rejected routes, delivered head, contact cutoffs, and projected Create pressure.

## A. Basic tank gravity

### A1 — Higher tank to lower empty tank

Setup:

```text
full tank
   |
 pipes descending
   |
empty tank
```

Expected:

- One tank-to-tank route is selected.
- Create moves up to `128 mB/t`.
- The source surface falls and the target surface rises.
- Flow slows/stops only because supply, room, or head eligibility changes—not because route length changes.

### A2 — Equal-height tanks

Setup two identical tanks with different amounts and contacts at the same elevation.

Expected:

- Fluid moves toward a shared surface.
- Direction does not alternate every few ticks.
- The route stops inside the dead band once surfaces match.

### A3 — Lower source cannot climb

Place the source surface below the receiving tank's required contact/surface elevation.

Expected:

- No route is selected without a pump.
- No Create pressure is projected on the rejected path.
- No inventory changes occur.

## B. Uneven tank contacts

### B1 — Preserve fluid below outlet cutoff

Attach the source pipe high on a tall tank and a receiving tank below it.

Expected:

- Only the amount above the source contact cutoff is considered reachable.
- Transfer stops when the source surface reaches that cutoff.
- Fluid below the outlet remains in the source tank.

### B2 — Two contacts on one tank stay distinct

Attach one pipe low and one pipe high on the same tank.

Expected:

- Debug output lists two separate tank ports.
- The low contact remains usable after the surface falls below the high contact.
- The high contact stops providing as soon as its cutoff is reached.

### B3 — Three varied-height tanks

Connect three tanks with different heights, capacities, fill levels, and contact elevations.

Expected:

- All physical contacts appear in one planning domain.
- No tank is represented by only its first discovered contact.
- Final amounts preserve inaccessible volume and converge toward hydraulically valid surfaces.

## C. Generic handlers

### C1 — Create/addon storage as sink

Connect a tank source to a non-tank `IFluidHandler` through Create pipes.

Expected:

- The capability is discovered as a generic port.
- Create performs simulation and insertion.
- Under Pressure never directly edits the handler inventory.

### C2 — Generic handler as source

Reverse the elevation/conditions so the handler can supply a lower destination.

Expected:

- Fluid compatibility is respected.
- Actual movement remains capped at `128 mB/t`.
- Native Create flow state matches the transfer.

## D. Pumps

### D1 — Powered input to output

Put a pump in a route that otherwise cannot reach the destination.

Expected:

- Pump input is opposite its facing side.
- Pump output is its facing side.
- Head gain is `round(abs(RPM) / 8)`, capped at 32.
- The route becomes eligible only when delivered head clears the destination requirement.
- Throughput remains capped at `128 mB/t` regardless of RPM.

### D2 — Reverse crossing

Attempt flow from pump output toward pump input.

Expected:

- No head gain is applied.
- The route succeeds only if the original source head is already sufficient.

### D3 — Multiple pumps and alternate paths

Create two routes that reuse different pumps before joining.

Expected:

- The solver retains states with different used-pump sets when either can matter later.
- Head cannot be gained from the same pump twice in one route.
- Route choice remains deterministic.

## E. World intake

### E1 — Finite pool to tank

Connect an open pipe end to a small finite pool and a tank below.

Expected:

- The active endpoint uses the persistent Create hose adapter.
- Every removed source block corresponds to fluid accepted by the destination.
- Failed insertion does not consume a source.
- Regenerated/flow-updated blocks are not repeatedly counted as new conserved supply.

### E2 — Infinite body

Repeat with a body that meets Create's configured infinite-fluid threshold.

Expected:

- Create's own threshold is authoritative.
- The body remains available without custom infinite-pool logic.

### E3 — Flowing-block root

Place the open end against a flowing part of a connected pool.

Expected:

- The adapter uses the exact open-end root and Create's search behavior.
- Under Pressure does not substitute a manually discovered source root.

## F. World output

### F1 — Tank to empty world space

Put a tank source above an open output end.

Expected:

- The tank is not drained unless Create's output behavior accepts the fluid.
- Output is transactional; failed placement leaves the tank unchanged.
- Pipe flow and particles precede or accompany the world change naturally.

### F2 — Output into connected flowing fluid

Aim the output into a compatible body with nearby flowing blocks.

Expected:

- Create's filling behavior chooses the placement location.
- Under Pressure does not run a second fillability search or place a block manually.

### F3 — Blocked/incompatible output

Block the output or place an incompatible fluid there.

Expected:

- No drain occurs.
- The planner rejects or Create simulation refuses the action cleanly.
- Debug output explains the lack of executable demand.

## G. World to world

### G1 — Conserved transfer

Connect a finite upper pool to a valid lower world output.

Expected:

- No source is removed unless the destination accepts the corresponding fluid.
- Total fluid is conserved.
- There is no manual drain-then-fill path with an unrecoverable failure window.

### G2 — No ping-pong

Leave the system running after output creates or expands a pool.

Expected:

- The newly affected output is not immediately selected as the opposite intake without a real head change.
- Recent valid route leases stabilize selection but expire with the cached plan.
- Direction does not alternate due solely to endpoint update order.

## H. Branching and leases

### H1 — Two valid sinks

Create one source and two lower sinks.

Expected:

- Selection is deterministic.
- Existing valid leases are preferred.
- Lower receiving head wins before coordinate tie-breaks.
- Create handles actual branch transfer; Under Pressure does not execute both inventories manually.

### H2 — Lease invalidation

Run a selected route, then block it or change the head so it is no longer valid.

Expected:

- The stale route loses priority immediately when it is not a candidate.
- Cached route keys expire with their plan.
- Reopening the network later does not resurrect an unrelated old lease.

## I. Regression checks

Every release candidate must also confirm:

- no pressure values grow cumulatively across ticks
- no manual visual layer injects additional operational pressure
- no tank/world executor performs the same transfer as Create
- no source or destination is modified twice for one selected action
- no ordinary Create pipe network crashes when it has no valid hydraulic action
- unloaded chunks are not force-loaded by scans
- debug logging does not change route behavior

## Turbine gate

Turbine implementation can begin after sections A through I pass consistently in repeated fresh-world and reload tests. Any turbine prototype before that point must remain isolated and must not introduce another fluid-transfer path.
