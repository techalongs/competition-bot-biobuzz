/*
 * Find what went wrong in an FTC Robot Controller log.
 *
 * Reads robotControllerLog.txt, match logs, or `adb logcat -d` output and reports
 * crashes in team code, stuck OpModes, power loss and low battery, hub communication
 * trouble, Driver Station disconnects, and slow loops (when the team logs LoopStats
 * lines). Crash stack traces are mapped to source files.
 *
 * Runs straight from source with any JDK 11 or later. There is no build step and
 * nothing to install:
 *
 *     java AnalyzeRobotLog.java ~/ftc-robot-logs/20261116-181500     (a folder from PullRobotLogs.java)
 *     java AnalyzeRobotLog.java robotControllerLog.txt --json
 *     adb logcat -d | java AnalyzeRobotLog.java -
 *
 * Exit codes:
 *     0  log parsed, nothing serious found
 *     1  log parsed, problems found
 *     2  bad arguments, unreadable input, or no log lines recognized
 *
 * The message patterns come from the FTC SDK 12.0 source. The code is written in the
 * same Java 8 dialect as the robot code, with no libraries. To teach it a new
 * problem, add a line to the RULES table below.
 */

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnalyzeRobotLog {

    static final int EXIT_CLEAN = 0;
    static final int EXIT_FINDINGS = 1;
    static final int EXIT_INPUT = 2;

    static final String DEFAULT_PACKAGE = "org.firstinspires.ftc.teamcode";
    static final String STOP_OPMODE = "$Stop$Robot$";

    // logcat "threadtime": 11-15 18:22:01.123  1234  1300 E Tag: message
    static final Pattern THREADTIME = Pattern.compile(
            "^(\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*: ?(.*)$");
    // logcat "brief": E/Tag( 1234): message
    static final Pattern BRIEF = Pattern.compile("^([VDIWEFA])/(.*?)\\(\\s*(\\d+)\\): ?(.*)$");
    // The SDK prefixes messages with "{ offset ss.mmm} " once a Driver Station has set the clock offset.
    static final Pattern CLOCK_PREFIX = Pattern.compile("^\\{\\s*-?\\d+\\s+\\d+\\.\\d{3}\\}\\s");
    static final Pattern STACK_LINE = Pattern.compile("^\\s*(at\\s|Caused by:|Suppressed:|\\.\\.\\. \\d+ more)");
    static final Pattern EXCEPTION_LINE = Pattern.compile(
            "^((?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error|Throwable)[\\w$]*)(?::\\s*(.*))?$");
    static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+([\\w.$<>]+)\\.([\\w$<>]+)\\(([^:()]+)(?::(\\d+))?\\)");

    static final Pattern OPMODE_SWITCH = Pattern.compile("Attempting to switch to OpMode (.*)");
    static final Pattern APP_INFO = Pattern.compile("App info: version=(\\S+) appId=(\\S+)");
    static final Pattern THREAD_PAUSE = Pattern.compile("Suspending all threads took: ([\\d.]+)ms");
    static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=([^\\s]+)");
    static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    static final Pattern ROTATION = Pattern.compile("\\.(\\d+)$");
    static final Pattern DIGITS = Pattern.compile("\\d+");

    private AnalyzeRobotLog() {
    }

    // ----------------------------------------------------------------------
    // What to look for
    // ----------------------------------------------------------------------

    /** One known problem. Severity 1 stops the robot; 2 degrades it; 3 is context worth knowing. */
    static final class Rule {
        final String category;
        final int severity;
        final Pattern tag;          // null matches any tag
        final Pattern message;      // may name the groups opmode, method, hub
        final String title;
        final String hint;

        Rule(String category, int severity, String tag, String message, String title, String hint) {
            this.category = category;
            this.severity = severity;
            this.tag = tag == null ? null : Pattern.compile(tag);
            this.message = Pattern.compile(message);
            this.title = title;
            this.hint = hint;
        }
    }

    static final List<Rule> RULES = Arrays.asList(
            new Rule("stuck", 1, null,
                    "OpMode '(?<opmode>.*)' stuck in (?<method>[\\w()]+)\\. Restarting robot controller app",
                    "OpMode stuck; the Robot Controller app was restarted",
                    "A method blocked for too long. Look for a loop, sleep, or wait inside the named method, "
                            + "or a loop in a LinearOpMode that never checks opModeIsActive()."),
            new Rule("stuck", 1, null,
                    "User OpMode was stuck in (?<method>[\\w()]+), but was able to be force stopped",
                    "OpMode stuck and force-stopped",
                    "Same cause as a stuck OpMode: blocking code in the named method."),
            new Rule("stuck", 1, null, "took too long to exit; emergency killing app",
                    "A thread would not stop; the app was killed",
                    "Look for a thread or loop that ignores interruption when the OpMode stops."),
            new Rule("power", 1, "^HubPowerCycle$", "(?<hub>.*) regained power after a complete power loss",
                    "A hub lost power completely and came back",
                    "Check the battery connector, power switch, and XT30 wiring for a loose contact, then "
                            + "battery health. A hard current spike from stalled motors can also do this."),
            new Rule("power", 1, null, "(?<hub>.*) currently has a battery too low to run motors and servos",
                    "Battery too low to run motors and servos",
                    "Charge or replace the battery. If it was charged, look for a load that drags the voltage down."),
            new Rule("power", 1, null, "BATTERY_TOO_LOW_TO_RUN_(?:MOTOR|SERVO)",
                    "A hub refused a command because battery voltage was too low",
                    "Same as low battery: voltage sagged below what the hub needs."),
            new Rule("hub", 2, null, "Unable to communicate with REV Hub #(?<hub>\\d+) at robot startup",
                    "A REV hub did not answer at startup",
                    "Check the RS485 or USB cable to that hub, its power, and its address in the configuration."),
            new Rule("hub", 2, null, "module #(?<hub>\\d+) did not connect at startup",
                    "A configured hub was missing at startup; its devices were left out of the hardwareMap",
                    "Every device on that hub will be missing. Fix the cable or the configuration first."),
            new Rule("hub", 2, null, "(?<hub>.*) is currently unresponsive\\.",
                    "A hub stopped responding",
                    "Usually a cable (RS485 or USB), static discharge, or a power dip."),
            new Rule("hub", 2, null, "Marking module #(?<hub>\\d+) as unresponsive",
                    "A hub was marked unresponsive",
                    "Usually a cable (RS485 or USB), static discharge, or a power dip."),
            new Rule("hub", 3, null, "REV Hub #(?<hub>\\d+) has reconnected",
                    "A hub reconnected", "It dropped out before this line. Look at what came just before."),
            new Rule("hub", 2, null, "timeout: abandoning waiting \\d+ms for",
                    "A hub command timed out",
                    "Repeated timeouts point to cabling or a hub that is resetting."),
            new Rule("hub", 2, null, "#### abandoning lock",
                    "The hub transmit lock was abandoned",
                    "A command to a hub hung. Often follows a disconnect or a blocked thread."),
            new Rule("hub", 2, null, "event loop: device has shutdown abnormally",
                    "A USB device shut down abnormally", "Check USB connections and static protection."),
            new Rule("hub", 3, null, "event loop: (?:detaching|auto-reattaching) device",
                    "A USB device was detached or reattached", "Check USB connections."),
            new Rule("hub", 2, null, "I2C_TIMEOUT_(?:SDA_STUCK|SCK_STUCK|UNKNOWN_CAUSE)",
                    "An I2C bus timed out",
                    "Check the sensor's cable and port. A stuck SDA or SCK line is a wiring or sensor fault."),
            new Rule("disconnect", 2, null, "Lost connection while running OpMode: (?<opmode>.*)",
                    "Driver Station connection lost while an OpMode was running; the robot was stopped",
                    "Wi-Fi interference, channel choice, distance, or a Driver Station problem. Compare the time "
                            + "with the Driver Station log."),
            new Rule("disconnect", 3, null, "Lost connection while main event loop not active",
                    "Driver Station connection lost while idle", "Only matters if it keeps happening."));

    /** The log line that starts a crash: tag pattern, message pattern, title. */
    static final String[][] CRASH_HEADERS = {
            {"^OpModeManager$", "User code threw an uncaught exception", "Team code threw an uncaught exception"},
            {"^AndroidRuntime$", "FATAL EXCEPTION", "The Robot Controller app crashed"},
            {"^EventLoopManager$", "Event loop threw an exception", "The event loop threw an exception"},
    };

    /** Advice for common exceptions: pattern, hint. %s in the hint is the pattern's first group. */
    static final String[][] CRASH_HINTS = {
            {"Unable to find a hardware device with (?:the )?name \"(.*?)\"",
                    "The name '%s' is not in the active robot configuration, or is a different device type. "
                            + "Compare the string in code with the configuration on the Control Hub."},
            {"NullPointerException",
                    "Something was used before it was created. Check that the object is assigned in init "
                            + "before the line shown, and that hardwareMap lookups succeeded."},
            {"TargetPositionNotSetException",
                    "Set a target position before switching the motor to RUN_TO_POSITION."},
            {"IndexOutOfBounds",
                    "A list or array was indexed past its end. Check for an empty list, such as no detections."},
    };

    static final List<String> CATEGORY_ORDER = Arrays.asList("crash", "stuck", "power", "hub", "disconnect", "loop");

    // ----------------------------------------------------------------------
    // Data
    // ----------------------------------------------------------------------

    static final class InputException extends Exception {
        private static final long serialVersionUID = 1L;

        InputException(String message) {
            super(message);
        }
    }

    /** One parsed log line. */
    static final class Line {
        final String file;
        final int number;
        final String stamp;         // "MM-DD HH:MM:SS.mmm", or "" when the format has no time
        final String pid;
        final String tid;
        final String level;
        final String tag;
        final String message;       // without the SDK's clock prefix
        final String raw;

        Line(String file, int number, String stamp, String pid, String tid, String level, String tag,
             String message, String raw) {
            this.file = file;
            this.number = number;
            this.stamp = stamp;
            this.pid = pid;
            this.tid = tid;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.raw = raw;
        }
    }

    static final class FileInfo {
        String file;
        int lines;
        String first;
        String last;
    }

    static final class Frame {
        String className;
        String method;
        String file;
        Integer line;           // null for frames without a line number
        boolean team;
        String path;            // source file in this checkout, when found

        String place() {
            return className + "." + method + "(" + file + (line == null ? "" : ":" + line) + ")";
        }
    }

    /** One occurrence of a problem. */
    static final class Event {
        String category;
        int severity;
        String title;
        String detail;
        String hint;
        String signature;       // occurrences with the same signature are reported as one finding
        String time;
        String file;
        Integer lineNumber;
        String opmode;
        String raw;
        // crashes only
        boolean crash;
        String exception;
        String message;
        String outerException;
        List<Frame> teamFrames = new ArrayList<Frame>();
        Frame firstFrame;
    }

    /** Every occurrence of the same problem, reported once. */
    static final class Finding {
        final Event event;      // the first occurrence
        int count;
        String first;
        String last;
        final List<String> opmodes = new ArrayList<String>();

        Finding(Event event) {
            this.event = event;
            this.first = event.time;
        }
    }

    static final class LoopReport {
        String opmode;
        int samples;
        double avgMs;
        double maxMs;
        Double minBattV;
        String first;
        String last;
        boolean slow;
        boolean lowVoltage;
    }

    static final class Pause {
        final String time;
        final double ms;

        Pause(String time, double ms) {
            this.time = time;
            this.ms = ms;
        }
    }

    static final class OtherError {
        final String line;
        final String first;
        int count;

        OtherError(String line, String first) {
            this.line = line;
            this.first = first;
        }
    }

    static final class Analysis {
        final List<Event> events = new ArrayList<Event>();
        final Map<String, Integer> opmodesRun = new LinkedHashMap<String, Integer>();
        final List<String> appStarts = new ArrayList<String>();
        String sdkVersion;
        final List<LoopReport> loop = new ArrayList<LoopReport>();
        final List<Pause> pauses = new ArrayList<Pause>();
        List<OtherError> otherErrors = new ArrayList<OtherError>();
    }

    /** Everything the report is built from. */
    static final class Report {
        List<FileInfo> files;
        Analysis analysis;
        List<Finding> findings;
        boolean haveSources;

        List<Finding> serious() {
            List<Finding> serious = new ArrayList<Finding>();
            for (Finding finding : findings) {
                if (finding.event.severity <= 2) {
                    serious.add(finding);
                }
            }
            return serious;
        }
    }

    static final class Options {
        final List<String> logs = new ArrayList<String>();
        String teamPackage = DEFAULT_PACKAGE;
        String sourceRoot;
        double loopAvgMs = 25.0;
        double loopMaxMs = 100.0;
        double lowVoltage = 10.0;
        int other = 8;
        boolean json;
        boolean help;
    }

    // ----------------------------------------------------------------------
    // Reading
    // ----------------------------------------------------------------------

    /** Parses one line of logcat output, or returns null when it is not a log line. */
    static Line parseLine(String file, int number, String raw) {
        Matcher match = THREADTIME.matcher(raw);
        String stamp;
        String pid;
        String tid;
        String level;
        String tag;
        String message;
        if (match.matches()) {
            stamp = match.group(1) + " " + match.group(2);
            pid = match.group(3);
            tid = match.group(4);
            level = match.group(5);
            tag = match.group(6);
            message = match.group(7);
        } else {
            match = BRIEF.matcher(raw);
            if (!match.matches()) {
                return null;
            }
            level = match.group(1);
            tag = match.group(2);
            pid = match.group(3);
            message = match.group(4);
            stamp = "";
            tid = pid;
        }
        return new Line(file, number, stamp, pid, tid, level, tag.trim(),
                CLOCK_PREFIX.matcher(message).replaceFirst(""), raw);
    }

    static List<Line> parseLines(String name, BufferedReader reader) throws IOException {
        List<Line> lines = new ArrayList<Line>();
        int number = 0;
        String raw;
        while ((raw = reader.readLine()) != null) {
            number++;
            Line line = parseLine(name, number, raw);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Orders rotated logs oldest first: name.txt.4 ... name.txt.1, name.txt. */
    static final Comparator<String> ROTATION_ORDER = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            int byName = base(a).compareTo(base(b));
            return byName != 0 ? byName : Long.compare(order(a), order(b));
        }

        private String base(String path) {
            Matcher match = ROTATION.matcher(path);
            return match.find() ? path.substring(0, match.start()) : path;
        }

        private long order(String path) {
            Matcher match = ROTATION.matcher(path);
            if (!match.find()) {
                return 1;
            }
            try {
                return -Long.parseLong(match.group(1));
            } catch (NumberFormatException tooLong) {
                return Long.MIN_VALUE;
            }
        }
    };

    static boolean hasWildcard(String path) {
        return path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0;
    }

    private static Path resolve(Path workingDir, String path) {
        try {
            return workingDir.resolve(path);
        } catch (InvalidPathException error) {      // for example a * in a Windows path
            return null;
        }
    }

    private static String joinPath(String folder, String name) {
        return folder.endsWith("/") || folder.endsWith("\\") ? folder + name : folder + "/" + name;
    }

    private static List<String> namesIn(Path folder, String glob) {
        List<String> names = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, glob)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && !name.startsWith(".")) {
                    names.add(name);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return new ArrayList<String>();
        }
        return names;
    }

    /**
     * Turns folders and wildcard patterns into file lists. Windows shells pass wildcards
     * through unexpanded, so the tool has to do it. Wildcards work in the file name only.
     */
    static List<String> expandInputs(List<String> paths, Path workingDir) {
        List<String> expanded = new ArrayList<String>();
        for (String path : paths) {
            Path resolved = path.equals("-") ? null : resolve(workingDir, path);
            if (path.equals("-")) {
                expanded.add(path);
            } else if (resolved != null && Files.isDirectory(resolved)) {
                List<String> found = namesIn(resolved, "robotControllerLog.txt*");
                if (found.isEmpty()) {
                    found = namesIn(resolved, "*");
                }
                for (String name : found) {
                    expanded.add(joinPath(path, name));
                }
            } else if (hasWildcard(path) && (resolved == null || !Files.exists(resolved))) {
                int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                String folder = cut < 0 ? "" : path.substring(0, cut + 1);
                Path folderPath = folder.isEmpty() ? workingDir : resolve(workingDir, folder);
                List<String> found = folderPath == null || !Files.isDirectory(folderPath)
                        ? new ArrayList<String>() : namesIn(folderPath, path.substring(cut + 1));
                if (found.isEmpty()) {
                    expanded.add(path);
                }
                for (String name : found) {
                    expanded.add(folder + name);
                }
            } else {
                expanded.add(path);
            }
        }
        return expanded;
    }

    static List<Line> readInputs(List<String> given, Path workingDir, InputStream stdin, List<FileInfo> files)
            throws InputException {
        List<String> paths = expandInputs(given, workingDir);
        List<String> ordered = new ArrayList<String>();
        for (String path : paths) {
            if (!path.equals("-")) {
                ordered.add(path);
            }
        }
        Collections.sort(ordered, ROTATION_ORDER);
        if (paths.contains("-")) {
            ordered.add("-");
        }
        List<Line> lines = new ArrayList<Line>();
        for (String path : ordered) {
            List<Line> parsed;
            try {
                if (path.equals("-")) {
                    parsed = parseLines("<stdin>", new BufferedReader(
                            new InputStreamReader(stdin, StandardCharsets.UTF_8)));
                } else {
                    Path file = resolve(workingDir, path);
                    if (file == null) {
                        throw new IOException("not a usable file name");
                    }
                    // InputStreamReader replaces bytes that are not valid UTF-8 instead of failing.
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                        parsed = parseLines(file.getFileName().toString(), reader);
                    }
                }
            } catch (IOException error) {
                throw new InputException("cannot read " + path + ": " + describe(error));
            }
            FileInfo info = new FileInfo();
            info.file = path;
            info.lines = parsed.size();
            info.first = parsed.isEmpty() ? null : parsed.get(0).stamp;
            info.last = parsed.isEmpty() ? null : parsed.get(parsed.size() - 1).stamp;
            files.add(info);
            lines.addAll(parsed);
        }
        return lines;
    }

    private static String describe(IOException error) {
        String name = error.getClass().getSimpleName();
        if (name.equals("NoSuchFileException")) {
            return "no such file";
        }
        if (name.equals("AccessDeniedException")) {
            return "permission denied";
        }
        return error.getMessage() == null ? name : error.getMessage();
    }

    // ----------------------------------------------------------------------
    // Crashes
    // ----------------------------------------------------------------------

    /** Collects the lines after a crash header that belong to its stack trace; returns the next index. */
    static int collectStack(List<Line> lines, int start, List<String> collected) {
        Line header = lines.get(start);
        int index = start + 1;
        while (index < lines.size() && collected.size() < 300) {
            Line line = lines.get(index);
            if (!line.tag.equals(header.tag) || !line.pid.equals(header.pid)) {
                break;
            }
            String text = line.message;
            if (STACK_LINE.matcher(text).find() || EXCEPTION_LINE.matcher(text.trim()).matches()
                    || text.startsWith("Process: ")) {
                collected.add(text);
                index++;
                continue;
            }
            break;
        }
        return index;
    }

    /** Maps a stack frame to a file in the checkout, or returns null. */
    static String sourcePath(String frameClass, String fileName, List<Path> roots, Path workingDir) {
        int lastDot = frameClass.lastIndexOf('.');
        String packageDir = lastDot < 0 ? "" : frameClass.substring(0, lastDot).replace('.', '/');
        for (Path root : roots) {
            Path candidate;
            try {
                candidate = root.resolve(packageDir).resolve(fileName);
            } catch (InvalidPathException error) {
                continue;
            }
            if (Files.isRegularFile(candidate)) {
                Path shown = candidate;
                try {
                    shown = workingDir.toAbsolutePath().normalize().relativize(candidate.toAbsolutePath().normalize());
                } catch (IllegalArgumentException differentDrive) {
                    // keep the full path
                }
                return shown.toString().replace('\\', '/');
            }
        }
        return null;
    }

    static Event describeCrash(String title, List<String> stack, String teamPackage, List<Path> roots,
                               Path workingDir) {
        List<String[]> exceptions = new ArrayList<String[]>();     // class name, message
        for (String text : stack) {
            String stripped = text.trim();
            if (stripped.startsWith("Caused by:")) {
                stripped = stripped.substring("Caused by:".length()).trim();
            }
            Matcher match = EXCEPTION_LINE.matcher(stripped);
            if (match.matches()) {
                exceptions.add(new String[] {match.group(1), match.group(2) == null ? "" : match.group(2)});
            }
        }
        List<Frame> frames = new ArrayList<Frame>();
        List<Frame> team = new ArrayList<Frame>();
        for (String text : stack) {
            Matcher match = FRAME.matcher(text);
            if (match.find()) {
                Frame frame = new Frame();
                frame.className = match.group(1);
                frame.method = match.group(2);
                frame.file = match.group(3);
                frame.line = match.group(4) == null ? null : parseInt(match.group(4));
                frame.team = frame.className.startsWith(teamPackage);
                frames.add(frame);
                if (frame.team) {
                    frame.path = sourcePath(frame.className, frame.file, roots, workingDir);
                    team.add(frame);
                }
            }
        }
        // The last "Caused by" is the root cause; fall back to the first exception.
        String[] root = exceptions.isEmpty() ? new String[] {"unknown exception", ""}
                : exceptions.get(exceptions.size() - 1);
        String[] top = exceptions.isEmpty() ? root : exceptions.get(0);
        String summary = root[1].isEmpty() ? root[0] : root[0] + ": " + root[1];
        String hint = null;
        for (String[] entry : CRASH_HINTS) {
            Pattern pattern = Pattern.compile(entry[0]);
            Matcher found = pattern.matcher(summary);
            if (!found.find()) {
                found = pattern.matcher(top[0] + ": " + top[1]);
                if (!found.find()) {
                    continue;
                }
            }
            hint = found.groupCount() > 0 ? String.format(Locale.ROOT, entry[1], found.group(1)) : entry[1];
            break;
        }
        if (hint == null && team.isEmpty()) {
            hint = "No team-code frame in the trace. The failure is inside the SDK or a library; "
                    + "look at what team code was doing just before this time.";
        }
        Frame where = !team.isEmpty() ? team.get(0) : (frames.isEmpty() ? null : frames.get(0));

        Event event = new Event();
        event.crash = true;
        event.category = "crash";
        event.severity = 1;
        event.title = title;
        event.detail = summary;
        event.exception = root[0];
        event.message = root[1];
        event.outerException = Arrays.equals(top, root) ? null : top[0];
        event.teamFrames = new ArrayList<Frame>(team.subList(0, Math.min(team.size(), 6)));
        event.firstFrame = frames.isEmpty() ? null : frames.get(0);
        event.hint = hint;
        event.signature = "crash|" + root[0] + "|"
                + (where == null ? "?" : where.className + "." + where.method + ":" + where.line);
        return event;
    }

    // ----------------------------------------------------------------------
    // Analysis
    // ----------------------------------------------------------------------

    /** Reads "key=value key=value" text. Numbers become Double; everything else stays a String. */
    static Map<String, Object> parseKeyValues(String text) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Matcher match = KEY_VALUE.matcher(text);
        while (match.find()) {
            String value = match.group(2);
            if (PLAIN_NUMBER.matcher(value).matches()) {
                values.put(match.group(1), Double.valueOf(value));
            } else {
                values.put(match.group(1), value);
            }
        }
        return values;
    }

    private static final class LoopTotals {
        int samples;
        double avgSum;
        double maxMs;
        Double minBattV;
        String first;
        String last;
    }

    static Analysis analyze(List<Line> lines, Options options, List<Path> roots, Path workingDir) {
        Analysis result = new Analysis();
        String opmode = null;
        Map<String, LoopTotals> loop = new TreeMap<String, LoopTotals>();
        Map<String, OtherError> other = new LinkedHashMap<String, OtherError>();

        int index = 0;
        while (index < lines.size()) {
            Line line = lines.get(index);
            String text = line.message;

            Matcher match = OPMODE_SWITCH.matcher(text);
            if (match.find()) {
                String name = match.group(1).trim();
                if (!name.equals(STOP_OPMODE)) {
                    opmode = name;
                    Integer count = result.opmodesRun.get(name);
                    result.opmodesRun.put(name, count == null ? 1 : count + 1);
                }
                index++;
                continue;
            }
            match = APP_INFO.matcher(text);
            if (match.find()) {
                result.sdkVersion = match.group(1);
                result.appStarts.add(line.stamp);
                index++;
                continue;
            }

            if (line.tag.equals("LoopStats")) {
                Map<String, Object> values = parseKeyValues(text);
                Object named = values.get("opmode");
                String name = named instanceof String ? (String) named : (opmode != null ? opmode : "?");
                LoopTotals entry = loop.get(name);
                if (entry == null) {
                    entry = new LoopTotals();
                    loop.put(name, entry);
                }
                if (values.get("avg_ms") instanceof Double && values.get("max_ms") instanceof Double) {
                    entry.samples++;
                    entry.avgSum += (Double) values.get("avg_ms");
                    entry.maxMs = Math.max(entry.maxMs, (Double) values.get("max_ms"));
                    if (entry.first == null || entry.first.isEmpty()) {
                        entry.first = line.stamp;
                    }
                    entry.last = line.stamp;
                    Object battery = values.get("batt_v");
                    if (battery instanceof Double && (Double) battery > 0) {
                        double volts = (Double) battery;
                        entry.minBattV = entry.minBattV == null ? volts : Math.min(entry.minBattV, volts);
                    }
                }
                index++;
                continue;
            }

            match = THREAD_PAUSE.matcher(text);
            if (match.find()) {
                try {
                    double ms = Double.parseDouble(match.group(1));
                    if (ms >= 50) {
                        result.pauses.add(new Pause(line.stamp, ms));
                    }
                } catch (NumberFormatException notANumber) {
                    // ignore a malformed duration
                }
            }

            boolean errorLevel = line.level.equals("E") || line.level.equals("F");
            String crashTitle = null;
            if (errorLevel) {
                for (String[] header : CRASH_HEADERS) {
                    if (Pattern.compile(header[0]).matcher(line.tag).find()
                            && Pattern.compile(header[1]).matcher(text).find()) {
                        crashTitle = header[2];
                        break;
                    }
                }
            }
            if (crashTitle != null) {
                List<String> stack = new ArrayList<String>();
                int next = collectStack(lines, index, stack);
                Event event = describeCrash(crashTitle, stack, options.teamPackage, roots, workingDir);
                place(event, line, opmode);
                result.events.add(event);
                index = next;
                continue;
            }

            boolean matched = false;
            for (Rule rule : RULES) {
                if (rule.tag != null && !rule.tag.matcher(line.tag).find()) {
                    continue;
                }
                Matcher found = rule.message.matcher(text);
                if (!found.find()) {
                    continue;
                }
                String hub = group(found, "hub");
                String method = group(found, "method");
                Event event = new Event();
                event.category = rule.category;
                event.severity = rule.severity;
                event.title = rule.title;
                event.detail = text.trim();
                event.hint = rule.hint;
                event.signature = rule.category + "|" + rule.title + "|"
                        + (!hub.isEmpty() ? hub : method);
                event.opmode = emptyToNull(group(found, "opmode"));
                place(event, line, opmode);
                result.events.add(event);
                matched = true;
                break;
            }
            if (!matched && errorLevel) {
                String normalized = DIGITS.matcher(text.trim()).replaceAll("#");
                if (normalized.length() > 160) {
                    normalized = normalized.substring(0, 160);
                }
                String key = line.level + " " + line.tag + ": " + normalized;
                OtherError entry = other.get(key);
                if (entry == null) {
                    entry = new OtherError(key, line.stamp);
                    other.put(key, entry);
                }
                entry.count++;
            }
            index++;
        }

        for (Map.Entry<String, LoopTotals> item : loop.entrySet()) {
            LoopTotals entry = item.getValue();
            if (entry.samples == 0) {
                continue;
            }
            String name = item.getKey();
            double average = entry.avgSum / entry.samples;
            LoopReport report = new LoopReport();
            report.opmode = name;
            report.samples = entry.samples;
            report.avgMs = round2(average);
            report.maxMs = round2(entry.maxMs);
            report.minBattV = entry.minBattV;
            report.first = entry.first;
            report.last = entry.last;
            report.slow = average > options.loopAvgMs || entry.maxMs > options.loopMaxMs;
            report.lowVoltage = entry.minBattV != null && entry.minBattV < options.lowVoltage;
            result.loop.add(report);
            if (report.slow) {
                Event event = new Event();
                event.category = "loop";
                event.severity = 2;
                event.title = "Slow control loop";
                event.opmode = name;
                event.detail = f("average %.1f ms, worst %.1f ms over %d samples (limits: average %.0f, worst %.0f)",
                        average, entry.maxMs, entry.samples, options.loopAvgMs, options.loopMaxMs);
                event.hint = "Look for extra hardware reads each loop, I2C sensors read every cycle, "
                        + "bulk caching left off, heavy telemetry, or allocation in the loop.";
                event.signature = "loop|" + name;
                event.time = entry.first;
                result.events.add(event);
            }
            if (report.lowVoltage) {
                Event event = new Event();
                event.category = "power";
                event.severity = 2;
                event.title = "Battery voltage sagged";
                event.opmode = name;
                event.detail = f("lowest logged battery voltage %.2f V (limit %.1f V)", entry.minBattV,
                        options.lowVoltage);
                event.hint = "Check battery charge and health, and what the robot was doing at the dip.";
                event.signature = "sag|" + name;
                event.time = entry.first;
                result.events.add(event);
            }
        }

        List<OtherError> ranked = new ArrayList<OtherError>(other.values());
        Collections.sort(ranked, new Comparator<OtherError>() {     // stable: ties stay in log order
            @Override
            public int compare(OtherError a, OtherError b) {
                return Integer.compare(b.count, a.count);
            }
        });
        result.otherErrors = ranked;
        return result;
    }

    private static void place(Event event, Line line, String currentOpmode) {
        event.time = line.stamp;
        event.file = line.file;
        event.lineNumber = line.number;
        if (event.opmode == null || event.opmode.isEmpty()) {
            event.opmode = currentOpmode;
        }
        event.raw = line.raw;
    }

    /** The named group's text, or "" when the pattern has no such group or it did not take part. */
    private static String group(Matcher match, String name) {
        try {
            String value = match.group(name);
            return value == null ? "" : value;
        } catch (IllegalArgumentException noSuchGroup) {
            return "";
        }
    }

    /** Collapses repeats of the same problem, most serious first. */
    static List<Finding> groupEvents(List<Event> events) {
        Map<String, Finding> groups = new LinkedHashMap<String, Finding>();
        for (Event event : events) {
            Finding finding = groups.get(event.signature);
            if (finding == null) {
                finding = new Finding(event);
                groups.put(event.signature, finding);
            }
            finding.count++;
            finding.last = event.time;
            if (event.opmode != null && !event.opmode.isEmpty() && !finding.opmodes.contains(event.opmode)) {
                finding.opmodes.add(event.opmode);
            }
        }
        List<Finding> ordered = new ArrayList<Finding>(groups.values());
        Collections.sort(ordered, new Comparator<Finding>() {       // stable: ties stay in log order
            @Override
            public int compare(Finding a, Finding b) {
                int bySeverity = Integer.compare(a.event.severity, b.event.severity);
                return bySeverity != 0 ? bySeverity : Integer.compare(rank(a), rank(b));
            }

            private int rank(Finding finding) {
                int rank = CATEGORY_ORDER.indexOf(finding.event.category);
                return rank < 0 ? 9 : rank;
            }
        });
        return ordered;
    }

    /** Finds the folders that hold the team's package tree: the nearest TeamCode/src/main/java going up. */
    static List<Path> findSourceRoots(String explicit, Path workingDir) {
        List<Path> roots = new ArrayList<Path>();
        if (explicit != null && !explicit.isEmpty()) {
            Path root = resolve(workingDir, explicit);
            if (root != null) {
                roots.add(root);
            }
            return roots;
        }
        Path folder = workingDir.toAbsolutePath().normalize();
        while (folder != null) {
            for (String sub : new String[] {"java", "kotlin"}) {
                Path candidate = folder.resolve("TeamCode").resolve("src").resolve("main").resolve(sub);
                if (Files.isDirectory(candidate)) {
                    roots.add(candidate);
                }
            }
            if (!roots.isEmpty()) {
                break;
            }
            folder = folder.getParent();
        }
        return roots;
    }

    /** Reads and analyzes the logs named in the options. */
    static Report load(Options options, InputStream stdin, Path workingDir) throws InputException {
        Report report = new Report();
        report.files = new ArrayList<FileInfo>();
        List<Line> lines = readInputs(options.logs, workingDir, stdin, report.files);
        if (lines.isEmpty()) {
            throw new InputException("no logcat-format lines found in the input; is this a Robot Controller log?");
        }
        List<Path> roots = findSourceRoots(options.sourceRoot, workingDir);
        report.haveSources = !roots.isEmpty();
        report.analysis = analyze(lines, options, roots, workingDir);
        report.findings = groupEvents(report.analysis.events);
        return report;
    }

    // ----------------------------------------------------------------------
    // Output
    // ----------------------------------------------------------------------

    static String renderText(Report report, int maxOther) {
        Analysis result = report.analysis;
        List<String> out = new ArrayList<String>();
        out.add("Robot log analysis");
        for (FileInfo info : report.files) {
            String span = info.first != null && !info.first.isEmpty()
                    ? info.first + " to " + info.last : "no timestamps";
            out.add(f("  file: %s (%d log lines, %s)", info.file, info.lines, span));
        }
        if (result.sdkVersion != null) {
            out.add("  SDK version in log: " + result.sdkVersion);
        }
        out.add("  app starts seen: " + result.appStarts.size());
        if (!report.haveSources) {
            out.add("  note: no TeamCode source tree found, so stack frames are not mapped to files. "
                    + "Run from the project root or pass --source-root.");
        }
        if (!result.opmodesRun.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : new TreeMap<String, Integer>(result.opmodesRun).entrySet()) {
                names.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
            out.add("  OpModes selected: " + join(names));
        }
        out.add("");

        List<Finding> serious = report.serious();
        out.add(serious.isEmpty()
                ? "FINDINGS: none of the known problem patterns appear in this log."
                : "FINDINGS (most serious first)");
        int number = 0;
        for (Finding finding : serious) {
            Event event = finding.event;
            number++;
            out.add("");
            out.add(f("[%d] %s: %s", number, event.category.toUpperCase(Locale.ROOT), event.title));
            String when = finding.first == null || finding.first.isEmpty() ? "?" : finding.first;
            if (finding.count > 1) {
                when += f("  (%d times, last %s)", finding.count, finding.last);
            }
            out.add("    when:   " + when);
            if (!finding.opmodes.isEmpty()) {
                out.add("    OpMode: " + join(finding.opmodes) + (event.file != null ? " (last selected)" : ""));
            }
            out.add("    what:   " + event.detail);
            if (event.outerException != null) {
                out.add("    thrown as: " + event.outerException);
            }
            for (Frame frame : event.teamFrames) {
                out.add("    team code: " + frame.place());
                if (frame.path != null) {
                    out.add("      -> " + frame.path + (frame.line == null ? "" : ":" + frame.line));
                } else if (report.haveSources) {
                    out.add("      -> source file not found in this checkout");
                }
            }
            if (event.crash && event.teamFrames.isEmpty() && event.firstFrame != null) {
                out.add("    thrown in: " + event.firstFrame.place());
            }
            if (event.file != null) {
                out.add("    log:    " + event.file + " line " + event.lineNumber);
            }
            if (event.hint != null) {
                out.add("    hint:   " + event.hint);
            }
        }

        List<Finding> context = new ArrayList<Finding>(report.findings);
        context.removeAll(serious);
        if (!context.isEmpty()) {
            out.add("");
            out.add("CONTEXT (not failures by themselves)");
            for (Finding finding : context) {
                out.add(f("  %dx %s (first %s)", finding.count, finding.event.title,
                        finding.first == null || finding.first.isEmpty() ? "?" : finding.first));
            }
        }

        out.add("");
        if (!result.loop.isEmpty()) {
            out.add("LOOP STATS (from LoopStats lines)");
            for (LoopReport entry : result.loop) {
                String battery = entry.minBattV == null ? "" : f(", lowest battery %.2f V", entry.minBattV);
                out.add(f("  %s: average %.1f ms, worst %.1f ms, %d samples%s%s", entry.opmode, entry.avgMs,
                        entry.maxMs, entry.samples, battery, entry.slow ? "  <-- slow" : ""));
            }
        } else {
            out.add("LOOP STATS: none. The SDK does not log loop times; add the LoopStats helper "
                    + "(see reference/loop-stats.md) to measure them.");
        }
        if (!result.pauses.isEmpty()) {
            Pause worst = result.pauses.get(0);
            for (Pause pause : result.pauses) {
                if (pause.ms > worst.ms) {
                    worst = pause;
                }
            }
            int count = result.pauses.size();
            out.add(f("  %d long thread pause%s logged by Android (worst %.0f ms at %s); these stall the loop.",
                    count, count == 1 ? "" : "s", worst.ms, worst.time));
        }

        if (!result.otherErrors.isEmpty() && maxOther > 0) {
            out.add("");
            out.add("OTHER ERROR-LEVEL LINES (not matched by any pattern, most frequent first)");
            for (OtherError item : result.otherErrors.subList(0, Math.min(maxOther, result.otherErrors.size()))) {
                out.add(f("  %4dx %s", item.count, item.line));
            }
            int hidden = result.otherErrors.size() - maxOther;
            if (hidden > 0) {
                out.add(f("  ... and %d more distinct lines (use --other to show more)", hidden));
            }
        }
        StringBuilder text = new StringBuilder();
        for (String line : out) {
            text.append(line).append("\n");
        }
        return text.toString();
    }

    static String renderJson(Report report, int maxOther) {
        Analysis result = report.analysis;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Object> files = new ArrayList<Object>();
        for (FileInfo info : report.files) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("file", info.file);
            item.put("lines", info.lines);
            item.put("first", info.first);
            item.put("last", info.last);
            files.add(item);
        }
        payload.put("files", files);
        payload.put("sdk_version", result.sdkVersion);
        payload.put("app_starts", new ArrayList<Object>(result.appStarts));
        payload.put("opmodes_run", new LinkedHashMap<String, Object>(result.opmodesRun));

        List<Object> findings = new ArrayList<Object>();
        for (Finding finding : report.findings) {
            Event event = finding.event;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("category", event.category);
            item.put("severity", event.severity);
            item.put("title", event.title);
            item.put("detail", event.detail);
            item.put("hint", event.hint);
            if (event.crash) {
                item.put("exception", event.exception);
                item.put("message", event.message);
                item.put("outer_exception", event.outerException);
                List<Object> frames = new ArrayList<Object>();
                for (Frame frame : event.teamFrames) {
                    frames.add(frameJson(frame));
                }
                item.put("team_frames", frames);
                item.put("first_frame", event.firstFrame == null ? null : frameJson(event.firstFrame));
            }
            item.put("time", event.time);
            item.put("file", event.file);
            item.put("line_number", event.lineNumber);
            item.put("opmode", event.opmode);
            item.put("raw", event.raw);
            item.put("count", finding.count);
            item.put("first", finding.first);
            item.put("last", finding.last);
            if (!finding.opmodes.isEmpty()) {
                item.put("opmodes", new ArrayList<Object>(finding.opmodes));
            }
            findings.add(item);
        }
        payload.put("findings", findings);

        List<Object> loop = new ArrayList<Object>();
        for (LoopReport entry : result.loop) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("opmode", entry.opmode);
            item.put("samples", entry.samples);
            item.put("avg_ms", entry.avgMs);
            item.put("max_ms", entry.maxMs);
            item.put("min_batt_v", entry.minBattV);
            item.put("first", entry.first);
            item.put("last", entry.last);
            item.put("slow", entry.slow);
            item.put("low_voltage", entry.lowVoltage);
            loop.add(item);
        }
        payload.put("loop", loop);

        List<Object> pauses = new ArrayList<Object>();
        for (Pause pause : result.pauses) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("time", pause.time);
            item.put("ms", pause.ms);
            pauses.add(item);
        }
        payload.put("long_pauses", pauses);

        List<Object> other = new ArrayList<Object>();
        for (OtherError error : result.otherErrors.subList(0, Math.min(maxOther, result.otherErrors.size()))) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("count", error.count);
            item.put("line", error.line);
            item.put("first", error.first);
            other.add(item);
        }
        payload.put("other_errors", other);

        StringBuilder out = new StringBuilder();
        writeJson(out, payload, 0);
        return out.append("\n").toString();
    }

    private static Map<String, Object> frameJson(Frame frame) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("class", frame.className);
        item.put("method", frame.method);
        item.put("file", frame.file);
        item.put("line", frame.line);
        item.put("team", frame.team);
        if (frame.team) {
            item.put("path", frame.path);
        }
        return item;
    }

    /** Writes maps, lists, strings, numbers, booleans, and null as indented JSON. */
    static void writeJson(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append(quote((String) value));
        } else if (value instanceof Double) {
            double number = (Double) value;
            out.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : Double.toString(number));
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(index++ == 0 ? "\n" : ",\n").append(indent(depth + 1))
                        .append(quote(String.valueOf(entry.getKey()))).append(": ");
                writeJson(out, entry.getValue(), depth + 1);
            }
            out.append("\n").append(indent(depth)).append("}");
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[");
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append(indent(depth + 1));
                writeJson(out, list.get(i), depth + 1);
            }
            out.append("\n").append(indent(depth)).append("]");
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass() + " as JSON");
        }
    }

    // ----------------------------------------------------------------------
    // Small helpers
    // ----------------------------------------------------------------------

    /** String.format with a fixed locale, so decimals always print with a dot. */
    static String f(String format, Object... values) {
        return String.format(Locale.ROOT, format, values);
    }

    static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static Integer parseInt(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String indent(int depth) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        return out.toString();
    }

    static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(out.length() == 0 ? "" : ", ").append(part);
        }
        return out.toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // ----------------------------------------------------------------------
    // Command line
    // ----------------------------------------------------------------------

    static final String HELP = "usage: java AnalyzeRobotLog.java LOG [LOG ...] [options]\n"
            + "\n"
            + "Find crashes, stuck OpModes, power and hub trouble, disconnects, and slow loops in\n"
            + "FTC Robot Controller logs.\n"
            + "\n"
            + "  LOG                  a log file, a folder holding robotControllerLog.txt, a wildcard\n"
            + "                       pattern such as logs/robotControllerLog.txt*, or - for stdin\n"
            + "\n"
            + "options:\n"
            + "  --package NAME       team code package prefix (default: " + DEFAULT_PACKAGE + ")\n"
            + "  --source-root DIR    folder holding the package tree (default: nearest TeamCode/src/main/java)\n"
            + "  --loop-avg-ms MS     flag an OpMode whose average loop exceeds this (default: 25)\n"
            + "  --loop-max-ms MS     flag an OpMode whose worst loop exceeds this (default: 100)\n"
            + "  --low-voltage VOLTS  flag logged battery voltage below this (default: 10)\n"
            + "  --other N            how many unmatched error lines to list (default: 8)\n"
            + "  --json               machine-readable output\n"
            + "  -h, --help           show this help\n"
            + "\n"
            + "Exit codes: 0 nothing serious found, 1 problems found, 2 bad input.\n";

    static Options parseArgs(String[] argv) throws InputException {
        Options options = new Options();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (token.equals("-h") || token.equals("--help")) {
                options.help = true;
            } else if (token.equals("--json")) {
                options.json = true;
            } else if (token.startsWith("--")) {
                String name = token;
                String value = null;
                int equals = token.indexOf('=');
                if (equals > 0) {
                    name = token.substring(0, equals);
                    value = token.substring(equals + 1);
                }
                if (!Arrays.asList("--package", "--source-root", "--loop-avg-ms", "--loop-max-ms", "--low-voltage",
                        "--other").contains(name)) {
                    throw new InputException("unknown option " + name);
                }
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new InputException(name + " needs a value");
                    }
                    value = argv[++i];
                }
                try {
                    if (name.equals("--package")) {
                        options.teamPackage = value;
                    } else if (name.equals("--source-root")) {
                        options.sourceRoot = value;
                    } else if (name.equals("--loop-avg-ms")) {
                        options.loopAvgMs = Double.parseDouble(value);
                    } else if (name.equals("--loop-max-ms")) {
                        options.loopMaxMs = Double.parseDouble(value);
                    } else if (name.equals("--low-voltage")) {
                        options.lowVoltage = Double.parseDouble(value);
                    } else {
                        options.other = Integer.parseInt(value);
                    }
                } catch (NumberFormatException error) {
                    throw new InputException(name + ": '" + value + "' is not a number");
                }
            } else {
                options.logs.add(token);
            }
        }
        if (!options.help && options.logs.isEmpty()) {
            throw new InputException("give at least one log file, a folder of logs, or - for stdin");
        }
        return options;
    }

    /** Runs the command line, printing to the given streams, and returns the exit code. */
    static int run(String[] argv, InputStream stdin, PrintStream out, PrintStream err, Path workingDir) {
        try {
            Options options = parseArgs(argv);
            if (options.help) {
                out.print(HELP);
                return EXIT_CLEAN;
            }
            Report report = load(options, stdin, workingDir);
            int maxOther = Math.max(options.other, 0);
            out.print(options.json ? renderJson(report, maxOther) : renderText(report, maxOther));
            return report.serious().isEmpty() ? EXIT_CLEAN : EXIT_FINDINGS;
        } catch (InputException error) {
            err.print("error: " + error.getMessage() + "\n");
            return EXIT_INPUT;
        }
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
        PrintStream err = utf8(new FileOutputStream(FileDescriptor.err));
        int code = run(argv, System.in, out, err, Paths.get("").toAbsolutePath());
        out.flush();
        err.flush();
        System.exit(code);
    }
}
