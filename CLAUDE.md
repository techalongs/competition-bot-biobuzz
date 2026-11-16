# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FTC (FIRST Tech Challenge) robot code for the 2026–2027 **BIOBUZZ** season, built on the official FTC SDK 12.0 (`FtcRobotController` fork). The output is an Android APK that runs on a REV Control Hub / Robot Controller phone.

- **All team code lives in `TeamCode/`** (`org.firstinspires.ftc.teamcode`). Treat `FtcRobotController/` as upstream SDK — don't edit it. Its `external/samples/` folder is a useful reference for SDK APIs (vision, IMU, sensors).
- Java source/target compatibility is **Java 8** (`build.common.gradle`) — no `var`, records, switch expressions, or Java 9+ APIs. The Control Hub runs Android 7.1 (minSdk 24) without desugaring, so `java.time` and Java 9+ library calls (`List.of`, `Map.of`, …) compile but crash on the robot.
- Hardware names passed to constructors (e.g. `"frontLeft"`, `"imu"`, `"flywheel"`, `"camera"`) must match the robot configuration on the Control Hub.

## Build & deploy

```bash
./gradlew :TeamCode:assembleDebug     # compile/build the APK
./gradlew :TeamCode:deploySloth       # hot-deploy TeamCode to a connected robot via Sloth (no full APK reinstall)
./gradlew :TeamCode:removeSlothRemote :TeamCode:installDebug  # full install (remove Sloth-loaded code first)
```

Android Studio run configurations in `.run/`:
- **Sloth-Run** — runs `:TeamCode:deploySloth` (fast iteration).
- **TeamCode-No-Sloth** — normal Android app install; runs `removeSlothRemote` first.

Sloth hot-reloads only classes under `org.firstinspires.ftc.teamcode`. After changing dependencies or anything outside that package, do a full install instead.

Deploy and install tasks restart the Robot Controller app and can make the robot move. Don't run them unless explicitly asked. `assembleDebug` is always safe.

The robot code has no unit tests or lint configuration. A green build only proves the code compiles; behavior is verified on the robot. (The helper scripts under `.claude/skills/` have their own offline tests; see each skill's `SKILL.md`.)

## Dependencies

- FTC SDK artifacts and Pedro Pathing are declared in `build.dependencies.gradle`; SolversLib, Sloth, and Panels in `TeamCode/build.gradle`.
- Extra Maven repo: `https://repo.dairy.foundation/releases` (Sloth/Panels/Dairy).
- **SolversLib** (`com.seattlesolvers.solverslib`, FTCLib fork) provides the command framework (`CommandScheduler`, `InstantCommand`, `ConditionalCommand`), `GamepadEx`, `MotorEx`, `MecanumDrive`, and `GyroEx`.
- **Pedro Pathing** (`com.pedropathing`) for path following/autonomous; **Sloth** (`dev.frozenmilk`) for hot code loading; **Panels** (`com.bylazar`) for dashboard telemetry.

## Architecture

```
opmodes/  (TeleOp/Auto OpModes)  ──>  Robot  ──>  subsystems/  ──>  hardware/
```

- **`Robot`** is the facade that OpModes talk to. It constructs every subsystem with hardware-map names and exposes robot actions as SolversLib `Command`s (`startIntake()`, `increaseLauncherSpeed()`, …) plus state queries (`isIntakeActive()`) and a `getTelemetry()` that appends subsystem data.
- **`subsystems/`** are plain classes (not SolversLib `SubsystemBase`) wrapping `MotorEx` devices: `Drivetrain` (mecanum, robot- or field-centric via `DriveState`), `Intake`, `Launcher` (flywheel with a stepped power level plus a camera).
- **`hardware/`** contains custom device wrappers: `NewIMU` (a `GyroEx` over the SDK `IMU`, heading in degrees with offset/invert) and `LogitechCamera` (`VisionPortal` + `AprilTagProcessor` using the current-game tag library; its constructor busy-waits until the camera is streaming).
- **OpModes** (e.g. `OneController`) build a `Robot`, bind gamepad buttons to `Robot` commands with `GamepadEx` in `init()`, and in `loop()` must call `CommandScheduler.getInstance().run()` and `driver.readButtons()` before driving. Controls use PS4 button names (`SQUARE`, `TRIANGLE`, `CIRCLE`, `TOUCHPAD`).
- **`pedro/`** is Pedro Pathing **3** setup (a rewrite; Pedro 2 APIs such as `BezierLine`, `PathChain`, and `followPath` no longer exist): `Constants.create(HardwareMap)` should return the configured `Follower` (currently a stub returning `null`), and `pedro/procedures/` holds Pedro's auto-tuning procedures (drivetrain, localizer — Pinpoint, OTOS, OctoQuad, two/three-wheel — and Foresight tuners, plus a `Tests` procedure). These are largely library-provided code; modify carefully. `pedro/Tuning.java` is an empty placeholder for tuner OpModes.

## Project subagents (`.claude/agents/`)

- **`ftc-java-expert`**: writes, reviews, and debugs robot code; also handles Gradle and adb. It reads this file first and follows it over its own prompt, so keep the architecture and build notes here accurate.
- **`ftc-rules-expert`**: answers Competition Manual, scoring, legality, and strategy questions with rule citations. It is read-only and checks the local copies in `doc/ftc-rules/` before the web. Get rule-dependent values (point values, time limits, field coordinates, legal hardware) from it instead of from memory. When one goes into code, make it a named constant with a comment citing the rule.
- **`cad-drafter`**: builds parametric CAD and engineering drawings. If it returns `STATUS: NEEDS_INPUT`, resume the same agent with `SendMessage`, because a new instance loses its context.

## Project skills (`.claude/skills/`)

- **`robot-log-diagnosis`**: pulls robot logs over adb and traces a crash, hang, disconnect, brownout, or slow loop to the line of team code. Single-file Java scripts run with `java`.
- **`mechanism-calc`**: gear ratios, flywheel speed and range, motor torque and current margin, and encoder velocity setpoints, from a tested calculator instead of mental arithmetic. Single-file Java run with `java`.
- **`ftc-docs-refresh`**: refreshes the local copies of official FTC documents in `doc/ftc-rules/` (the Team Q&A and the Competition Manual). The only team tool written in Python: the Q&A script needs Python 3.9+, the manual script 3.13+, standard library only. Run it after each Thursday Team Update or when `ftc-rules-expert` reports a copy is missing or behind.

## Rules documents (`doc/ftc-rules/`)

- `ftc-qa.md`: every official Q&A question and answer, with links.
- `manual/`: the Competition Manual as one Markdown file per section plus `images/`; `manual/README.md` gives the manual version (for example TU09) and the section index.
- `competition-manual-<version>.pdf`: the official PDF, kept out of git by `.gitignore`. It outranks the Markdown copy.

These files are generated by `ftc-docs-refresh`. Don't edit them by hand (the next refresh replaces them, figures included); commit them after a refresh so the team and agents share the same copies, and use `git diff` to see what a Team Update changed.
