/*
 * Copy the logs and configuration files off an FTC robot with adb.
 *
 * Pulls the Robot Controller log (and its rotated copies), match logs, the robot
 * configuration XML files, and a dump of the live logcat buffer into a new folder on
 * this computer. It only reads from the robot.
 *
 * Runs straight from source with any JDK 11 or later. There is no build step and
 * nothing to install:
 *
 *     java PullRobotLogs.java
 *     java PullRobotLogs.java --out ./robot-logs --skip matchlogs,logcat
 *
 * stdout: the folder the files were saved in (one line). Progress goes to stderr.
 * Pass that folder to AnalyzeRobotLog.java.
 *
 * Exit codes:
 *     0  files saved
 *     2  bad arguments, or more than one device and none chosen
 *     3  adb not found, or the robot could not be reached
 *     4  connected, but no Robot Controller log was found on the device
 *
 * The code is written in the same Java 8 dialect as the robot code, with no libraries.
 */

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public final class PullRobotLogs {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_UNREACHABLE = 3;
    static final int EXIT_NO_LOG = 4;

    static final String ROBOT_ADDRESS = "192.168.43.1:5555";    // Control Hub on its own Wi-Fi network
    static final String LOG_DIR = "/sdcard";
    static final String LOG_NAME = "robotControllerLog.txt";
    static final String FIRST_DIR = "/sdcard/FIRST";
    static final String MATCH_LOG_DIR = "/sdcard/FIRST/matchlogs";
    static final List<String> SKIPPABLE = Arrays.asList("matchlogs", "configs", "logcat");

    static final String UNREACHABLE_HELP = "The robot could not be reached. One of these is the cause:\n"
            + "  1. This computer is not connected to the robot's Wi-Fi network.\n"
            + "  2. The robot is turned off, or the Control Hub is still starting up.\n"
            + "  3. Someone else may be connected to the robot.\n"
            + "Or plug a USB-C cable into the Control Hub and run this again.\n";

    static final String HELP = "usage: java PullRobotLogs.java [options]\n"
            + "\n"
            + "Copy Robot Controller logs and configuration files off an FTC robot with adb.\n"
            + "Prints the folder they were saved in. It only reads from the robot.\n"
            + "\n"
            + "options:\n"
            + "  --out DIR      save here (default: a new timestamped folder under ~/ftc-robot-logs)\n"
            + "  --serial NAME  device to use when several are attached (default: $ANDROID_SERIAL)\n"
            + "  --adb PATH     path to adb (default: found automatically)\n"
            + "  --no-connect   do not try 'adb connect " + ROBOT_ADDRESS + "' when nothing is attached\n"
            + "  --skip LIST    comma-separated extras to leave out: matchlogs, configs, logcat\n"
            + "  -h, --help     show this help\n"
            + "\n"
            + "Exit codes: 0 saved, 2 bad arguments or ambiguous device, 3 adb missing or robot\n"
            + "unreachable, 4 no Robot Controller log on the device.\n";

    private PullRobotLogs() {
    }

    // ----------------------------------------------------------------------
    // Running programs
    // ----------------------------------------------------------------------

    /** What one command produced. */
    static final class Result {
        final int code;
        final byte[] out;
        final String err;
        final boolean timedOut;

        Result(int code, byte[] out, String err, boolean timedOut) {
            this.code = code;
            this.out = out;
            this.err = err;
            this.timedOut = timedOut;
        }

        String text() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    /** Runs a program and waits for it. The tests swap in a stand-in for adb. */
    interface Runner {
        Result run(List<String> command, int timeoutSeconds) throws IOException;
    }

    /** Runs a real program. Output goes to temporary files, so a large logcat dump cannot block. */
    static final class ProcessRunner implements Runner {
        @Override
        public Result run(List<String> command, int timeoutSeconds) throws IOException {
            File outFile = File.createTempFile("pull-robot-logs-out", ".tmp");
            File errFile = File.createTempFile("pull-robot-logs-err", ".tmp");
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectOutput(outFile);
                builder.redirectError(errFile);
                Process process = builder.start();
                process.getOutputStream().close();          // nothing to type into the program
                boolean finished;
                try {
                    finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                byte[] out = Files.readAllBytes(outFile.toPath());
                String err = new String(Files.readAllBytes(errFile.toPath()), StandardCharsets.UTF_8).trim();
                return new Result(finished ? process.exitValue() : -1, out, err, !finished);
            } finally {
                outFile.delete();
                errFile.delete();
            }
        }
    }

    static final class AdbException extends Exception {
        private static final long serialVersionUID = 1L;

        AdbException(String message) {
            super(message);
        }
    }

    /** More than one device is attached and nothing says which to use. */
    static final class AmbiguousDeviceException extends Exception {
        private static final long serialVersionUID = 1L;

        AmbiguousDeviceException(String message) {
            super(message);
        }
    }

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    // ----------------------------------------------------------------------
    // Locating adb
    // ----------------------------------------------------------------------

    /** Where the tool looks things up. The tests replace these. */
    static final class Context {
        Runner runner = new ProcessRunner();
        Map<String, String> env = System.getenv();
        String home = System.getProperty("user.home");
        Path workingDir = Paths.get("").toAbsolutePath();
        boolean windows = File.separatorChar == '\\';
    }

    /** Reads an environment variable. Windows names are not case-sensitive, so "Path" matches PATH. */
    static String env(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value == null || value.isEmpty() ? null : value;
    }

    /** Reads sdk.dir from the nearest local.properties at or above the start folder. */
    static String sdkDirFromLocalProperties(Path start) {
        Path folder = start.toAbsolutePath().normalize();
        while (folder != null) {
            Path file = folder.resolve("local.properties");
            if (Files.isRegularFile(file)) {
                // Properties understands the escaping Android Studio writes on Windows (C\:\\Users\\...).
                try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                    Properties properties = new Properties();
                    properties.load(reader);
                    String value = properties.getProperty("sdk.dir");
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                } catch (IOException | IllegalArgumentException unreadable) {
                    // keep looking in the folders above
                }
            }
            folder = folder.getParent();
        }
        return null;
    }

    private static void addCandidate(List<Path> candidates, String first, String... more) {
        if (first == null || first.isEmpty()) {
            return;
        }
        try {
            candidates.add(Paths.get(first, more));
        } catch (InvalidPathException ignored) {
            // not a usable path on this computer
        }
    }

    /** Finds adb, or returns null. An explicit path wins, then $ADB, then PATH, then the Android SDK. */
    static Path findAdb(String explicit, Context context) {
        String exe = context.windows ? "adb.exe" : "adb";
        List<Path> candidates = new ArrayList<Path>();
        if (explicit != null && !explicit.isEmpty()) {
            Path given = null;
            try {
                given = context.workingDir.resolve(explicit);
            } catch (InvalidPathException ignored) {
                // falls through to the other places
            }
            if (given != null) {
                candidates.add(given);
            }
        }
        addCandidate(candidates, env(context.env, "ADB"));
        String path = env(context.env, "PATH");
        if (path != null) {
            for (String folder : path.split(File.pathSeparator)) {
                addCandidate(candidates, folder, exe);
            }
        }
        List<String> sdkDirs = new ArrayList<String>();
        sdkDirs.add(env(context.env, "ANDROID_HOME"));
        sdkDirs.add(env(context.env, "ANDROID_SDK_ROOT"));
        sdkDirs.add(sdkDirFromLocalProperties(context.workingDir));
        if (context.home != null && !context.home.isEmpty()) {
            sdkDirs.add(context.home + File.separator + "Library" + File.separator + "Android" + File.separator + "sdk");
        }
        String localAppData = env(context.env, "LOCALAPPDATA");
        if (localAppData != null) {
            sdkDirs.add(localAppData + File.separator + "Android" + File.separator + "Sdk");
        }
        if (context.home != null && !context.home.isEmpty()) {
            sdkDirs.add(context.home + File.separator + "Android" + File.separator + "Sdk");
        }
        for (String sdk : sdkDirs) {
            addCandidate(candidates, sdk, "platform-tools", exe);
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // Talking to adb
    // ----------------------------------------------------------------------

    static final class Adb {
        final String path;
        final Runner runner;
        String serial;

        Adb(String path, Runner runner) {
            this.path = path;
            this.runner = runner;
        }

        /** Runs adb with the given arguments, adding "-s serial" when a device is chosen and wanted. */
        Result run(int timeoutSeconds, boolean forDevice, String... args) throws AdbException {
            List<String> command = new ArrayList<String>();
            command.add(path);
            if (forDevice && serial != null) {
                command.add("-s");
                command.add(serial);
            }
            command.addAll(Arrays.asList(args));
            Result result;
            try {
                result = runner.run(command, timeoutSeconds);
            } catch (IOException error) {
                throw new AdbException("could not run " + path + ": " + error.getMessage());
            }
            if (result.timedOut) {
                throw new AdbException("adb " + join(Arrays.asList(args), " ") + " timed out after "
                        + timeoutSeconds + "s");
            }
            return result;
        }

        /** Attached devices and their states, for example {"192.168.43.1:5555": "device"}. */
        Map<String, String> devices() throws AdbException {
            Map<String, String> found = new LinkedHashMap<String, String>();
            for (String line : lines(run(15, false, "devices").text())) {
                String[] parts = line.trim().split("\\s+");
                // Skip the "List of devices attached" header and "* daemon started" notices.
                if (parts.length >= 2 && !line.startsWith("*") && !line.startsWith("List of devices")) {
                    found.put(parts[0], parts[1]);
                }
            }
            return found;
        }

        List<String> ready() throws AdbException {
            List<String> ready = new ArrayList<String>();
            for (Map.Entry<String, String> device : devices().entrySet()) {
                if (device.getValue().equals("device")) {
                    ready.add(device.getKey());
                }
            }
            return ready;
        }

        List<String> listDir(String folder) throws AdbException {
            Result result = run(20, true, "shell", "ls", folder);
            List<String> names = new ArrayList<String>();
            String text = result.text();
            if (result.code != 0 || text.contains("No such file")) {
                return names;
            }
            for (String name : lines(text)) {
                if (!name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
            Collections.sort(names);
            return names;
        }
    }

    /** Returns the serial to use, connecting over Wi-Fi if nothing is attached. */
    static String chooseDevice(Adb adb, String wanted, boolean connect, PrintStream log)
            throws AdbException, AmbiguousDeviceException {
        List<String> ready = adb.ready();
        boolean chosen = wanted != null && !wanted.isEmpty();
        if (ready.isEmpty() && connect && !chosen) {
            log.print("no device attached; trying adb connect " + ROBOT_ADDRESS + "\n");
            try {
                Result result = adb.run(15, false, "connect", ROBOT_ADDRESS);
                String said = result.text().trim();
                log.print("  adb: " + (said.isEmpty() ? result.err : said) + "\n");
            } catch (AdbException error) {
                log.print("  " + error.getMessage() + "\n");
            }
            ready = adb.ready();
        }
        if (chosen) {
            if (ready.contains(wanted)) {
                return wanted;
            }
            String state = adb.devices().get(wanted);
            throw new AdbException("device " + wanted + " is " + (state == null ? "not attached" : state));
        }
        if (ready.isEmpty()) {
            Map<String, String> states = adb.devices();
            if (!states.isEmpty()) {
                List<String> described = new ArrayList<String>();
                for (Map.Entry<String, String> device : states.entrySet()) {
                    described.add(device.getKey() + " is " + device.getValue());
                }
                throw new AdbException("device attached but not usable: " + join(described, ", "));
            }
            throw new AdbException("no device");
        }
        if (ready.size() == 1) {
            return ready.get(0);
        }
        if (ready.contains(ROBOT_ADDRESS)) {
            return ROBOT_ADDRESS;
        }
        throw new AmbiguousDeviceException("more than one device is attached (" + join(ready, ", ")
                + "); choose one with --serial");
    }

    static boolean pull(Adb adb, String remote, Path folder, PrintStream log) throws AdbException {
        Result result = adb.run(120, true, "pull", remote, folder.toString());
        if (result.code != 0) {
            log.print("  could not pull " + remote + ": " + (result.err.isEmpty() ? result.text().trim() : result.err)
                    + "\n");
            return false;
        }
        return true;
    }

    private static List<String> endingWith(List<String> names, String suffix) {
        List<String> kept = new ArrayList<String>();
        for (String name : names) {
            if (name.endsWith(suffix)) {
                kept.add(name);
            }
        }
        return kept;
    }

    // ----------------------------------------------------------------------
    // Command line
    // ----------------------------------------------------------------------

    static final class Options {
        String out;
        String serial;
        String adb;
        boolean noConnect;
        final TreeSet<String> skip = new TreeSet<String>();
        boolean help;
    }

    static Options parseArgs(String[] argv, Map<String, String> env) throws UsageException {
        Options options = new Options();
        options.serial = env(env, "ANDROID_SERIAL");
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (token.equals("-h") || token.equals("--help")) {
                options.help = true;
                continue;
            }
            if (token.equals("--no-connect")) {
                options.noConnect = true;
                continue;
            }
            String name = token;
            String value = null;
            int equals = token.indexOf('=');
            if (token.startsWith("--") && equals > 0) {
                name = token.substring(0, equals);
                value = token.substring(equals + 1);
            }
            if (!Arrays.asList("--out", "--serial", "--adb", "--skip").contains(name)) {
                throw new UsageException("unknown argument " + token);
            }
            if (value == null) {
                if (i + 1 >= argv.length) {
                    throw new UsageException(name + " needs a value");
                }
                value = argv[++i];
            }
            if (name.equals("--out")) {
                options.out = value;
            } else if (name.equals("--serial")) {
                options.serial = value;
            } else if (name.equals("--adb")) {
                options.adb = value;
            } else {
                for (String item : value.split(",")) {
                    if (!item.trim().isEmpty()) {
                        options.skip.add(item.trim());
                    }
                }
            }
        }
        TreeSet<String> unknown = new TreeSet<String>(options.skip);
        unknown.removeAll(SKIPPABLE);
        if (!unknown.isEmpty()) {
            throw new UsageException("--skip does not know: " + join(unknown, ", ") + " (it knows "
                    + join(SKIPPABLE, ", ") + ")");
        }
        return options;
    }

    /** Runs the command line, printing to the given streams, and returns the exit code. */
    static int run(String[] argv, PrintStream out, PrintStream log, Context context) {
        Options options;
        try {
            options = parseArgs(argv, context.env);
        } catch (UsageException error) {
            log.print("error: " + error.getMessage() + "\n");
            return EXIT_USAGE;
        }
        if (options.help) {
            out.print(HELP);
            return EXIT_OK;
        }

        Path adbPath = findAdb(options.adb, context);
        if (adbPath == null) {
            log.print("error: adb not found. It ships with the Android SDK in platform-tools; pass --adb PATH "
                    + "or open the project in Android Studio once so local.properties points at the SDK.\n");
            return EXIT_UNREACHABLE;
        }
        Adb adb = new Adb(adbPath.toString(), context.runner);
        log.print("using " + adbPath + "\n");

        try {
            adb.serial = chooseDevice(adb, options.serial, !options.noConnect, log);
        } catch (AmbiguousDeviceException error) {
            log.print("error: " + error.getMessage() + "\n");
            return EXIT_USAGE;
        } catch (AdbException error) {
            log.print("error: " + error.getMessage() + "\n");
            log.print(UNREACHABLE_HELP);
            return EXIT_UNREACHABLE;
        }
        log.print("device: " + adb.serial + "\n");

        Path folder;
        int saved = 0;
        try {
            List<String> names = new ArrayList<String>();
            for (String name : adb.listDir(LOG_DIR)) {
                if (name.startsWith(LOG_NAME)) {
                    names.add(name);
                }
            }
            if (names.isEmpty()) {
                log.print("error: no " + LOG_NAME + " in " + LOG_DIR + " on the device. Is this a Robot Controller?\n");
                return EXIT_NO_LOG;
            }

            try {
                if (options.out != null && !options.out.isEmpty()) {
                    folder = context.workingDir.resolve(options.out);
                } else {
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                    folder = Paths.get(context.home, "ftc-robot-logs", stamp);
                }
                folder = folder.toAbsolutePath().normalize();
                Files.createDirectories(folder);
            } catch (IOException | InvalidPathException error) {
                log.print("error: cannot create the output folder: " + error.getMessage() + "\n");
                return EXIT_USAGE;
            }

            for (String name : names) {
                log.print("pulling " + LOG_DIR + "/" + name + "\n");
                saved += pull(adb, LOG_DIR + "/" + name, folder, log) ? 1 : 0;
            }

            if (!options.skip.contains("matchlogs")) {
                saved += pullAll(adb, MATCH_LOG_DIR, ".txt", folder.resolve("matchlogs"), "match logs", log);
            }
            if (!options.skip.contains("configs")) {
                saved += pullAll(adb, FIRST_DIR, ".xml", folder.resolve("configs"), "robot configuration files", log);
            }
            if (!options.skip.contains("logcat")) {
                // -d dumps the buffer and exits; without it logcat never returns.
                Result dump = adb.run(60, true, "logcat", "-d", "-v", "threadtime");
                if (dump.code == 0 && dump.out.length > 0) {
                    try {
                        Files.write(folder.resolve("logcat-live.txt"), dump.out);
                        saved++;
                        log.print("saved the live logcat buffer\n");
                    } catch (IOException error) {
                        log.print("  could not save the logcat dump: " + error.getMessage() + "\n");
                    }
                } else {
                    log.print("  could not dump logcat: " + dump.err + "\n");
                }
            }
        } catch (AdbException error) {
            log.print("error: " + error.getMessage() + "\n");
            log.print(UNREACHABLE_HELP);
            return EXIT_UNREACHABLE;
        }

        if (saved == 0) {
            log.print("error: nothing could be copied from the device\n");
            return EXIT_NO_LOG;
        }
        log.print("saved " + saved + " files\n");
        out.print(folder + "\n");
        return EXIT_OK;
    }

    /** Pulls every file in a device folder whose name has the suffix. Returns how many were saved. */
    private static int pullAll(Adb adb, String remoteDir, String suffix, Path target, String what, PrintStream log)
            throws AdbException {
        List<String> names = endingWith(adb.listDir(remoteDir), suffix);
        if (names.isEmpty()) {
            return 0;
        }
        try {
            Files.createDirectories(target);
        } catch (IOException error) {
            log.print("  could not create " + target + ": " + error.getMessage() + "\n");
            return 0;
        }
        int saved = 0;
        for (String name : names) {
            saved += pull(adb, remoteDir + "/" + name, target, log) ? 1 : 0;
        }
        log.print("pulled " + names.size() + " " + what + "\n");
        return saved;
    }

    // ----------------------------------------------------------------------
    // Small helpers
    // ----------------------------------------------------------------------

    static List<String> lines(String text) {
        return Arrays.asList(text.split("\\r\\n|\\r|\\n"));
    }

    static String join(Iterable<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(out.length() == 0 ? "" : separator).append(part);
        }
        return out.toString();
    }

    /** Windows consoles default to a legacy code page; always write UTF-8. */
    static PrintStream utf8(OutputStream stream) {
        try {
            return new PrintStream(stream, true, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return new PrintStream(stream, true);
        }
    }

    public static void main(String[] argv) {
        PrintStream out = utf8(new FileOutputStream(FileDescriptor.out));
        PrintStream log = utf8(new FileOutputStream(FileDescriptor.err));
        int code = run(argv, out, log, new Context());
        out.flush();
        log.flush();
        System.exit(code);
    }
}
