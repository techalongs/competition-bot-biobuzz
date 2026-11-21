---
name: mechanism-calc
description: Calculate FTC mechanism numbers with a tested tool instead of arithmetic in your head. Covers gear ratios and output speed, flywheel exit speed and range, and whether a motor has the torque and current margin for a lift, arm, intake, or drivetrain. Use when choosing a motor or gear ratio, sizing a flywheel, checking whether a mechanism will stall or draw too much current, or turning a wheel speed into an encoder velocity setpoint for code.
allowed-tools: Bash(java ${CLAUDE_SKILL_DIR}/scripts/MechCalc.java *)
---

# Mechanism calculator

Run `scripts/MechCalc.java` for every number. Do not work these out by hand; the tool is tested and prints its assumptions.

```
java "${CLAUDE_SKILL_DIR}/scripts/MechCalc.java" <command> [options]
```

It is one Java source file with no libraries. `java` runs it straight from source, so there is no build step and nothing to install beyond the JDK the robot code already needs (JDK 11 or later).

## If `java` is not found

Any laptop that builds the robot code has a JDK, but it is often not on the PATH. Try these in order and use the first that prints a version of 11 or later with `-version`:

1. `java`
2. `"$JAVA_HOME/bin/java"`, when `JAVA_HOME` is set
3. The JDK inside Android Studio:
   - Windows: `"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"`
   - macOS: `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"`

In PowerShell, put `&` in front of a quoted path: `& "C:\...\java.exe" ...`. If no JDK turns up, say so and stop. Do not install one, and do not fall back to hand arithmetic without saying the numbers are unchecked.

## Units are required

Every length, mass, force, and torque must carry a unit, and the tool rejects a bare number: `96mm`, `4in`, `12lb`, `5.4kg`, `20N`, `18.7kgcm`, `2.1Nm`. Speeds are in RPM and voltage in volts.

## Commands

Each example is what follows `MechCalc.java` on the command line.

| Command | Answers | Example |
|---|---|---|
| `motors` | Which motors are built in, with their published figures | `motors` |
| `gear` | Output speed, torque, encoder counts per turn, and current at a load | `gear --motor gobilda-1620 --stage 20:40 --wheel-diameter 72mm --load-torque 0.2Nm` |
| `flywheel` | Ball exit speed and spin, velocity setpoint, slowdown per shot, recovery time, range | `flywheel --motor gobilda-6000 --wheel-diameter 96mm --rpm 3200 --ball-diameter 71mm --ball-mass 30g --wheel-mass 100g --angle-deg 45 --release-height 300mm` |
| `flywheel --target-distance` | The wheel speed needed to reach a distance | `flywheel --wheel-diameter 96mm --angle-deg 50 --release-height 300mm --target-height 900mm --target-distance 3m` |
| `lift` | Hold current, share of stall torque, lift speed, travel time | `lift --motor gobilda-435 --count 2 --mass 3kg --spool-diameter 38mm --travel 700mm` |
| `arm` | Worst-case torque with the arm horizontal, hold current, swing speed | `arm --motor gobilda-60 --payload-mass 400g --length 350mm --arm-mass 250g` |
| `drivetrain` | Free speed, pushing force, whether wheels slip before motors stall, current while pushing | `drivetrain --motor gobilda-435 --mass 12kg --wheel-diameter 104mm` |

Gearing after the motor is either `--stage DRIVING:DRIVEN` by tooth count, repeated for each stage, or `--ratio R`, where a value above 1 slows the output and below 1 speeds it up. A "2:1" set can mean either direction, so ask which gear is on the motor before choosing. For a motor that is not built in, give `--free-rpm`, `--stall-torque`, and `--stall-current` from its data sheet. `--help` after any command lists every option.

## Where the inputs come from

- **Masses and dimensions:** measured on the real mechanism or taken from CAD. Ask for them. Do not estimate a mass and present the result as an answer.
- **Game piece size and mass:** from the Competition Manual through `ftc-rules-expert`. Never from recollection, and never hard-coded.
- **Motor figures:** the motor table at the top of `scripts/MechCalc.java`. Every report prints the manufacturer page its motor figures came from. Encoder counts marked "(computed)" are 28 times the gear ratio, not a published figure; confirm them on the spec sheet before they go into code.
- **Anything the report marks "(assumed)":** efficiency, friction, speed transfer. Say so when you report, and name the measurement that would replace the assumption.

## Reading the result

Each report lists inputs and assumptions, results, and checks marked `OK`, `WARN`, or `FAIL`.

| Exit code | Meaning |
|---|---|
| 0 | Calculated, and no check failed (warnings are allowed unless `--strict`) |
| 1 | Calculated, and a check failed |
| 2 | Bad arguments, such as a missing unit or an unknown motor |

Add `--json` for structured output. The checks and their limits are explained in [reference/formulas.md](reference/formulas.md), along with every formula.

Good practice with the results:

- Run any case that carries a load twice: at 12 V and again with `--voltage 11` for a tired battery.
- A `FAIL` on load means change the design: more reduction, another motor, a smaller spool, or a spring assist. Rerun to confirm the change fixes it.
- Compare options by running each one. Report them side by side with the command used for each.
- For a flywheel, the range without drag is an upper bound. Light or perforated balls fall well short. Take one measured shot, set `--transfer` to match it, and trust the calculator only after that.

## Limits

These are estimates from a simple motor model and published figures. They are good for choosing between designs and catching one that cannot work. They do not replace a test on the robot, and a result should never be reported as what the robot will do.

Whether a design is legal (motor and servo limits, size limits, launch rules) is a rules question. Hand it to `ftc-rules-expert`.

## Reporting

Give the person the command you ran, the numbers that answer their question, each assumption that was not measured, any `WARN` or `FAIL` with what it means for the design, and the one or two measurements that would firm the answer up.

## Changing the tool

The tool is written in the same Java 8 style as the robot code, so the team can read it and change it. To add a motor, add one line to the motor table and cite the source. Change it only when asked, and run the tests afterward, from this skill's folder:

```
javac -d build scripts/MechCalc.java tests/MechCalcTest.java
java -cp build MechCalcTest
```

Exit code 0 means every test passed. Delete the `build` folder when done. Always compile into a separate folder as shown. JDK 11 will not run `java MechCalc.java` while a compiled `MechCalc.class` sits in the current folder; it stops with "class found on application class path". Deleting the stray `.class` files fixes it.
