/*
 * Tests for PullRobotLogs.java, using a stand-in for adb so no robot is needed.
 * No test library is needed. From the skill folder:
 *
 *     javac -d build scripts/PullRobotLogs.java tests/PullRobotLogsTest.java
 *     java -cp build PullRobotLogsTest
 *
 * Exit code 0 means every test passed. Delete the build folder afterward.
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PullRobotLogsTest {

    private static int failures;
    private static int checks;
    private static final List<Path> TEMP = new ArrayList<Path>();
    static final String LINE = "11-15 18:00:01.100  1234  1234 I RobotCore: App info: version=12.0.0 "
            + "appId=com.qualcomm.ftcrobotcontroller\n";

    private PullRobotLogsTest() {
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

    // ---- a stand-in for adb ------------------------------------------------

    /**
     * Answers the adb commands the tool sends, without a robot. Modes: attached (one USB
     * device), wifi (reachable after adb connect), none, two, nolog, unauthorized, daemon
     * (adb prints start-up notices), pullfail (one file cannot be copied), hang.
     */
    static final class FakeAdb implements PullRobotLogs.Runner {
        final String mode;
        boolean connected;
        final List<String> calls = new ArrayList<String>();
        final Map<String, List<String>> files = new LinkedHashMap<String, List<String>>();

        FakeAdb(String mode) {
            this.mode = mode;
            files.put("/sdcard", Arrays.asList("Download", "FIRST", "robotControllerLog.txt",
                    "robotControllerLog.txt.1"));
            files.put("/sdcard/FIRST", Arrays.asList("matchlogs", "robot.xml", "practice.xml", "settings"));
            files.put("/sdcard/FIRST/matchlogs", Arrays.asList("Match-3-Auto_Red.txt"));
        }

        private static PullRobotLogs.Result result(int code, String out, String err) {
            return new PullRobotLogs.Result(code, out.getBytes(StandardCharsets.UTF_8), err, false);
        }

        @Override
        public PullRobotLogs.Result run(List<String> command, int timeoutSeconds) throws IOException {
            List<String> args = new ArrayList<String>(command.subList(1, command.size()));
            calls.add(String.join(" ", args));
            if (args.size() >= 2 && args.get(0).equals("-s")) {
                args = args.subList(2, args.size());
            }
            String first = args.get(0);
            if (first.equals("devices")) {
                if (mode.equals("hang")) {
                    return new PullRobotLogs.Result(-1, new byte[0], "", true);
                }
                StringBuilder out = new StringBuilder();
                if (mode.equals("daemon")) {
                    out.append("* daemon not running; starting now at tcp:5037\n* daemon started successfully\n");
                }
                out.append("List of devices attached\n");
                if (mode.equals("attached") || mode.equals("nolog") || mode.equals("daemon")
                        || mode.equals("pullfail")) {
                    out.append("ABC123\tdevice\n");
                } else if (mode.equals("two")) {
                    out.append("ABC123\tdevice\nXYZ789\tdevice\n");
                } else if (mode.equals("unauthorized")) {
                    out.append("ABC123\tunauthorized\n");
                } else if (mode.equals("wifi") && connected) {
                    out.append("192.168.43.1:5555\tdevice\n");
                }
                return result(0, out.append("\n").toString(), "");
            }
            if (first.equals("connect")) {
                if (mode.equals("wifi")) {
                    connected = true;
                    return result(0, "connected to " + args.get(1) + "\n", "");
                }
                return result(0, "failed to connect to " + args.get(1) + "\n", "");
            }
            if (first.equals("shell") && args.get(1).equals("ls")) {
                List<String> names = files.get(args.get(2));
                if (names == null || (mode.equals("nolog") && args.get(2).equals("/sdcard"))) {
                    return result(names == null ? 1 : 0, "ls: " + args.get(2) + ": No such file or directory\n", "");
                }
                return result(0, String.join("\r\n", names) + "\r\n", "");     // older Android ends lines with CR LF
            }
            if (first.equals("pull")) {
                String name = args.get(1).substring(args.get(1).lastIndexOf('/') + 1);
                if (mode.equals("pullfail") && name.equals("robot.xml")) {
                    return result(1, "", "adb: error: failed to stat remote object");
                }
                Files.write(Paths.get(args.get(2), name), LINE.getBytes(StandardCharsets.UTF_8));
                return result(0, args.get(1) + ": 1 file pulled.\n", "");
            }
            if (first.equals("logcat") && args.get(1).equals("-d")) {
                return result(0, LINE, "");
            }
            throw new IOException("fake adb: unexpected arguments " + args);
        }
    }

    // ---- helpers -----------------------------------------------------------

    static final class Run {
        int code;
        String out;
        String err;
        FakeAdb adb;
        Path folder;
    }

    static Path tempDir() throws IOException {
        Path folder = Files.createTempDirectory("pull-robot-logs-test");
        TEMP.add(folder);
        return folder;
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

    /** A context with nothing in the environment and an empty home folder. */
    static PullRobotLogs.Context context(PullRobotLogs.Runner runner, Path home) {
        PullRobotLogs.Context context = new PullRobotLogs.Context();
        context.runner = runner;
        context.env = new HashMap<String, String>();
        context.home = home.toString();
        context.workingDir = home;
        return context;
    }

    static Run run(PullRobotLogs.Context context, String... argv) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Run result = new Run();
        result.code = PullRobotLogs.run(argv, new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"),
                context);
        result.out = out.toString("UTF-8");
        result.err = err.toString("UTF-8");
        return result;
    }

    /** Runs the tool against the stand-in adb in the given mode, saving into a fresh folder. */
    static Run pull(String mode, String... extra) throws Exception {
        Path home = tempDir();
        Path adbFile = Files.write(home.resolve("adb"), new byte[0]);
        Path folder = home.resolve("out");
        FakeAdb adb = new FakeAdb(mode);
        List<String> argv = new ArrayList<String>(Arrays.asList("--adb", adbFile.toString(), "--out", folder.toString()));
        argv.addAll(Arrays.asList(extra));
        Run result = run(context(adb, home), argv.toArray(new String[0]));
        result.adb = adb;
        result.folder = folder;
        return result;
    }

    static List<String> saved(Path folder) throws IOException {
        List<String> names = new ArrayList<String>();
        collect(folder.toFile(), "", names);
        Collections.sort(names);
        return names;
    }

    private static void collect(File folder, String prefix, List<String> names) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, prefix + child.getName() + "/", names);
            } else {
                names.add(prefix + child.getName());
            }
        }
    }

    static String javaProgram() {
        String exe = File.separatorChar == '\\' ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", exe).toString();
    }

    // ---- tests -------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        // Child-process modes, used by the tests of the real program runner below.
        if (argv.length > 0 && argv[0].equals("--child-sleep")) {
            Thread.sleep(60000);
            return;
        }
        if (argv.length > 0 && argv[0].equals("--child-spew")) {
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 48; i++) {         // 3 MB on stdout, more than any pipe holds
                System.out.write(chunk, 0, chunk.length);
            }
            System.out.flush();
            System.err.print("  done  \n");
            System.exit(7);
        }

        test("a USB device: everything is pulled", () -> {
            Run result = pull("attached");
            same(result.code, PullRobotLogs.EXIT_OK, "exit code (" + result.err + ")");
            same(result.out.trim(), result.folder.toAbsolutePath().normalize().toString(), "stdout is the folder");
            same(saved(result.folder), Arrays.asList("configs/practice.xml", "configs/robot.xml", "logcat-live.txt",
                    "matchlogs/Match-3-Auto_Red.txt", "robotControllerLog.txt", "robotControllerLog.txt.1"), "files");
            check(!result.err.contains("trying adb connect"), "no Wi-Fi connect when a device is attached");
            check(result.err.contains("saved 6 files"), "progress goes to stderr: " + result.err);
            check(result.adb.calls.contains("-s ABC123 logcat -d -v threadtime"),
                    "logcat is dumped with -d: " + result.adb.calls);
            for (String call : result.adb.calls) {
                check(!call.contains("install") && !call.contains("rm ") && !call.contains("push"),
                        "only reads from the robot: " + call);
            }
        });

        test("--skip leaves the extras out", () -> {
            Run result = pull("attached", "--skip", "matchlogs,configs,logcat");
            same(result.code, PullRobotLogs.EXIT_OK, "exit code");
            same(saved(result.folder), Arrays.asList("robotControllerLog.txt", "robotControllerLog.txt.1"), "files");
            Run some = pull("attached", "--skip=logcat");
            same(saved(some.folder).contains("logcat-live.txt"), false, "--skip=logcat");
            same(saved(some.folder).contains("configs/robot.xml"), true, "configs still pulled");
        });

        test("connects over Wi-Fi when nothing is attached", () -> {
            Run result = pull("wifi");
            same(result.code, PullRobotLogs.EXIT_OK, "exit code (" + result.err + ")");
            check(result.err.contains("trying adb connect 192.168.43.1:5555"), "tries to connect");
            check(result.err.contains("device: 192.168.43.1:5555"), "uses the robot address");
            check(result.adb.calls.contains("-s 192.168.43.1:5555 shell ls /sdcard"), "commands name the device");
        });

        test("unreachable: the three causes are listed", () -> {
            Run result = pull("none");
            same(result.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code");
            same(result.out, "", "nothing on stdout");
            check(result.err.contains("not connected to the robot's Wi-Fi"), "cause 1");
            check(result.err.contains("turned off"), "cause 2");
            check(result.err.contains("Someone else may be connected"), "cause 3");
            check(result.err.contains("USB-C"), "the cable alternative");
            Run noConnect = pull("none", "--no-connect");
            same(noConnect.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code with --no-connect");
            check(!noConnect.adb.calls.contains("connect 192.168.43.1:5555"), "--no-connect does not connect");
            same(Files.exists(result.folder), false, "no folder is created when nothing is reachable");
        });

        test("two devices need a choice", () -> {
            Run result = pull("two");
            same(result.code, PullRobotLogs.EXIT_USAGE, "exit code");
            check(result.err.contains("--serial"), "says how to choose: " + result.err);
            Run chosen = pull("two", "--serial", "XYZ789");
            same(chosen.code, PullRobotLogs.EXIT_OK, "exit code with --serial");
            check(chosen.adb.calls.contains("-s XYZ789 pull /sdcard/robotControllerLog.txt "
                    + chosen.folder.toAbsolutePath().normalize()), "pulls from the chosen device: " + chosen.adb.calls);
            Run wrong = pull("two", "--serial", "NOPE");
            same(wrong.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code for an unknown serial");
            check(wrong.err.contains("device NOPE is not attached"), "message: " + wrong.err);
        });

        test("ANDROID_SERIAL chooses the device", () -> {
            Path home = tempDir();
            Path adbFile = Files.write(home.resolve("adb"), new byte[0]);
            FakeAdb adb = new FakeAdb("two");
            PullRobotLogs.Context context = context(adb, home);
            context.env.put("ANDROID_SERIAL", "XYZ789");
            Run result = run(context, "--adb", adbFile.toString(), "--out", home.resolve("out").toString());
            same(result.code, PullRobotLogs.EXIT_OK, "exit code");
            check(result.err.contains("device: XYZ789"), "device: " + result.err);
        });

        test("no log on the device exits 4", () -> {
            Run result = pull("nolog");
            same(result.code, PullRobotLogs.EXIT_NO_LOG, "exit code");
            check(result.err.contains("no robotControllerLog.txt in /sdcard"), "message: " + result.err);
        });

        test("bad arguments exit 2", () -> {
            same(pull("attached", "--skip", "everything").code, PullRobotLogs.EXIT_USAGE, "unknown --skip value");
            same(pull("attached", "--bogus").code, PullRobotLogs.EXIT_USAGE, "unknown option");
            same(pull("attached", "--serial").code, PullRobotLogs.EXIT_USAGE, "option without its value");
            Run help = pull("attached", "--help");
            same(help.code, 0, "help");
            check(help.out.contains("--no-connect"), "help text");
            same(help.adb.calls.size(), 0, "help does not touch adb");
        });

        test("adb start-up notices, unauthorized devices, and a hung adb", () -> {
            same(pull("daemon").code, PullRobotLogs.EXIT_OK, "daemon notices are not devices");
            Run locked = pull("unauthorized", "--no-connect");
            same(locked.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code for an unauthorized device");
            check(locked.err.contains("device attached but not usable: ABC123 is unauthorized"),
                    "message: " + locked.err);
            Run hung = pull("hang");
            same(hung.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code when adb hangs");
            check(hung.err.contains("adb devices timed out after 15s"), "message: " + hung.err);
        });

        test("a file that cannot be copied is reported and the rest are kept", () -> {
            Run result = pull("pullfail");
            same(result.code, PullRobotLogs.EXIT_OK, "exit code");
            check(result.err.contains("could not pull /sdcard/FIRST/robot.xml: adb: error"), "message: " + result.err);
            check(result.err.contains("saved 5 files"), "count: " + result.err);
            same(saved(result.folder).contains("configs/practice.xml"), true, "the other config is kept");
        });

        test("the default folder is ~/ftc-robot-logs/<date-time>", () -> {
            Path home = tempDir();
            Path adbFile = Files.write(home.resolve("adb"), new byte[0]);
            Run result = run(context(new FakeAdb("attached"), home), "--adb", adbFile.toString());
            same(result.code, PullRobotLogs.EXIT_OK, "exit code");
            Path folder = Paths.get(result.out.trim());
            same(folder.getParent(), home.toAbsolutePath().normalize().resolve("ftc-robot-logs"), "parent folder");
            check(folder.getFileName().toString().matches("\\d{8}-\\d{6}"), "name: " + folder.getFileName());
            check(Files.isRegularFile(folder.resolve("robotControllerLog.txt")), "log saved there");
        });

        test("local.properties with Windows escaping", () -> {
            Path folder = tempDir();
            Files.write(folder.resolve("local.properties"),
                    "## comment\nsdk.dir=C\\:\\\\Users\\\\kid\\\\AppData\\\\Local\\\\Android\\\\Sdk\n"
                            .getBytes(StandardCharsets.UTF_8));
            Path nested = Files.createDirectories(folder.resolve("TeamCode").resolve("src"));
            same(PullRobotLogs.sdkDirFromLocalProperties(nested), "C:\\Users\\kid\\AppData\\Local\\Android\\Sdk",
                    "sdk.dir");
            Path mac = tempDir();
            Files.write(mac.resolve("local.properties"), "sdk.dir=/Users/kid/Library/Android/sdk\n"
                    .getBytes(StandardCharsets.UTF_8));
            same(PullRobotLogs.sdkDirFromLocalProperties(mac), "/Users/kid/Library/Android/sdk", "sdk.dir on a Mac");
        });

        test("adb is not found when it is nowhere", () -> {
            Path home = tempDir();
            PullRobotLogs.Context context = context(new FakeAdb("attached"), home);
            context.env.put("PATH", home.toString());
            same(PullRobotLogs.findAdb("/no/such/adb", context), null, "findAdb");
            Run result = run(context, "--out", home.resolve("out").toString());
            same(result.code, PullRobotLogs.EXIT_UNREACHABLE, "exit code");
            check(result.err.contains("adb not found"), "message: " + result.err);
        });

        test("adb is found through local.properties, PATH, $ADB, and the SDK variables", () -> {
            Path home = tempDir();
            PullRobotLogs.Context context = context(new FakeAdb("attached"), home);
            String exe = context.windows ? "adb.exe" : "adb";

            Path tools = Files.createDirectories(home.resolve("sdk").resolve("platform-tools"));
            Path inSdk = Files.write(tools.resolve(exe), new byte[0]);
            Path project = Files.createDirectories(home.resolve("project").resolve("TeamCode"));
            Files.write(home.resolve("project").resolve("local.properties"),
                    ("sdk.dir=" + home.resolve("sdk").toString().replace("\\", "\\\\").replace(":", "\\:") + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            context.workingDir = project;
            same(PullRobotLogs.findAdb(null, context), inSdk, "through local.properties, from a subfolder");

            context.workingDir = home;
            same(PullRobotLogs.findAdb(null, context), null, "not found outside the project");
            context.env.put("ANDROID_SDK_ROOT", home.resolve("sdk").toString());
            same(PullRobotLogs.findAdb(null, context), inSdk, "through ANDROID_SDK_ROOT");

            Path bin = Files.createDirectories(home.resolve("bin"));
            Path onPath = Files.write(bin.resolve(exe), new byte[0]);
            context.env.put("Path", home.resolve("empty") + File.pathSeparator + bin);      // Windows spelling
            same(PullRobotLogs.findAdb(null, context), onPath, "PATH wins over the SDK");

            Path special = Files.write(home.resolve("my-adb"), new byte[0]);
            context.env.put("ADB", special.toString());
            same(PullRobotLogs.findAdb(null, context), special, "$ADB wins over PATH");
            same(PullRobotLogs.findAdb(onPath.toString(), context), onPath, "--adb wins over everything");
        });

        test("the real program runner: exit code, output, and stderr", () -> {
            PullRobotLogs.Runner runner = new PullRobotLogs.ProcessRunner();
            PullRobotLogs.Result result = runner.run(Arrays.asList(javaProgram(), "-cp",
                    System.getProperty("java.class.path"), "PullRobotLogsTest", "--child-spew"), 60);
            same(result.code, 7, "exit code");
            same(result.out.length, 48 * 64 * 1024, "all of a large output is captured");
            // A JVM may add its own notice lines to stderr first, so only the end is checked.
            check(result.err.endsWith("done"), "stderr is captured and trimmed: [" + result.err + "]");
            same(result.timedOut, false, "did not time out");
        });

        test("the real program runner: a hung program is stopped", () -> {
            PullRobotLogs.Runner runner = new PullRobotLogs.ProcessRunner();
            long start = System.currentTimeMillis();
            PullRobotLogs.Result result = runner.run(Arrays.asList(javaProgram(), "-cp",
                    System.getProperty("java.class.path"), "PullRobotLogsTest", "--child-sleep"), 2);
            same(result.timedOut, true, "timed out");
            check(System.currentTimeMillis() - start < 20000, "stopped promptly");
            try {
                runner.run(Arrays.asList(Paths.get("no", "such", "program").toString()), 5);
                check(false, "a missing program should fail");
            } catch (IOException expected) {
                check(true, "");
            }
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
