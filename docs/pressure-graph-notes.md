# Pressure graph notes

Working design notes for Create: Under Pressure. This is not final API; it is a place to pin the intended behavior before the next graph rewrite.

## Core split

The mod should treat these as related but separate systems:

1. **Pipe pressure graph**
   - Moves fluid through pipes when there is a higher source and a lower destination.
   - Does not require a tank to exist.
   - Source blocks, tanks, hose-pulley-like intakes, turbines, outlets, and fluid handlers can all be graph nodes.

2. **Tank reservoir solver**
   - Handles tank-to-tank fill-level balancing.
   - Uses target fill-Y, not percent alone.
   - Connected tanks move toward the shared target at the same time.
   - Highest fill-Y tanks drain first.
   - Lowest fill-Y tanks receive first.

3. **Directional/valve graph rules**
   - Future one-way valve should be a directional edge in the pipe graph.
   - Proposed recipe idea: pipe + dried kelp = one-way valve.
   - Valves should affect pipe-only source movement, tank networks, turbines, and bulk movers.

## Pipe-only source movement

Pipes should be able to move water/lava sources without tanks.

Example:

```text
water source
   |
 pipe
   |
 pipe
   |
outlet below source
```

Rule:

```text
If a source block is connected to a pipe graph and a valid end point is below the current source location, pressure should carry through the pipes and move fluid toward that lower end.
```

This means pipe pressure needs its own ticker/trigger, not only the current tank tick hook. A tank-only tick cannot support pure pipe networks because no tank exists to start the scan.

## Tanks as reservoir nodes

Tanks should not be hard breaks in the pressure graph.

Current bad behavior:

```text
source above
   |
 pipe
   |
 tank
   |
 pipe
   |
outlet below
```

This currently behaves too much like two disconnected systems:

```text
source -> pipe -> tank

tank -> pipe -> outlet
```

Wanted behavior:

```text
source pressure enters tank
pressure can pass through tank as a reservoir node
lower outlet can be driven by the higher source/head
```

A tank should be a reservoir junction:

- It can receive from higher pressure.
- It can pass pressure onward to lower outlets.
- It still stores fluid and updates its own fill-Y.
- Its tank-to-tank balancing remains separate from general pipe pressure.

## Tank fill-Y model

Tank waterline/fill level:

```text
fillY = tankBottomY + (% full * tankHeight)
```

Where:

```text
% full = fluidAmount / capacity
```

For connected tanks:

```text
1. Collect connected tanks of the same fluid group.
2. Sum total fluid.
3. Solve the shared target fill-Y that preserves total fluid.
4. Convert target fill-Y back to target amount for each tank.
5. Move all tanks toward the target together.
6. Drain highest fill-Y surplus tanks first.
7. Fill lowest fill-Y deficit tanks first.
```

## Flow intensity

Flow rate should depend on fill-Y/head difference.

Expected feel:

```text
small fill-Y difference = slow movement
large fill-Y difference = faster movement
near target = slow final settling
```

Lava should move slower/heavier than water.

Current tuning notes:

```text
water tank cap: 100mb/update
lava tank cap: 25mb/update
water smoothing factor: 0.20
lava smoothing factor: 0.08
lava pipe pressure multiplier: 0.35
```

These are tuning values, not final constants.

## Future bulk fluid mover

A future block should act like a hose pulley for moving large bodies of water through pipes.

Intent:

- Pull from world fluid bodies.
- Push into pipe graph.
- Respect an infinite-source/world-body threshold.
- For bodies smaller than the infinite-source threshold, actually consume source blocks.
- For infinite bodies, behave more like an infinite reservoir/source.

This is likely a dedicated block, not just passive pipe behavior.

## Turbines

Two turbine directions are desired:

1. **Inline turbine**
   - Pipe component that generates stress from flowing fluid passing through it.
   - Should probably add flow resistance or consume some pressure/head.

2. **Multiblock waterfall turbine**
   - Uses flowing water/lava falling through a vertical area.
   - Higher waterfall = more stress generated.
   - Consumes source blocks from the body of water at the top of the flow when the body is smaller than the infinite-source variable for the world.
   - If the top source is considered infinite, turbine can run as long as the fall/flow remains valid.

Important design constraint:

```text
Turbines should use real flow/head, not duplicate free power from the same pressure multiple times.
```

Possible rule:

```text
A turbine consumes pressure/head from the graph. Downstream fluid can continue, but the same head should not generate infinite chained power.
```

## Next graph rewrite goal

The next pressure graph pass should support:

```text
- pipe-only source-to-lower-outlet movement
- tanks as reservoir bridge nodes
- tank smoothing kept separate
- future directional valve edges
- future turbine/head consumption hooks
- future bulk fluid mover/source-body hooks
```

Avoid bolting all of this into the current tank tick loop. The pipe graph needs a real graph service with explicit node/edge types.
