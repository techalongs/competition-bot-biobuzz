---
name: robot-log-diagnosis
description: Diagnose a robot problem from its logs. Use when an OpMode crashed, got stuck, or stopped by itself, when the robot disconnected, lost power, or had motors cut out, when loops feel slow, or when someone provides a robotControllerLog.txt, match log, or logcat output. Pulls logs over adb, finds the failure, and maps it to the line of team code.
allowed-tools: Bash(java ${CLAUDE_SKILL_DIR}/scripts/*)
---

# Robot log diagnosis

Work through these steps in order. The two tools are single Java source files with no libraries. `java` runs them straight from source, so there is no build step and nothing to install beyond the JDK the robot code already needs (JDK 11 or later).

## If `java` is not found

Any laptop that builds the robot code has a JDK, but it is often not on the PATH. Try these in order and use the first that prints a version of 11 or later with `-version`:

1. `java`
2. `"$JAVA_HOME/bin/java"`, when `JAVA_HOME` is set
3. The JDK inside Android Studio:
   - Windows: `"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"`
   - macOS: `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"`

In PowerShell, put `&` in front of a quoted path: `& "C:\...\java.exe" ...`. If no JDK turns up, do not install one. Do the same work by hand with [reference/log-patterns.md](reference/log-patterns.md) and say that you did.

## 1. Get the facts

Ask, or take from the request: what the robot did, which OpMode was running, roughly what time, and whether the code on the robot matches the current checkout. The log has no year and survives restarts, so the time is how you find the right incident.

## 2. Get the logs

If the person gave you a log file, use it and skip to step 3.

Otherwise copy the logs off the robot. This only reads from the robot:

```
java "${CLAUDE_SKILL_DIR}/scripts/PullRobotLogs.java"
```

It finds adb, connects to `192.168.43.1:5555` if nothing is attached, and prints the folder it saved to. That folder holds `robotControllerLog.txt` and its rotated copies, `matchlogs/`, `configs/` (robot configuration XML), and `logcat-live.txt`. It saves under `~/ftc-robot-logs/` by default; keep logs out of the repo.

| Exit code | Meaning | What to do |
|---|---|---|
| 0 | Files saved | Continue |
| 2 | Bad arguments, or several devices attached | Rerun with `--serial <device>` |
| 3 | adb missing, or the robot is unreachable | Not on the robot's Wi-Fi, robot off or still starting, or someone else may be connected. USB-C to the Control Hub also works. Tell the person and stop; do not retry in a loop |
| 4 | No Robot Controller log on that device | Wrong device, such as a Driver Hub |

You need internet to run, and a laptop on only the robot's Wi-Fi has none. If the person must switch networks to reach the robot, give them the command to run and ask for the folder path, or have them use USB.

## 3. Analyze

```
java "${CLAUDE_SKILL_DIR}/scripts/AnalyzeRobotLog.java" <folder>
```

Given a folder, it reads `robotControllerLog.txt` and its rotated copies in time order. Run it from the project root so stack frames map to source files. For one match, pass that match log file instead. Add `--json` for structured output. Exit code 0 means nothing serious matched, 1 means problems were found, 2 means the input was not a log.

## 4. Work out cause and effect

The report lists findings by seriousness, which is not always the order they happened. Use the timestamps:

- The earliest serious event near the incident time is usually the cause. Later ones are often consequences.
- Power loss leads to unresponsive hubs, which lead to command timeouts. Start at the power event.
- A stuck OpMode is followed by an app restart (`app starts seen` goes up). The code that blocked is the cause.
- A crash is followed by a switch to the idle OpMode. The crash is the cause.
- A Driver Station disconnect makes the SDK stop the robot. That is the robot working as designed; the question is why the link dropped, and the Driver Station's own log has that side.

Then read the raw log around that time yourself. The patterns come from the SDK 12.0 source and cannot cover everything, so check the section "OTHER ERROR-LEVEL LINES" and the lines just before the first finding.

## 5. Map it to code

- **Crash:** open the first team-code frame at the line given. Before trusting the line number, confirm the checkout matches what was on the robot (`git log`, uncommitted changes, or ask). If it does not match, find the line by method name.
- **Missing hardware device:** compare the name in code with the device names in `configs/*.xml`. The log does not say which configuration was active, so ask.
- **Stuck:** read the named method (`init()`, `loop()`, `stop()`) and everything it calls for a loop, sleep, or wait. Constructors called from `init()` count.
- **Power, hub, disconnect:** these are usually wiring, battery, or radio problems, not code. Say so plainly, and only then look for code that makes it worse, such as stalling a motor or a loop slow enough to trip a timeout.

## 6. Slow loops

The SDK does not record loop times. If the report says `LOOP STATS: none` and loop speed is the question, offer to add the helper in [reference/loop-stats.md](reference/loop-stats.md). It changes team code, so get a yes first, then have the person run the OpMode and pull logs again.

## 7. Report

Give the person:

- **What happened**, in one sentence.
- **Evidence:** the log lines with timestamps.
- **Cause:** file and line, or the hardware area if it is not code.
- **Fix:** the proposed change, or the physical check to make.
- **Confidence:** proven by the log, likely, or a guess. A timing coincidence is not proof.
- **Check on the robot:** how to confirm the fix.

Do not change code as part of diagnosis unless you were asked to fix it.

## Changing the tools

Both tools are written in the same Java 8 style as the robot code, so the team can read them and change them. To teach the analyzer a new problem, add a line to the `RULES` table in `scripts/AnalyzeRobotLog.java` and a matching row in [reference/log-patterns.md](reference/log-patterns.md). Change them only when asked, and run the tests afterward, from this skill's folder:

```
javac -d build scripts/AnalyzeRobotLog.java tests/AnalyzeRobotLogTest.java
java -cp build AnalyzeRobotLogTest
javac -d build scripts/PullRobotLogs.java tests/PullRobotLogsTest.java
java -cp build PullRobotLogsTest
```

Exit code 0 means every test passed. The puller's tests use a stand-in for adb, so they need no robot. Delete the `build` folder when done. Always compile into a separate folder as shown. JDK 11 will not run `java AnalyzeRobotLog.java` while a compiled `AnalyzeRobotLog.class` sits in the current folder; it stops with "class found on application class path". Deleting the stray `.class` files fixes it.
