# In-Pipe Pressure

This is the first pressure system to build.

World-fluid pressure can come later. For now, the mod should make Create tanks pressure nearby Create pipe networks.

## Rule

A Create fluid tank pressurizes connected pipe networks below the tank.

If a pipe branch rises back up to the Y level of the tank's bottom layer, pressure stops there. The tank should not behave like a pump that can lift fluid back up to its own base height or higher.

In plain terms:

```text
Tank above the pipe network: pressure works.
Pipe branch climbs back to tank-bottom Y: pressure stops.
Pipe branch stays below tank-bottom Y: pressure can keep flowing.
```

## Why this rule

This keeps the feature easy to understand and avoids making tanks into free all-direction pumps.

The tank acts like head pressure, not magic powered pumping.

## Create hook plan

Create already has pipe pressure logic.

The important existing pieces are:

- `FluidTransportBehaviour.addPressure(...)`
- `FluidTransportBehaviour.wipePressure()`
- `FluidPropagator.getPipe(...)`
- `FluidPropagator.getPipeConnections(...)`
- the pump's graph-walking pressure distribution logic

So the first implementation should not replace Create pipes.

Instead:

1. Hook Create tank ticks.
2. When a controller tank has fluid, find adjacent pipe networks.
3. Walk the pipe graph outward from the tank.
4. Stop walking any branch that reaches the tank-bottom Y level.
5. Apply Create pipe pressure to valid pipe faces.
6. Let Create's existing pipe flow code do the actual transfer.

## First approximation

Pressure strength can start simple:

```text
pressure = min(maxPressure, tankBottomY - pipeY)
```

Later this can include:

- actual fluid surface height
- tank fill percentage
- fluid type
- config multipliers
- turbine load

## Important limitation

This is intentionally in-pipe only.

It does not yet pull from world source blocks, infinite bodies, oceans, reservoirs, or hose-pulley-style fluid bodies.
