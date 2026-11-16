# LoopStats: measuring loop time and battery voltage

The FTC SDK does not record how long each control loop takes or what the battery voltage was. This small class logs both once a second, in the format `scripts/AnalyzeRobotLog.java` reads. Adding it changes team code, so get an explicit yes before putting it in the project.

## The class

Java 8, no library beyond the SDK. Suggested location: `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/util/LoopStats.java`.

```java
package org.firstinspires.ftc.teamcode.util;

import android.util.Log;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import java.util.Iterator;
import java.util.Locale;

/**
 * Logs loop timing and battery voltage once a second under the tag "LoopStats".
 * Call tick() once at the top of every loop.
 */
public final class LoopStats {
    private static final String TAG = "LoopStats";
    private static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private final String opModeName;
    private final VoltageSensor voltageSensor; // null when the configuration has none

    private long previousTickNanos;
    private long windowStartNanos;
    private long worstLoopNanos;
    private int loopsInWindow;

    public LoopStats(String opModeName, HardwareMap hardwareMap) {
        this.opModeName = opModeName.replace(' ', '_');
        Iterator<VoltageSensor> sensors = hardwareMap.voltageSensor.iterator();
        this.voltageSensor = sensors.hasNext() ? sensors.next() : null;
    }

    public void tick() {
        long now = System.nanoTime();
        if (previousTickNanos == 0) {
            windowStartNanos = now;
        } else {
            worstLoopNanos = Math.max(worstLoopNanos, now - previousTickNanos);
            loopsInWindow++;
        }
        previousTickNanos = now;

        if (loopsInWindow > 0 && now - windowStartNanos >= REPORT_INTERVAL_NANOS) {
            double averageMs = (now - windowStartNanos) / 1e6 / loopsInWindow;
            double worstMs = worstLoopNanos / 1e6;
            double batteryVolts = voltageSensor != null ? voltageSensor.getVoltage() : 0.0;
            Log.i(TAG, String.format(Locale.US, "opmode=%s n=%d avg_ms=%.1f max_ms=%.1f batt_v=%.2f",
                    opModeName, loopsInWindow, averageMs, worstMs, batteryVolts));
            windowStartNanos = now;
            worstLoopNanos = 0;
            loopsInWindow = 0;
        }
    }
}
```

## Using it

In an iterative OpMode, create it in `init()` and call `tick()` as the first line of `loop()`:

```java
private LoopStats loopStats;

@Override
public void init() {
    loopStats = new LoopStats("One Controller TeleOp", hardwareMap);
    // ... the rest of init
}

@Override
public void loop() {
    loopStats.tick();
    // ... the rest of loop
}
```

In a `LinearOpMode` or `CommandOpMode`, call `tick()` once per pass through the main loop.

## What it costs

- One battery-voltage read and one log line per second. The voltage read is a hub command of its own, not part of a bulk read, so it adds a few milliseconds to one loop each second. That loop can show up as the window's worst loop.
- No allocation on ordinary loops. The once-a-second report allocates a short string.

## The log line

```
I LoopStats: opmode=One_Controller_TeleOp n=81 avg_ms=12.3 max_ms=44.2 batt_v=12.61
```

`n` is loops in the window, `avg_ms` and `max_ms` are loop times in that window, `batt_v` is volts (0.00 when no voltage sensor was found). The analyzer flags an OpMode whose average is over 25 ms or whose worst loop is over 100 ms, and battery readings under 10.0 V. Those limits are starting points; change them with `--loop-avg-ms`, `--loop-max-ms`, and `--low-voltage`.

Verify the two SDK calls (`hardwareMap.voltageSensor` and `VoltageSensor.getVoltage()`) against the pinned SDK version before adding the class. They were checked against SDK 12.0.0.
