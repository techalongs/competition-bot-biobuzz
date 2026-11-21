/*
 * Tests for AnalyzeRobotLog.java. No test library is needed. From the skill folder:
 *
 *     javac -d build scripts/AnalyzeRobotLog.java tests/AnalyzeRobotLogTest.java
 *     java -cp build AnalyzeRobotLogTest
 *
 * Exit code 0 means every test passed. Delete the build folder afterward. To run from
 * another folder, pass the path of tests/fixtures/synthetic_robot_log.txt as the argument.
 *
 * The fixture is synthetic: it is assembled from message formats in the FTC SDK 12.0
 * source, not captured from a robot.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AnalyzeRobotLogTest {

    private static int failures;
    private static int checks;
    private static final List<Path> TEMP = new ArrayList<Path>();
    private static final Path HERE = Paths.get("").toAbsolutePath();

    private AnalyzeRobotLogTest() {
    }

    // ---- tiny test harness -------------------------------------------------

    interface Test {
        void run() throws Exception;
    }

    static void test(String name, Test body) {
        int before = failures;
        try {
            body.run();
        } catch (Throwable error) {
            failures++;
            System.out.println("    threw " + error);
        }
        System.out.println((failures == before ? "ok    " : "FAIL  ") + name);
    }

    static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("    not true: " + what);
        }
    }

    static void same(Object actual, Object expected, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + ": expected " + expected + ", got " + actual);
    }

    static void near(double actual, double expected, String what) {
        check(Math.abs(actual - expected) < 5e-8, what + ": expected " + expected + ", got " + actual);
    }

    // ---- helpers -----------------------------------------------------------

    static final class Run {
        int code;
        String out;
        String err;
    }

    static Run run(InputStream stdin, String... argv) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Run result = new Run();
        result.code = AnalyzeRobotLog.run(argv, stdin, new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"), HERE);
        result.out = out.toString("UTF-8");
        result.err = err.toString("UTF-8");
        return result;
    }

    static Run run(String... argv) throws Exception {
        return run(new ByteArrayInputStream(new byte[0]), argv);
    }

    static Path tempDir() throws IOException {
        Path folder = Files.createTempDirectory("analyze-robot-log-test");
        TEMP.add(folder);
        return folder;
    }

    static Path write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    static List<AnalyzeRobotLog.Finding> find(AnalyzeRobotLog.Report report, String category, String needle) {
        List<AnalyzeRobotLog.Finding> found = new ArrayList<AnalyzeRobotLog.Finding>();
        for (AnalyzeRobotLog.Finding finding : report.findings) {
            if (finding.event.category.equals(category) && finding.event.detail.contains(needle)) {
                found.add(finding);
            }
        }
        return found;
    }

    static List<String> titles(AnalyzeRobotLog.Report report) {
        List<String> titles = new ArrayList<String>();
        for (AnalyzeRobotLog.Finding finding : report.findings) {
            titles.add(finding.event.title);
        }
        return titles;
    }

    static AnalyzeRobotLog.LoopReport loop(AnalyzeRobotLog.Report report, String opmode) {
        for (AnalyzeRobotLog.LoopReport entry : report.analysis.loop) {
            if (entry.opmode.equals(opmode)) {
                return entry;
            }
        }
        throw new IllegalStateException("no loop stats for " + opmode);
    }

    static final String INFO_LINE = "11-15 18:00:0%d.100  1234  1234 I RobotCore: App info: version=12.0.0 appId=x\n";

    // ---- tests -------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        final String fixture = argv.length > 0 ? argv[0]
                : HERE.resolve("tests").resolve("fixtures").resolve("synthetic_robot_log.txt").toString();
        if (!Files.isRegularFile(Paths.get(fixture))) {
            System.err.println("Cannot find the test log at " + fixture);
            System.err.println("Run from the skill folder, or pass the path of synthetic_robot_log.txt.");
            System.exit(2);
        }

        // A stand-in source tree, so stack frames can be mapped to files.
        final Path tree = tempDir();
        Path teamPackage = tree.resolve("org").resolve("firstinspires").resolve("ftc").resolve("teamcode");
        Files.createDirectories(teamPackage.resolve("subsystems"));
        write(teamPackage.resolve("Robot.java"), "");
        write(teamPackage.resolve("subsystems").resolve("Launcher.java"), "");

        AnalyzeRobotLog.Options options = new AnalyzeRobotLog.Options();
        options.logs.add(fixture);
        options.sourceRoot = tree.toString();
        final AnalyzeRobotLog.Report report = AnalyzeRobotLog.load(options, System.in, HERE);

        test("exit code and header", () -> {
            same(run(fixture, "--source-root", tree.toString()).code, AnalyzeRobotLog.EXIT_FINDINGS, "exit code");
            same(report.analysis.sdkVersion, "12.0.0", "SDK version");
            same(report.analysis.appStarts.size(), 2, "app starts");
            same(report.analysis.opmodesRun.get("One Controller TeleOp"), 3, "times TeleOp was selected");
            check(!report.analysis.opmodesRun.containsKey("$Stop$Robot$"), "the idle OpMode is not counted");
        });

        test("a repeated crash is grouped and mapped to source", () -> {
            List<AnalyzeRobotLog.Finding> crashes = find(report, "crash", "NullPointerException");
            same(crashes.size(), 1, "one finding for the repeated crash");
            AnalyzeRobotLog.Finding crash = crashes.get(0);
            same(crash.count, 2, "count (the second copy carries the clock prefix)");
            same(crash.event.exception, "java.lang.NullPointerException", "exception");
            same(crash.opmodes, Arrays.asList("One Controller TeleOp"), "OpModes");
            AnalyzeRobotLog.Frame frame = crash.event.teamFrames.get(0);
            same(frame.file, "Launcher.java", "file");
            same(frame.line, 29, "line");
            check(frame.path != null && frame.path.endsWith("subsystems/Launcher.java"), "path: " + frame.path);
            same(crash.event.teamFrames.get(1).method, "lambda$startLauncher$2", "second team frame");
            check(crash.event.hint.contains("used before it was created"), "hint: " + crash.event.hint);
        });

        test("root cause and hardware-name hint", () -> {
            AnalyzeRobotLog.Finding crash = find(report, "crash", "Unable to find a hardware device").get(0);
            same(crash.event.exception, "java.lang.IllegalArgumentException", "root cause");
            same(crash.event.outerException, "java.lang.RuntimeException", "outer exception");
            check(crash.event.hint.contains("'flywheel'"), "hint names the device: " + crash.event.hint);
            same(crash.opmodes, Arrays.asList("Auto Red"), "OpModes");
        });

        test("stuck OpMode, power, hub, and disconnect", () -> {
            AnalyzeRobotLog.Finding stuck = find(report, "stuck", "").get(0);
            same(stuck.opmodes, Arrays.asList("Vision Test"), "OpMode named in the message");
            check(stuck.event.detail.contains("stuck in init()"), "detail: " + stuck.event.detail);
            List<String> titles = titles(report);
            check(titles.contains("A hub lost power completely and came back"), "power loss");
            check(titles.contains("Battery too low to run motors and servos"), "low battery");
            check(titles.contains("A hub stopped responding"), "hub unresponsive");
            check(titles.contains("A hub was marked unresponsive"), "hub marked unresponsive");
            check(!find(report, "disconnect", "One Controller TeleOp").isEmpty(), "disconnect");
            for (AnalyzeRobotLog.Finding finding : report.findings) {
                if (finding.event.title.equals("A hub reconnected")) {
                    same(finding.event.severity, 3, "a reconnect is context, not a failure");
                }
            }
            check(titles.contains("A hub reconnected"), "reconnect is reported");
        });

        test("loop stats", () -> {
            check(!loop(report, "One_Controller_TeleOp").slow, "TeleOp is not slow");
            near(loop(report, "One_Controller_TeleOp").avgMs, 12.5, "TeleOp average");
            check(loop(report, "Slow_Auto").slow, "Slow_Auto is slow");
            near(loop(report, "Slow_Auto").maxMs, 260.5, "Slow_Auto worst loop");
            near(loop(report, "Slow_Auto").minBattV, 9.42, "Slow_Auto lowest battery");
            check(!find(report, "loop", "worst 260.5 ms").isEmpty(), "slow loop finding");
            check(!find(report, "power", "9.42 V").isEmpty(), "battery sag finding");
            near(report.analysis.pauses.get(0).ms, 181.25, "long thread pause");
        });

        test("unmatched errors are listed, not lost", () -> {
            List<String> lines = new ArrayList<String>();
            for (AnalyzeRobotLog.OtherError error : report.analysis.otherErrors) {
                lines.add(error.line);
                check(!error.line.contains("NullPointerException"), "crash lines are not listed twice");
            }
            check(lines.contains("E SomeLibrary: failed to open camera # on attempt #"), "library errors: " + lines);
            check(lines.contains("E RobotCore: Begin thread dump"), "other RobotCore errors");
            same(report.analysis.otherErrors.get(0).count, 2, "most frequent first");
        });

        test("severity order", () -> {
            List<String> order = Arrays.asList("crash", "stuck", "power");
            int last = 0;
            int lastSeverity = 0;
            for (AnalyzeRobotLog.Finding finding : report.findings) {
                check(finding.event.severity >= lastSeverity, "findings are sorted by severity");
                lastSeverity = finding.event.severity;
                if (finding.event.severity == 1) {
                    int rank = order.indexOf(finding.event.category);
                    check(rank >= last, "crash, then stuck, then power: " + finding.event.category);
                    last = rank;
                }
            }
        });

        test("text report", () -> {
            Run result = run(fixture);
            same(result.code, AnalyzeRobotLog.EXIT_FINDINGS, "exit code");
            check(result.out.contains("[1] CRASH: Team code threw an uncaught exception"), "first finding");
            check(result.out.contains("team code: org.firstinspires.ftc.teamcode.subsystems.Launcher"
                    + ".startLauncher(Launcher.java:29)"), "team frame");
            check(result.out.contains("LOOP STATS (from LoopStats lines)"), "loop stats section");
            check(result.out.contains("(2 times, last 11-15 18:02:03.010)"), "repeat count and last time");
            Run mapped = run(fixture, "--source-root", tree.toString());
            check(mapped.out.contains("subsystems/Launcher.java:29"), "mapped source line");
        });

        test("json report", () -> {
            Run result = run(fixture, "--json", "--source-root", tree.toString());
            same(result.code, AnalyzeRobotLog.EXIT_FINDINGS, "exit code");
            check(result.out.startsWith("{\n  \"files\": ["), "starts with the file list");
            check(result.out.contains("\"sdk_version\": \"12.0.0\""), "SDK version");
            check(result.out.contains("\"exception\": \"java.lang.NullPointerException\""), "exception");
            check(result.out.contains("\"min_batt_v\": 9.42"), "numbers are written as numbers");
            check(result.out.contains("name \\\"flywheel\\\" and type"), "quotes are escaped");
            check(!result.out.contains("signature"), "internal fields are left out");
        });

        test("a clean log exits zero", () -> {
            Path log = write(tempDir().resolve("clean.txt"), String.format(INFO_LINE, 1)
                    + "11-15 18:00:02.100  1234  1234 I RobotCore: Attempting to switch to OpMode Fine\n");
            Run result = run(log.toString());
            same(result.code, AnalyzeRobotLog.EXIT_CLEAN, "exit code");
            check(result.out.contains("FINDINGS: none"), "no findings");
            check(result.out.contains("LOOP STATS: none"), "no loop stats");
        });

        test("bad input exits 2", () -> {
            Path notALog = write(tempDir().resolve("notes.txt"), "this is not a log\n");
            Run result = run(notALog.toString());
            same(result.code, AnalyzeRobotLog.EXIT_INPUT, "a file with no log lines");
            check(result.err.startsWith("error: no logcat-format lines"), "message: " + result.err);
            same(result.out, "", "nothing on stdout");
            Run missing = run("/no/such/file.txt");
            same(missing.code, AnalyzeRobotLog.EXIT_INPUT, "a missing file");
            check(missing.err.contains("cannot read /no/such/file.txt"), "message: " + missing.err);
            same(run().code, AnalyzeRobotLog.EXIT_INPUT, "no arguments");
            same(run(fixture, "--bogus").code, AnalyzeRobotLog.EXIT_INPUT, "unknown option");
            same(run(fixture, "--other", "many").code, AnalyzeRobotLog.EXIT_INPUT, "a number that is not one");
            same(run("--help").code, 0, "help");
        });

        test("folder and wildcard inputs", () -> {
            Path folder = tempDir();
            String[] names = {"robotControllerLog.txt.1", "robotControllerLog.txt", "logcat-live.txt"};
            for (int i = 0; i < names.length; i++) {
                write(folder.resolve(names[i]), String.format(INFO_LINE, i));
            }
            AnalyzeRobotLog.Options byFolder = new AnalyzeRobotLog.Options();
            byFolder.logs.add(folder.toString());
            List<AnalyzeRobotLog.FileInfo> files = AnalyzeRobotLog.load(byFolder, System.in, HERE).files;
            same(files.size(), 2, "only the Robot Controller logs are read from a folder");
            check(files.get(0).file.endsWith("robotControllerLog.txt.1"), "oldest first: " + files.get(0).file);
            check(files.get(1).file.endsWith("robotControllerLog.txt"), "newest last: " + files.get(1).file);

            AnalyzeRobotLog.Options byPattern = new AnalyzeRobotLog.Options();
            byPattern.logs.add(folder.toString() + File.separator + "robotControllerLog.txt*");
            same(AnalyzeRobotLog.load(byPattern, System.in, HERE).files.size(), 2, "wildcard pattern");
            same(run(folder.toString() + File.separator + "robotControllerLog.txt*").code,
                    AnalyzeRobotLog.EXIT_CLEAN, "exit code");
            same(run(folder.toString() + File.separator + "nothing*").code, AnalyzeRobotLog.EXIT_INPUT,
                    "a pattern that matches nothing");
        });

        test("a folder without robotControllerLog.txt uses every file except hidden ones", () -> {
            Path folder = tempDir();
            write(folder.resolve("Match-1-Auto.txt"), String.format(INFO_LINE, 1));
            write(folder.resolve("Match-2-TeleOp.txt"), String.format(INFO_LINE, 2));
            write(folder.resolve(".DS_Store"), "junk");
            Files.createDirectory(folder.resolve("configs"));
            AnalyzeRobotLog.Options byFolder = new AnalyzeRobotLog.Options();
            byFolder.logs.add(folder.toString());
            same(AnalyzeRobotLog.load(byFolder, System.in, HERE).files.size(), 2, "files read");
        });

        test("brief format and rotation order", () -> {
            List<String> names = new ArrayList<String>(Arrays.asList("a.txt", "a.txt.2", "a.txt.1", "a.txt.10"));
            names.sort(AnalyzeRobotLog.ROTATION_ORDER);
            same(names, Arrays.asList("a.txt.10", "a.txt.2", "a.txt.1", "a.txt"), "oldest first");
            AnalyzeRobotLog.Line line = AnalyzeRobotLog.parseLine("x", 1,
                    "E/OpModeManager( 1234): User code threw an uncaught exception");
            same(line.level, "E", "level");
            same(line.tag, "OpModeManager", "tag");
            same(AnalyzeRobotLog.parseLine("x", 1, "--------- beginning of main"), null, "not a log line");
        });

        test("stdin, frames without line numbers, and bytes that are not UTF-8", () -> {
            String log = "E/AndroidRuntime( 99): FATAL EXCEPTION: main\n"
                    + "E/AndroidRuntime( 99): Process: com.qualcomm.ftcrobotcontroller, PID: 99\n"
                    + "E/AndroidRuntime( 99): java.lang.UnsatisfiedLinkError: no camera library\n"
                    + "E/AndroidRuntime( 99): \tat java.lang.Runtime.loadLibrary0(Native Method)\n"
                    + "E/AndroidRuntime( 99): \tat org.example.Camera.open(Camera.java:12)\n";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(log.getBytes(StandardCharsets.UTF_8));
            bytes.write(new byte[] {'I', '/', 'X', '(', '1', ')', ':', ' ', (byte) 0xff, (byte) 0xfe, '\n'});
            Run result = run(new ByteArrayInputStream(bytes.toByteArray()), "-");
            same(result.code, AnalyzeRobotLog.EXIT_FINDINGS, "exit code");
            check(result.out.contains("CRASH: The Robot Controller app crashed"), "crash title");
            check(result.out.contains("what:   java.lang.UnsatisfiedLinkError: no camera library"), "what");
            check(result.out.contains("thrown in: java.lang.Runtime.loadLibrary0(Native Method)"),
                    "frame without a line number: " + result.out);
            check(result.out.contains("No team-code frame in the trace"), "hint when no team code is involved");
            check(result.out.contains("file: - (6 log lines, no timestamps)"), "stdin is named -");
        });

        test("thresholds can be changed", () -> {
            AnalyzeRobotLog.Options relaxed = new AnalyzeRobotLog.Options();
            relaxed.logs.add(fixture);
            relaxed.loopAvgMs = 60;
            relaxed.loopMaxMs = 500;
            relaxed.lowVoltage = 9;
            AnalyzeRobotLog.Report loose = AnalyzeRobotLog.load(relaxed, System.in, HERE);
            check(find(loose, "loop", "").isEmpty(), "no slow-loop finding with loose limits");
            check(find(loose, "power", "lowest logged").isEmpty(), "no sag finding with a lower limit");
            Run few = run(fixture, "--other", "1");
            check(few.out.contains("... and 1 more distinct lines"), "--other limits the list");
        });

        for (Path folder : TEMP) {
            delete(folder.toFile());
        }
        System.out.println();
        System.out.println(failures == 0
                ? "All tests passed (" + checks + " checks)."
                : failures + " problem(s) in " + checks + " checks.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
