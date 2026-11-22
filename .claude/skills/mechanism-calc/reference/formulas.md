# What MechCalc.java computes

The formulas behind each command, so a result can be checked by hand. Symbols use SI units: meters, kilograms, seconds, newtons, newton-meters, radians per second. `g` is 9.80665 m/s².

## Motor model

A brushed DC motor is modeled as a straight line between two published points: free speed (no torque) and stall (no speed). All motor figures are at the motor's own output shaft, after its built-in gearbox.

- Voltage scaling: free speed, stall torque, and stall current all scale by `V / 12`.
- Speed at a torque: `speed = free_speed × (1 − torque / stall_torque)`
- Current at a torque: `current = free_current + (stall_current − free_current) × torque / stall_torque`
- Mechanical power: `torque × speed`. It peaks at half of stall torque; efficiency peaks much lower.

With `n` motors, an external ratio `R` (above 1 slows the output), and efficiency `e`:

- Output free speed: `motor_free_speed / R`
- Output stall torque: `n × motor_stall_torque × R × e`
- Torque each motor carries for an output torque `T`: `T / (n × R × e)`

Efficiency defaults, used only when `--efficiency` is not given: 0.95 for each external gear, belt, or chain stage, multiplied by 0.85 for a lift (string and slide friction), 0.95 for an arm, 0.90 for a drivetrain. These are estimates. Measure and override them.

## Checks

| Check | OK | WARN | FAIL |
|---|---|---|---|
| Load as a share of stall torque | at most `--max-load-fraction` (0.33) | up to 0.5 | above 0.5 |
| Current per motor | at most 80% of `--port-limit-a` (10 A) | up to the limit | above it |
| Mechanism current against `--fuse-a` (20 A) | at most 60% | up to the fuse | above it |
| Flywheel speed as a share of free speed | at most 0.85 | up to 1.0 | above 1.0 |
| Drivetrain | wheels slip before motors stall | pushing current above the fuse | motors stall before wheels slip |

The 10 A figure is the REV hub's continuous output current per motor port, and 20 A is the fuse on the standard robot battery. The 0.33, 0.5, and 0.85 limits are rules of thumb for motors that have to keep working for a whole match as the battery sags. They are arguments so a team can set its own.

## gear

- Ratio of a stage given as `DRIVING:DRIVEN` teeth: `driven / driving`. Stages multiply.
- Surface speed of a wheel of radius `r` on the output: `output_speed × r`
- Encoder counts per output turn: `motor counts per turn × R`

## lift

- String force: `(mass × g − assist) × multiplier`, where the multiplier is the number of moving stages in a cascade lift (1 for a direct lift)
- Spool torque: `force × spool_radius`
- Lift speed: `spool_speed × spool_radius × multiplier`, with spool speed taken from the motor line at the hold torque
- The mass the motors stall at: `output_stall_torque / (spool_radius × multiplier × g)`

The speed is steady-state. The time for full travel leaves out acceleration.

## arm

- Worst-case torque, arm horizontal: `g × (payload_mass × length + arm_mass × center_of_mass_distance)`
- At an angle `θ` from horizontal the torque is that value times `cos θ`.

## drivetrain

- Free speed: `output_free_speed × wheel_radius`
- Push force at stall: `output_stall_torque / wheel_radius`
- Traction limit: `friction × mass × g`
- If stall force exceeds the traction limit, the wheels slip first. The current then is the motor-line current at `traction × wheel_radius`.
- Time to a share `s` of free speed `v`: accelerate at `min(traction, stall_force) / mass` until the motor line drops below the traction limit at `v_c = v × (1 − traction / stall_force)`, then `τ × ln((v − v_c) / (v − s × v))` with `τ = mass × v / stall_force`.

Wheel inertia, rolling drag, and weight transfer are left out.

## flywheel

Surface speed of a wheel: `wheel_speed × wheel_radius`.

- **One wheel against a fixed hood.** The ball rolls along the hood, so its center moves at half the wheel's surface speed and it leaves spinning: `exit = transfer × surface / 2`, `spin = exit / ball_radius`.
- **Two counter-rotating wheels.** `exit = transfer × (surface_1 + surface_2) / 2`, `spin = transfer × (surface_1 − surface_2) / (2 × ball_radius)`. Equal speeds give no spin.

`transfer` is 1.0 for the ideal no-slip case. Real launchers lose speed to slip and ball compression, so measure a shot and set it.

- Energy per shot: `½ × m × exit² + ½ × I_ball × spin²`, with `I_ball = k × m × r²` (`k` is 2/3 for a hollow shell, 2/5 for a solid ball)
- Flywheel inertia from mass: `½ × m × r²` for a disk, `m × r²` for a ring
- Wheel speed after a shot: `sqrt(ω² − 2 × energy / I_flywheel)`. Real losses make the drop larger.
- Recovery at full power from `ω_1` back to `ω_0`: `τ × ln((ω_free − ω_1) / (ω_free − ω_0))` with `τ = I_flywheel × ω_free / output_stall_torque`. Spin-up from rest uses `ω_1 = 0`.
- Velocity setpoint in encoder counts per second: `wheel_rpm × R / 60 × motor counts per turn`

Flight without drag, launched at speed `v` and angle `θ` from height `h_0` to a target at height `h_t`:

- Time: `(v sin θ + sqrt(v² sin² θ + 2 g (h_0 − h_t))) / g`, the point where the ball comes down through the target height
- Range: `v cos θ × time`

With `--drag-cd`, the flight is stepped numerically with drag force `½ × air_density × Cd × area × speed²` opposing motion. `--target-distance` searches for the exit speed that lands at that distance and converts it to wheel speed.

## What the model leaves out

- Motor figures are published values. Individual motors and independent dyno tests differ from them.
- Heat. A motor near stall loses torque as it warms.
- Battery internal resistance. Use `--voltage` to try a sagged battery, such as 11 V.
- Air drag unless `--drag-cd` is given, and spin lift always.
