# Robot Controller log patterns

Every pattern here was taken from the FTC SDK 12.0.0 source (`RobotCore` and `Hardware` artifacts). `scripts/AnalyzeRobotLog.java` searches for the same strings, in its `RULES` table. Use this table to search by hand when no JDK is available, or to check a finding against the SDK.

## Where the logs are

| File on the robot | What it is |
|---|---|
| `/sdcard/robotControllerLog.txt` | The Robot Controller's own log. Older parts rotate to `.1`, `.2`, and so on; a higher number is older |
| `/sdcard/FIRST/matchlogs/Match-<number>-<OpMode>.txt` | One file per logged match, when match logging is on. The SDK keeps the newest 9 |
| `/sdcard/FIRST/*.xml` | Robot configuration files. Compare device names here with the strings in code |
| `driverStationLog.txt` on the Driver Hub | The Driver Station's side of a disconnect |

Lines are in logcat `threadtime` format: `MM-DD HH:MM:SS.mmm  PID  TID LEVEL Tag: message`. There is no year. After a Driver Station connects, the SDK may put `{ offset ss.mmm} ` at the start of each message; ignore that prefix when searching.

## Markers for finding your place

| Search for | Meaning | Source |
|---|---|---|
| `App info: version=` | The app started. Two of these means the app restarted in between | `RobotLog.logAppInfo` |
| `Attempting to switch to OpMode <name>` | An OpMode was selected. `$Stop$Robot$` is the idle OpMode between runs | `OpModeManagerImpl` |
| `saving match logcat to` | A match log started | `RobotLog` |
| `User runOpModeMethod exited` | A LinearOpMode's `runOpMode` returned | `LinearOpMode` |

## Failures

| Search for | Tag | Meaning | Source |
|---|---|---|---|
| `User code threw an uncaught exception` | `OpModeManager` | Team code crashed. The stack trace follows on the next lines with the same tag; the first `at org.firstinspires.ftc.teamcode...` frame is where to look | `OpModeManagerImpl.handleUserCodeException` |
| `FATAL EXCEPTION` | `AndroidRuntime` | The whole app crashed, usually from a thread outside the OpMode | Android |
| `Unable to find a hardware device with name "<name>"` | (in a stack trace) | A `hardwareMap.get` name is not in the active configuration, or has a different type | `HardwareMap` |
| `OpMode '<name>' stuck in <method>. Restarting robot controller app.` | `RobotCore` | A method blocked until the SDK gave up and restarted the app | `OpModeManagerImpl` |
| `User OpMode was stuck in <method>, but was able to be force stopped` | | Same cause, but the OpMode could be stopped without a restart | `OpModeManagerImpl` |
| `took too long to exit; emergency killing app` | | A thread ignored the stop request | `ThreadPool` |
| `<hub> regained power after a complete power loss` | `HubPowerCycle` | A hub lost power and came back: loose connector, power switch, or a deep voltage dip | `LynxModuleWarningManager` |
| `<hub> currently has a battery too low to run motors and servos` | `RobotCore` | Battery voltage is below what the hub needs | `LynxModuleWarningManager` |
| `BATTERY_TOO_LOW_TO_RUN_MOTOR`, `BATTERY_TOO_LOW_TO_RUN_SERVO` | | A hub refused a command for low voltage | `LynxNack` |
| `<hub> is currently unresponsive.` | `RobotCore` | A hub stopped answering | `LynxModuleWarningManager` |
| `Marking module #<n> as unresponsive` | | Same, from the hub driver | `LynxRespondable` |
| `REV Hub #<n> has reconnected` | | The hub came back; look just before this for why it left | `LynxModule` |
| `timeout: abandoning waiting` | | A hub command timed out | `LynxRespondable` |
| `#### abandoning lock` | | A hub command hung and its lock was dropped | `MessageKeyedLock` |
| `Unable to communicate with REV Hub #<n> at robot startup` | | A configured hub did not answer at startup | `LynxUsbDeviceImpl` |
| `module #<n> did not connect at startup` | | Its devices were left out of the hardwareMap, so lookups for them will fail | `LynxUsbDeviceImpl` |
| `event loop: device has shutdown abnormally` | | A USB device dropped | `EventLoopManager` |
| `I2C_TIMEOUT_SDA_STUCK`, `I2C_TIMEOUT_SCK_STUCK`, `I2C_TIMEOUT_UNKNOWN_CAUSE` | | An I2C bus fault: wiring or a failed sensor | `LynxNack` |
| `Lost connection while running OpMode: <name>` | `EventLoopManager` | The Driver Station link dropped, and the SDK stopped the robot | `EventLoopManager.onPeerDisconnected` |

## What the SDK does not log

- **Loop time.** There is no built-in record. Add the helper in [loop-stats.md](loop-stats.md) to get `LoopStats` lines.
- **Battery voltage over time.** Only the too-low events above. The same helper logs voltage once a second.
- **Which configuration file was active.** The XML files are all there, but the log does not name the active one. Ask.

## Reading by hand

```
adb logcat -d -t 500                               # last 500 lines of the live buffer
adb pull /sdcard/robotControllerLog.txt ./logs/    # the saved log
adb shell ls /sdcard/FIRST/matchlogs               # match logs
```

Always pass `-d` to `adb logcat`. Without it the command never returns.
