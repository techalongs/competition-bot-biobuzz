/*
 * FTC mechanism calculator: gear trains, flywheel launchers, lifts, arms, drivetrains.
 *
 * Runs straight from source with any JDK 11 or later. There is no build step and
 * nothing to install:
 *
 *     java MechCalc.java motors
 *     java MechCalc.java gear --motor gobilda-1620 --stage 20:40 --wheel-diameter 72mm
 *     java MechCalc.java flywheel --motor gobilda-6000 --wheel-diameter 96mm --rpm 3200 \
 *         --ball-diameter 71mm --ball-mass 30g --angle-deg 45 --release-height 300mm
 *     java MechCalc.java lift --motor gobilda-435 --count 2 --mass 3kg --spool-diameter 38mm --travel 700mm
 *     java MechCalc.java arm --motor gobilda-60 --payload-mass 400g --length 350mm --arm-mass 250g
 *     java MechCalc.java drivetrain --motor gobilda-435 --mass 12kg --wheel-diameter 104mm
 *
 * Every length, mass, force, and torque must carry a unit (96mm, 4in, 12lb, 5.4kg,
 * 20N, 18.7kgcm, 2.1Nm), so a number can never be read in the wrong unit.
 *
 * Exit codes:
 *     0  calculated; every check passed (warnings allowed unless --strict)
 *     1  calculated; a check failed
 *     2  bad arguments
 *
 * The motor model is the standard straight-line one: speed falls and current rises
 * in proportion to torque, and all three scale with voltage. It uses the
 * manufacturers' published numbers, so treat results as estimates and keep a margin.
 *
 * The code is written in the same Java 8 dialect as the robot code, with no
 * libraries. To add a motor, add a line to the motor table below and cite its source.
 */

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MechCalc {

    static final int EXIT_OK = 0;
    static final int EXIT_FAIL = 1;
    static final int EXIT_USAGE = 2;

    static final double GRAVITY = 9.80665;            // m/s^2
    static final double KGCM_TO_NM = 0.0980665;
    static final double RPM_TO_RAD = 2 * Math.PI / 60;
    static final double INCH = 0.0254;
    static final double FOOT = 0.3048;
    static final double POUND = 0.45359237;
    static final double POUND_FORCE = 4.4482216152605;

    private MechCalc() {
    }

    // ----------------------------------------------------------------------
    // Motor table. Published figures at 12 V, checked on 2026-11-16.
    //
    // Speeds and torques are at the motor's own output shaft, after its built-in
    // gearbox. Published stall figures are extrapolated and vary motor to motor,
    // and independent dyno tests differ from them, so keep a margin.
    // ----------------------------------------------------------------------

    static final class Motor {
        final String name;
        final String label;
        final double freeRpm;
        final double stallTorque;       // N*m at the motor's output shaft
        final double stallCurrent;      // A
        final Double freeCurrent;       // A; null when the maker does not publish it
        final double nominalVoltage;
        final Double ppr;               // encoder counts per output-shaft turn; null when not listed
        final boolean pprComputed;      // true when ppr is 28 x gear ratio, not a published figure
        final String source;

        Motor(String name, String label, double freeRpm, double stallTorque, double stallCurrent,
              Double freeCurrent, double nominalVoltage, Double ppr, boolean pprComputed, String source) {
            this.name = name;
            this.label = label;
            this.freeRpm = freeRpm;
            this.stallTorque = stallTorque;
            this.stallCurrent = stallCurrent;
            this.freeCurrent = freeCurrent;
            this.nominalVoltage = nominalVoltage;
            this.ppr = ppr;
            this.pprComputed = pprComputed;
            this.source = source;
        }
    }

    static final Map<String, Motor> MOTORS = new LinkedHashMap<String, Motor>();

    /** goBILDA 5203 Yellow Jacket: every ratio shares 9.2 A stall and 0.25 A free current at 12 V. */
    private static void gobilda(String name, String gearbox, double freeRpm, double stallKgCm,
                                double ppr, boolean pprPublished, String sheet) {
        MOTORS.put(name, new Motor(name,
                "goBILDA 5203 Yellow Jacket " + gearbox + " (" + f("%.0f", freeRpm) + " RPM)",
                freeRpm, stallKgCm * KGCM_TO_NM, 9.2, 0.25, 12.0, ppr, !pprPublished,
                "https://www.gobilda.com/content/spec_sheets/5203-2402-" + sheet + "_spec_sheet.pdf"));
    }

    private static void rev(String name, String label, double freeRpm, double stallNm, double stallCurrent,
                            Double freeCurrent, Double ppr, String source) {
        MOTORS.put(name, new Motor(name, label, freeRpm, stallNm, stallCurrent, freeCurrent, 12.0, ppr,
                false, source));
    }

    static {
        //      name            gearbox   RPM   kg.cm  counts/rev  published?  spec sheet
        gobilda("gobilda-6000", "1:1",    6000,   1.47,    28.0, true,  "0001");
        gobilda("gobilda-1620", "3.7:1",  1620,   5.4,    103.8, true,  "0003");
        gobilda("gobilda-1150", "5.2:1",  1150,   7.9,    145.1, false, "0005");
        gobilda("gobilda-435",  "13.7:1",  435,  18.7,    384.5, true,  "0014");
        gobilda("gobilda-312",  "19.2:1",  312,  24.3,    537.7, true,  "0019");
        gobilda("gobilda-223",  "26.9:1",  223,  38.0,    751.8, false, "0027");
        gobilda("gobilda-117",  "50.9:1",  117,  68.4,   1425.1, false, "0051");
        gobilda("gobilda-84",   "71.2:1",   84,  93.6,   1992.6, false, "0071");
        gobilda("gobilda-60",   "99.5:1",   60, 133.2,   2786.2, true,  "0100");
        gobilda("gobilda-43",   "139:1",    43, 185.0,   3895.9, false, "0139");
        gobilda("gobilda-30",   "188:1",    30, 250.0,   5281.1, true,  "0188");

        // REV: 28 counts per motor turn on the HD Hex; the geared HD Hex counts depend on the
        // exact gearbox ratio (see REV's encoder table), so they are left out. The Core Hex is
        // 4 counts per motor turn x 72, and REV publishes no free current for it.
        rev("rev-hd-hex", "REV HD Hex motor, no gearbox (6000 RPM)", 6000, 0.105, 8.5, 0.4, 28.0,
                "https://docs.revrobotics.com/duo-build/motion/motors/hd-hex-motor");
        rev("rev-hd-hex-20", "REV HD Hex 20:1 (300 RPM)", 300, 2.1, 8.5, 0.4, null,
                "https://www.revrobotics.com/REV-41-1211/");
        rev("rev-hd-hex-40", "REV HD Hex 40:1 (150 RPM)", 150, 4.2, 8.5, 0.4, null,
                "https://revrobotics.com/rev-41-1301");
        rev("rev-core-hex", "REV Core Hex 72:1 (125 RPM)", 125, 3.2, 4.4, null, 288.0,
                "https://docs.revrobotics.com/duo-build/motion/motors/core-hex-motor");
    }

    // ----------------------------------------------------------------------
    // Units and input parsing
    // ----------------------------------------------------------------------

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** A kind of physical quantity and the units accepted for it, each with its factor to SI. */
    enum Quantity {
        LENGTH("length", "96mm"),
        MASS("mass", "2.5kg"),
        FORCE("force", "20N"),
        TORQUE("torque", "18.7kgcm");

        final String label;
        final String example;
        final Map<String, Double> toSi = new TreeMap<String, Double>();

        Quantity(String label, String example) {
            this.label = label;
            this.example = example;
        }

        private Quantity unit(String name, double factor) {
            toSi.put(name, factor);
            return this;
        }

        static {
            LENGTH.unit("mm", 1e-3).unit("cm", 1e-2).unit("m", 1.0).unit("in", INCH).unit("ft", FOOT);
            MASS.unit("g", 1e-3).unit("kg", 1.0).unit("oz", 0.028349523125).unit("lb", POUND);
            FORCE.unit("n", 1.0).unit("kgf", GRAVITY).unit("lbf", POUND_FORCE);
            TORQUE.unit("nm", 1.0).unit("kgcm", KGCM_TO_NM).unit("ozin", 0.00706155183333)
                    .unit("lbin", 0.112984829).unit("lbft", 1.3558179483);
        }
    }

    // A number, then a unit. The unit may contain . - * or a space ("kg.cm", "N-m", "kg cm").
    private static final Pattern QUANTITY = Pattern.compile(
            "^([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[+-]?\\d+)?)\\s*([a-z][a-z.\\-*\\u00b7 ]*)$");

    /** Reads a number with a required unit and returns it in SI units. */
    static double quantity(Quantity kind, String text) throws UsageException {
        Matcher match = QUANTITY.matcher(text.trim().toLowerCase(Locale.ROOT));
        String unit = match.matches() ? match.group(2).replaceAll("[.\\-*\\u00b7 ]", "") : null;
        if (unit == null || !kind.toSi.containsKey(unit)) {
            throw new UsageException("'" + text + "' needs a number and a " + kind.label + " unit ("
                    + join(kind.toSi.keySet()) + "), for example " + kind.example);
        }
        double value = Double.parseDouble(match.group(1));
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new UsageException("'" + text + "' must be zero or more");
        }
        return value * kind.toSi.get(unit);
    }

    /** Reads DRIVING:DRIVEN tooth counts, for example 20:40. */
    static double[] stage(String text) throws UsageException {
        String[] parts = text.split(":", -1);
        double driving;
        double driven;
        try {
            if (parts.length != 2) {
                throw new NumberFormatException(text);
            }
            driving = Double.parseDouble(parts[0].trim());
            driven = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException error) {
            throw new UsageException("'" + text + "' should look like 20:40 (driving:driven)");
        }
        if (!(driving > 0) || !(driven > 0) || Double.isInfinite(driving) || Double.isInfinite(driven)) {
            throw new UsageException("'" + text + "': tooth counts must be positive");
        }
        return new double[] {driving, driven};
    }

    static double number(String text) throws UsageException {
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new UsageException("'" + text + "' is not a number");
        }
    }

    static double positive(String text) throws UsageException {
        double value = number(text);
        if (value <= 0) {
            throw new UsageException("'" + text + "' must be greater than zero");
        }
        return value;
    }

    // ----------------------------------------------------------------------
    // Command-line options
    // ----------------------------------------------------------------------

    enum Kind { FLAG, TEXT, CHOICE, INTEGER, NUMBER, POSITIVE, LENGTH, MASS, FORCE, TORQUE, STAGE }

    static final class Option {
        final String name;
        final Kind kind;
        final String help;
        Object fallback;            // value used when the option is not given; may be null
        boolean required;
        String[] choices;

        Option(String name, Kind kind, String help) {
            this.name = name;
            this.kind = kind;
            this.help = help;
        }

        Object convert(String text) throws UsageException {
            switch (kind) {
                case TEXT:
                    return text;
                case CHOICE:
                    if (!Arrays.asList(choices).contains(text)) {
                        throw new UsageException("'" + text + "' is not one of: " + join(Arrays.asList(choices)));
                    }
                    return text;
                case INTEGER:
                    try {
                        return Integer.valueOf(text.trim());
                    } catch (NumberFormatException error) {
                        throw new UsageException("'" + text + "' is not a whole number");
                    }
                case NUMBER:
                    return number(text);
                case POSITIVE:
                    return positive(text);
                case LENGTH:
                    return quantity(Quantity.LENGTH, text);
                case MASS:
                    return quantity(Quantity.MASS, text);
                case FORCE:
                    return quantity(Quantity.FORCE, text);
                case TORQUE:
                    return quantity(Quantity.TORQUE, text);
                case STAGE:
                    return stage(text);
                default:
                    throw new IllegalStateException("flag options take no value");
            }
        }

        String placeholder() {
            switch (kind) {
                case FLAG:
                    return "";
                case TEXT:
                    return " NAME";
                case CHOICE:
                    return " " + join(Arrays.asList(choices)).replace(", ", "|");
                case INTEGER:
                    return " N";
                case STAGE:
                    return " DRIVING:DRIVEN";
                case NUMBER:
                case POSITIVE:
                    return " NUMBER";
                default:
                    return " " + kind.name();
            }
        }
    }

    interface Calculation {
        Report run(Args args) throws UsageException;
    }

    static final class Command {
        final String name;
        final String help;
        final Calculation calculation;
        final Map<String, Option> options = new LinkedHashMap<String, Option>();

        Command(String name, String help, Calculation calculation) {
            this.name = name;
            this.help = help;
            this.calculation = calculation;
            add("--json", Kind.FLAG, "machine-readable output");
            add("--strict", Kind.FLAG, "treat warnings as failures");
        }

        Option add(String optionName, Kind kind, String optionHelp) {
            Option option = new Option(optionName, kind, optionHelp);
            options.put(optionName, option);
            return option;
        }

        Command opt(String optionName, Kind kind, String optionHelp) {
            add(optionName, kind, optionHelp);
            return this;
        }

        Command opt(String optionName, Kind kind, Object fallback, String optionHelp) {
            add(optionName, kind, optionHelp).fallback = fallback;
            return this;
        }

        Command required(String optionName, Kind kind, String optionHelp) {
            add(optionName, kind, optionHelp).required = true;
            return this;
        }

        Command choice(String optionName, String fallback, String optionHelp, String... choices) {
            Option option = add(optionName, Kind.CHOICE, optionHelp);
            option.choices = choices;
            option.fallback = fallback;
            return this;
        }

        String helpText() {
            StringBuilder out = new StringBuilder();
            out.append("usage: java MechCalc.java ").append(name).append(" [options]\n\n").append(help).append("\n\n");
            out.append("Lengths, masses, forces, and torques need units: 96mm, 4in, 12lb, 5.4kg, 20N, 18.7kgcm.\n\n");
            out.append("options:\n");
            int width = 0;
            for (Option option : options.values()) {
                width = Math.max(width, (option.name + option.placeholder()).length());
            }
            for (Option option : options.values()) {
                out.append(pad("  " + option.name + option.placeholder(), width + 4)).append(option.help);
                if (option.required) {
                    out.append(" (required)");
                }
                out.append("\n");
            }
            return out.toString();
        }
    }

    static final class Args {
        final Command command;
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        boolean help;

        Args(Command command) {
            this.command = command;
        }

        private Object get(String name) {
            Object value = values.get(name);
            if (value != null) {
                return value;
            }
            Option option = command.options.get(name);
            return option == null ? null : option.fallback;
        }

        /** The option's value, or null when it was not given and has no default. */
        Double num(String name) {
            return (Double) get(name);
        }

        String text(String name) {
            return (String) get(name);
        }

        int integer(String name) {
            return ((Integer) get(name)).intValue();
        }

        boolean flag(String name) {
            return Boolean.TRUE.equals(get(name));
        }

        @SuppressWarnings("unchecked")
        List<double[]> stages(String name) {
            Object value = values.get(name);
            return value == null ? new ArrayList<double[]>() : (List<double[]>) value;
        }
    }

    @SuppressWarnings("unchecked")
    static Args parse(Command command, List<String> tokens) throws UsageException {
        Args args = new Args(command);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("-h") || token.equals("--help")) {
                args.help = true;
                continue;
            }
            if (!token.startsWith("--")) {
                throw new UsageException("unexpected argument '" + token + "'; options start with --");
            }
            String name = token;
            String value = null;
            int equals = token.indexOf('=');
            if (equals > 0) {
                name = token.substring(0, equals);
                value = token.substring(equals + 1);
            }
            Option option = command.options.get(name);
            if (option == null) {
                throw new UsageException("'" + command.name + "' has no option " + name);
            }
            if (option.kind == Kind.FLAG) {
                if (value != null) {
                    throw new UsageException(name + " does not take a value");
                }
                args.values.put(name, Boolean.TRUE);
                continue;
            }
            if (value == null) {
                if (i + 1 >= tokens.size()) {
                    throw new UsageException(name + " needs a value");
                }
                value = tokens.get(++i);
            }
            Object converted;
            try {
                converted = option.convert(value);
            } catch (UsageException error) {
                throw new UsageException(name + ": " + error.getMessage());
            }
            if (option.kind == Kind.STAGE) {
                List<double[]> list = (List<double[]>) args.values.get(name);
                if (list == null) {
                    list = new ArrayList<double[]>();
                    args.values.put(name, list);
                }
                list.add((double[]) converted);
            } else {
                args.values.put(name, converted);
            }
        }
        if (!args.help) {
            for (Option option : command.options.values()) {
                if (option.required && !args.values.containsKey(option.name)) {
                    throw new UsageException("'" + command.name + "' needs " + option.name);
                }
            }
        }
        return args;
    }

    // ----------------------------------------------------------------------
    // Motor and gearing model
    // ----------------------------------------------------------------------

    /** The steady state of a drive at one torque demand. */
    static final class Point {
        double fraction;        // demanded torque as a share of stall torque
        double rpm;
        double currentEach;
        double currentTotal;
        double powerOut;
        double powerIn;
    }

    /** One or more identical motors driving one output through a reduction. */
    static final class Drive {
        final Motor motor;
        final int count;
        final double ratio;             // above 1 slows the output down
        final double efficiency;
        final boolean efficiencyAssumed;
        final double volts;
        final double motorFreeRpm;
        final double motorStallTorque;
        final double motorStallCurrent;
        final double motorFreeCurrent;
        final double freeRpm;
        final double stallTorque;

        Drive(Motor motor, int count, double ratio, double efficiency, boolean efficiencyAssumed, double volts) {
            this.motor = motor;
            this.count = count;
            this.ratio = ratio;
            this.efficiency = efficiency;
            this.efficiencyAssumed = efficiencyAssumed;
            this.volts = volts;
            double scale = volts / motor.nominalVoltage;
            this.motorFreeRpm = motor.freeRpm * scale;
            this.motorStallTorque = motor.stallTorque * scale;
            this.motorStallCurrent = motor.stallCurrent * scale;
            this.motorFreeCurrent = motor.freeCurrent == null ? 0.0 : motor.freeCurrent;
            this.freeRpm = motorFreeRpm / ratio;
            this.stallTorque = count * motorStallTorque * ratio * efficiency;
        }

        /** Steady-state operating point for a torque demanded at the output. */
        Point atTorque(double outputTorque) {
            Point point = new Point();
            point.fraction = stallTorque != 0 ? outputTorque / stallTorque : Double.POSITIVE_INFINITY;
            double capped = Math.min(Math.max(point.fraction, 0.0), 1.0);
            point.rpm = freeRpm * (1 - capped);
            point.currentEach = motorFreeCurrent + (motorStallCurrent - motorFreeCurrent) * capped;
            point.currentTotal = point.currentEach * count;
            point.powerOut = point.fraction <= 1 ? outputTorque * point.rpm * RPM_TO_RAD : 0.0;
            point.powerIn = volts * point.currentTotal;
            return point;
        }
    }

    static Motor motorFromArgs(Args args) throws UsageException {
        String name = args.text("--motor");
        if (name != null && !name.isEmpty()) {
            Motor motor = MOTORS.get(name);
            if (motor == null) {
                throw new UsageException("unknown motor '" + name + "'. Known: "
                        + join(new TreeSet<String>(MOTORS.keySet())));
            }
            return motor;
        }
        Double freeRpm = args.num("--free-rpm");
        Double stallTorque = args.num("--stall-torque");
        Double stallCurrent = args.num("--stall-current");
        if (freeRpm != null && stallTorque != null && stallCurrent != null) {
            return new Motor("custom", "custom motor", freeRpm, stallTorque, stallCurrent,
                    args.num("--free-current"), 12.0, null, false, "given on the command line");
        }
        throw new UsageException("give --motor NAME (see the 'motors' command) or all of --free-rpm, "
                + "--stall-torque and --stall-current");
    }

    static Drive driveFromArgs(Args args, double baseEfficiency) throws UsageException {
        Motor motor = motorFromArgs(args);
        Double givenRatio = args.num("--ratio");
        List<double[]> stages = args.stages("--stage");
        if (givenRatio != null && !stages.isEmpty()) {
            throw new UsageException("use either --ratio or --stage, not both");
        }
        int stageCount = stages.size();
        double ratio = 1.0;
        for (double[] teeth : stages) {
            ratio *= teeth[1] / teeth[0];
        }
        if (givenRatio != null) {
            ratio = givenRatio;
            stageCount = ratio == 1 ? 0 : 1;
        }
        Double givenEfficiency = args.num("--efficiency");
        boolean assumed = givenEfficiency == null;
        double efficiency = assumed ? baseEfficiency * Math.pow(0.95, stageCount) : givenEfficiency;
        if (!(efficiency > 0 && efficiency <= 1)) {
            throw new UsageException("--efficiency must be above 0 and at most 1");
        }
        int count = args.integer("--count");
        if (count < 1) {
            throw new UsageException("--count must be at least 1");
        }
        return new Drive(motor, count, ratio, efficiency, assumed, args.num("--voltage"));
    }

    // ----------------------------------------------------------------------
    // Report
    // ----------------------------------------------------------------------

    static final class Report {
        final String title;
        final List<String[]> inputs = new ArrayList<String[]>();    // label, text
        final List<String[]> results = new ArrayList<String[]>();   // label, text
        final List<String[]> checks = new ArrayList<String[]>();    // status, label, detail
        final List<String> notes = new ArrayList<String>();
        final Map<String, Double> values = new LinkedHashMap<String, Double>();  // raw numbers, SI units

        Report(String title) {
            this.title = title;
        }

        void input(String label, String text) {
            inputs.add(new String[] {label, text});
        }

        void result(String label, String text) {
            results.add(new String[] {label, text});
        }

        void result(String label, String text, String key, double value) {
            result(label, text);
            values.put(key, value);
        }

        void check(String status, String label, String detail) {
            checks.add(new String[] {status, label, detail});
        }

        void note(String text) {
            notes.add(text);
        }

        /** The status of the check named label, or null when there is no such check. */
        String status(String label) {
            for (String[] check : checks) {
                if (check[1].equals(label)) {
                    return check[0];
                }
            }
            return null;
        }

        String worst() {
            List<String> order = Arrays.asList("OK", "WARN", "FAIL");
            String worst = "OK";
            for (String[] check : checks) {
                if (order.indexOf(check[0]) > order.indexOf(worst)) {
                    worst = check[0];
                }
            }
            return worst;
        }

        String text() {
            int width = 1;
            for (String[] row : inputs) {
                width = Math.max(width, row[0].length());
            }
            for (String[] row : results) {
                width = Math.max(width, row[0].length());
            }
            StringBuilder out = new StringBuilder(title).append("\n");
            if (!inputs.isEmpty()) {
                out.append("\nInputs and assumptions\n");
                for (String[] row : inputs) {
                    out.append("  ").append(pad(row[0], width)).append("  ").append(row[1]).append("\n");
                }
            }
            if (!results.isEmpty()) {
                out.append("\nResults\n");
                for (String[] row : results) {
                    out.append("  ").append(pad(row[0], width)).append("  ").append(row[1]).append("\n");
                }
            }
            if (!checks.isEmpty()) {
                out.append("\nChecks\n");
                for (String[] check : checks) {
                    out.append("  [").append(pad(check[0], 4)).append("] ").append(check[1]).append(": ")
                            .append(check[2]).append("\n");
                }
            }
            if (!notes.isEmpty()) {
                out.append("\nNotes\n");
                for (String note : notes) {
                    out.append("  - ").append(note).append("\n");
                }
            }
            return out.toString();
        }

        String json() {
            StringBuilder out = new StringBuilder("{\n");
            out.append("  \"title\": ").append(quote(title)).append(",\n");
            out.append("  \"inputs\": ").append(pairs(inputs, "label", "value")).append(",\n");
            out.append("  \"results\": ").append(pairs(results, "label", "value")).append(",\n");
            out.append("  \"checks\": [");
            for (int i = 0; i < checks.size(); i++) {
                String[] check = checks.get(i);
                out.append(i == 0 ? "\n" : ",\n").append("    {\"status\": ").append(quote(check[0]))
                        .append(", \"label\": ").append(quote(check[1]))
                        .append(", \"detail\": ").append(quote(check[2])).append("}");
            }
            out.append(checks.isEmpty() ? "],\n" : "\n  ],\n");
            out.append("  \"notes\": [");
            for (int i = 0; i < notes.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append("    ").append(quote(notes.get(i)));
            }
            out.append(notes.isEmpty() ? "],\n" : "\n  ],\n");
            out.append("  \"values\": {");
            int index = 0;
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                out.append(index++ == 0 ? "\n" : ",\n").append("    ").append(quote(entry.getKey())).append(": ")
                        .append(jsonNumber(entry.getValue()));
            }
            out.append(values.isEmpty() ? "},\n" : "\n  },\n");
            out.append("  \"worst\": ").append(quote(worst())).append("\n}\n");
            return out.toString();
        }

        private static String pairs(List<String[]> rows, String first, String second) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < rows.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append("    {").append(quote(first)).append(": ")
                        .append(quote(rows.get(i)[0])).append(", ").append(quote(second)).append(": ")
                        .append(quote(rows.get(i)[1])).append("}");
            }
            return out.append(rows.isEmpty() ? "]" : "\n  ]").toString();
        }
    }

    static void describeDrive(Report report, Drive drive) {
        Motor motor = drive.motor;
        report.input("motor", f("%d x %s", drive.count, motor.label));
        report.input("motor data", f("%.0f RPM free, %.3f N*m stall, %.1f A stall at %.0f V (%s)",
                motor.freeRpm, motor.stallTorque, motor.stallCurrent, motor.nominalVoltage, motor.source));
        report.input("voltage", f("%.1f V", drive.volts));
        report.input("external ratio", f("%.3f : 1%s", drive.ratio,
                drive.ratio >= 1 ? "" : "  (speeds the output up)"));
        report.input("efficiency", f("%.3f%s", drive.efficiency,
                drive.efficiencyAssumed ? "  (assumed; set --efficiency)" : ""));
        if (motor.freeCurrent == null) {
            report.note("No free current is published for this motor, so currents are slightly low.");
        }
    }

    /** Checks shared by every mechanism that loads a motor. */
    static void loadChecks(Report report, Point point, Args args, String what) {
        double guideline = args.num("--max-load-fraction");
        double portLimit = args.num("--port-limit-a");
        double fuse = args.num("--fuse-a");
        double fraction = point.fraction;
        String percent = f("%.0f%% of stall torque", fraction * 100);
        if (fraction >= 1) {
            report.check("FAIL", "load", f("%s needs %s; the motors cannot do it", what, percent));
        } else if (fraction > 0.5) {
            report.check("FAIL", "load", f("%s takes %s; above 50%% a motor overheats quickly and stalls as "
                    + "the battery sags", what, percent));
        } else if (fraction > guideline) {
            report.check("WARN", "load", f("%s takes %s, above the %.0f%% guideline; acceptable in short "
                    + "bursts only", what, percent, guideline * 100));
        } else {
            report.check("OK", "load", f("%s takes %s (guideline: at most %.0f%%)", what, percent, guideline * 100));
        }

        double each = point.currentEach;
        double total = point.currentTotal;
        if (each > portLimit) {
            report.check("FAIL", "motor port", f("%.1f A per motor exceeds the %.0f A continuous port limit",
                    each, portLimit));
        } else if (each > 0.8 * portLimit) {
            report.check("WARN", "motor port", f("%.1f A per motor is close to the %.0f A continuous port limit",
                    each, portLimit));
        } else {
            report.check("OK", "motor port", f("%.1f A per motor (continuous port limit %.0f A)", each, portLimit));
        }
        if (total > fuse) {
            report.check("FAIL", "battery fuse", f("%.1f A for this mechanism alone exceeds the %.0f A fuse",
                    total, fuse));
        } else if (total > 0.6 * fuse) {
            report.check("WARN", "battery fuse", f("%.1f A for this mechanism is over 60%% of the %.0f A fuse, "
                    + "which every other motor and servo shares", total, fuse));
        } else {
            report.check("OK", "battery fuse", f("%.1f A for this mechanism (fuse %.0f A, shared with everything "
                    + "else)", total, fuse));
        }
    }

    static void noteComputedCounts(Report report, Motor motor) {
        if (motor.pprComputed) {
            report.note("The encoder counts for this motor are computed as 28 x its gear ratio. Confirm them on "
                    + "the spec sheet before relying on a count-based number.");
        }
    }

    // ----------------------------------------------------------------------
    // Commands
    // ----------------------------------------------------------------------

    static Report motorsCommand(Args args) {
        Report report = new Report("Motors built into MechCalc (published figures at 12 V)");
        List<Motor> motors = new ArrayList<Motor>(MOTORS.values());
        motors.sort((a, b) -> {
            int vendor = a.name.split("-")[0].compareTo(b.name.split("-")[0]);
            return vendor != 0 ? vendor : Double.compare(b.freeRpm, a.freeRpm);
        });
        for (Motor motor : motors) {
            String ticks = "counts/rev not listed";
            if (truthy(motor.ppr)) {
                ticks = f("%.1f counts/rev", motor.ppr) + (motor.pprComputed ? " (computed)" : "");
            }
            report.result(motor.name, f("%5.0f RPM  %6.3f N*m stall  %4.1f A stall  %s",
                    motor.freeRpm, motor.stallTorque, motor.stallCurrent, ticks));
        }
        report.note("Each calculation prints the source of the motor figures it used. The table is at the top "
                + "of MechCalc.java.");
        report.note("\"(computed)\" counts are 28 x the gear ratio, not a published figure. Confirm them on the "
                + "spec sheet.");
        return report;
    }

    static Report gearCommand(Args args) throws UsageException {
        Drive drive = driveFromArgs(args, 1.0);
        Report report = new Report("Gear train");
        describeDrive(report, drive);
        report.result("output free speed", f("%.0f RPM", drive.freeRpm), "output_free_rpm", drive.freeRpm);
        report.result("output stall torque", torque(drive.stallTorque), "output_stall_torque_nm", drive.stallTorque);
        if (truthy(drive.motor.ppr)) {
            double counts = drive.motor.ppr * drive.ratio;
            report.result("encoder counts per output turn", f("%.1f", counts), "counts_per_output_rev", counts);
            noteComputedCounts(report, drive.motor);
        }
        Double diameter = args.num("--wheel-diameter");
        Double radius = truthy(diameter) ? Double.valueOf(diameter / 2) : null;
        if (radius != null) {
            report.input("wheel or pulley diameter", length(diameter));
            double speed = drive.freeRpm * RPM_TO_RAD * radius;
            report.result("free surface speed", speed(speed), "free_surface_speed_mps", speed);
            report.result("stall force at the rim", force(drive.stallTorque / radius));
        }
        Double load = args.num("--load-torque");
        Double loadForce = args.num("--load-force");
        if (loadForce != null) {
            if (radius == null) {
                throw new UsageException("--load-force needs --wheel-diameter");
            }
            load = loadForce * radius;
        }
        if (load != null) {
            Point point = drive.atTorque(load);
            report.input("load at the output", torque(load));
            report.result("speed under load", f("%.0f RPM", point.rpm), "loaded_rpm", point.rpm);
            if (radius != null) {
                report.result("surface speed under load", speed(point.rpm * RPM_TO_RAD * radius));
            }
            report.result("current", f("%.1f A per motor, %.1f A total", point.currentEach, point.currentTotal));
            report.values.put("current_each_a", point.currentEach);
            report.values.put("current_total_a", point.currentTotal);
            report.result("mechanical power out", f("%.1f W", point.powerOut));
            loadChecks(report, point, args, "the load");
        }
        return report;
    }

    static Report liftCommand(Args args) throws UsageException {
        Drive drive = driveFromArgs(args, 0.85);
        double mass = args.num("--mass");
        double spool = args.num("--spool-diameter");
        double multiplier = args.num("--travel-multiplier");
        double assist = args.num("--assist-force");
        Double travel = args.num("--travel");
        if (spool <= 0) {
            throw new UsageException("--spool-diameter must be greater than zero");
        }
        Report report = new Report("Linear lift");
        describeDrive(report, drive);
        double radius = spool / 2;
        report.input("load mass", f("%.2f kg (%.1f lb)", mass, mass / POUND));
        report.input("spool or pulley diameter", length(spool));
        if (multiplier != 1) {
            report.input("travel multiplier", f("%.0fx (cascade: load moves this many times the string, and the "
                    + "string carries this many times the load)", multiplier));
        }
        double force = Math.max(mass * GRAVITY - assist, 0.0) * multiplier;
        if (assist != 0) {
            report.input("assist force", force(assist));
        }
        double torque = force * radius;
        Point point = drive.atTorque(torque);
        double capacity = drive.stallTorque / radius / multiplier / GRAVITY;
        double freeSpeed = drive.freeRpm * RPM_TO_RAD * radius * multiplier;
        double speed = point.rpm * RPM_TO_RAD * radius * multiplier;

        report.result("string force to hold", force(force), "hold_force_n", force);
        report.result("torque at the spool", torque(torque));
        report.result("share of stall torque", f("%.0f%%", point.fraction * 100), "load_fraction", point.fraction);
        report.result("current to hold or lift", f("%.1f A per motor, %.1f A total", point.currentEach,
                point.currentTotal));
        report.values.put("current_each_a", point.currentEach);
        report.values.put("current_total_a", point.currentTotal);
        report.result("mass the motors stall at", mass != 0
                ? f("%.1f kg (%.1fx the load)", capacity, capacity / mass)
                : f("%.1f kg", capacity), "stall_mass_kg", capacity);
        report.result("lift speed, no load", speed(freeSpeed));
        report.result("lift speed, this load", speed(speed), "loaded_speed_mps", speed);
        if (truthy(travel)) {
            report.input("travel", length(travel));
            if (speed > 0) {
                report.result("time for full travel", f("%.2f s at steady speed (add time to accelerate)",
                        travel / speed), "travel_time_s", travel / speed);
            }
        }
        loadChecks(report, point, args, "holding the load");
        report.note("Holding a load with motor power draws the hold current the whole time. A ratchet, worm "
                + "drive, or constant-force spring removes that load.");
        return report;
    }

    static Report armCommand(Args args) throws UsageException {
        Drive drive = driveFromArgs(args, 0.95);
        double payload = args.num("--payload-mass");
        double armLength = args.num("--length");
        double armMass = args.num("--arm-mass");
        Double givenCom = args.num("--com-distance");
        Double sweep = args.num("--sweep-deg");
        Report report = new Report("Rotating arm (worst case: arm horizontal)");
        describeDrive(report, drive);
        double com = givenCom != null ? givenCom : armLength / 2;
        double torque = GRAVITY * (payload * armLength + armMass * com);
        report.input("payload", f("%.3f kg at %s", payload, length(armLength)));
        report.input("arm", f("%.3f kg, center of mass at %s", armMass, length(com)));
        Point point = drive.atTorque(torque);
        double freeDegrees = drive.freeRpm * 6.0;
        double loadedDegrees = point.rpm * 6.0;
        report.result("torque at the pivot", torque(torque), "pivot_torque_nm", torque);
        report.result("share of stall torque", f("%.0f%%", point.fraction * 100), "load_fraction", point.fraction);
        report.result("current to hold horizontal", f("%.1f A per motor, %.1f A total", point.currentEach,
                point.currentTotal));
        report.values.put("current_each_a", point.currentEach);
        report.values.put("current_total_a", point.currentTotal);
        report.result("swing speed, no load", f("%.0f deg/s", freeDegrees));
        report.result("swing speed, worst-case load", f("%.0f deg/s", loadedDegrees), "loaded_deg_per_s",
                loadedDegrees);
        if (truthy(sweep) && loadedDegrees > 0) {
            report.result(f("time to sweep %.0f deg", sweep),
                    f("about %.2f s, using worst-case speed the whole way", sweep / loadedDegrees));
        }
        loadChecks(report, point, args, "holding the arm horizontal");
        report.note("Torque falls as the arm leaves horizontal (it scales with the cosine of the angle).");
        return report;
    }

    static Report drivetrainCommand(Args args) throws UsageException {
        Drive drive = driveFromArgs(args, 0.90);
        double mass = args.num("--mass");
        double wheel = args.num("--wheel-diameter");
        double friction = args.num("--friction");
        double speedFraction = args.num("--speed-fraction");
        double fuse = args.num("--fuse-a");
        if (mass <= 0) {
            throw new UsageException("--mass must be greater than zero");
        }
        if (wheel <= 0) {
            throw new UsageException("--wheel-diameter must be greater than zero");
        }
        if (speedFraction >= 1) {
            throw new UsageException("--speed-fraction must be below 1; free speed is never quite reached");
        }
        Report report = new Report("Drivetrain");
        describeDrive(report, drive);
        double radius = wheel / 2;
        report.input("robot mass", f("%.1f kg (%.1f lb)", mass, mass / POUND));
        report.input("wheel diameter", length(wheel));
        report.input("wheel-to-floor friction", f("%.2f%s", friction,
                friction == 1.0 ? "  (assumed; measure it for your wheels)" : ""));

        double freeSpeed = drive.freeRpm * RPM_TO_RAD * radius;
        double stallForce = drive.stallTorque / radius;
        double traction = friction * mass * GRAVITY;
        Point slip = drive.atTorque(traction * radius);
        report.result("free speed", speed(freeSpeed), "free_speed_mps", freeSpeed);
        report.result("push force at stall", force(stallForce), "stall_force_n", stallForce);
        report.result("push force before wheels slip", force(traction), "traction_force_n", traction);

        if (stallForce > traction) {
            report.result("current when the wheels slip", f("%.1f A per motor, %.1f A total", slip.currentEach,
                    slip.currentTotal), "slip_current_total_a", slip.currentTotal);
            report.check("OK", "pushing", f("wheels slip at %.0f%% of stall torque, before the motors stall",
                    slip.fraction * 100));
            if (slip.currentTotal > fuse) {
                report.check("WARN", "battery fuse", f("pushing to wheel slip draws %.1f A, above the %.0f A fuse. "
                        + "A fuse survives a short overload, but a sustained push can blow it or sag the battery",
                        slip.currentTotal, fuse));
            } else {
                report.check("OK", "battery fuse", f("pushing to wheel slip draws %.1f A (fuse %.0f A)",
                        slip.currentTotal, fuse));
            }
        } else {
            double total = drive.motorStallCurrent * drive.count;
            report.check("FAIL", "pushing", f("the motors stall before the wheels slip, so pushing against a wall "
                    + "or another robot stalls all %d motors at %.1f A total", drive.count, total));
        }

        // Time to reach a share of free speed: traction-limited first, then motor-limited.
        double target = speedFraction * freeSpeed;
        double acceleration = Math.min(traction, stallForce) / mass;
        double crossover = stallForce > traction ? freeSpeed * (1 - traction / stallForce) : 0.0;
        double tau = mass * freeSpeed / stallForce;
        double seconds;
        if (target <= crossover) {
            seconds = target / acceleration;
        } else {
            seconds = crossover / acceleration + tau * Math.log((freeSpeed - crossover) / (freeSpeed - target));
        }
        report.result("launch acceleration", f("%.1f m/s^2", acceleration));
        report.result(f("time to %.0f%% of free speed", speedFraction * 100),
                f("about %.2f s (ignores wheel inertia and rolling drag)", seconds), "time_to_speed_s", seconds);
        report.note("Real top speed is below free speed. For mecanum wheels, forward speed follows this "
                + "estimate and strafing is slower.");
        return report;
    }

    /**
     * Follows one shot. Returns {range, time, peak height} where the ball comes down through the
     * target height, or null when it never gets there. dragK is 0 for no air drag.
     */
    static double[] simulateShot(double speed, double angle, double release, double target, double dragK) {
        double vx = speed * Math.cos(angle);
        double vy = speed * Math.sin(angle);
        if (dragK == 0) {
            double disc = vy * vy + 2 * GRAVITY * (release - target);
            if (disc < 0) {
                return null;
            }
            double time = (vy + Math.sqrt(disc)) / GRAVITY;
            double apex = release + (vy > 0 ? vy * vy / (2 * GRAVITY) : 0.0);
            return new double[] {vx * time, time, apex};
        }
        double x = 0.0;
        double y = release;
        double time = 0.0;
        double step = 0.0005;
        double apex = release;
        while (time < 20) {
            double v = Math.hypot(vx, vy);
            double ax = -dragK * v * vx;
            double ay = -GRAVITY - dragK * v * vy;
            double nx = x + vx * step;
            double ny = y + vy * step;
            double nvx = vx + ax * step;
            double nvy = vy + ay * step;
            apex = Math.max(apex, ny);
            if (nvy < 0 && y >= target && target > ny) {
                double part = (y - target) / (y - ny);
                return new double[] {x + (nx - x) * part, time + step * part, apex};
            }
            if (ny < Math.min(target, 0.0) - 1) {
                return null;
            }
            x = nx;
            y = ny;
            vx = nvx;
            vy = nvy;
            time += step;
        }
        return null;
    }

    static Report flywheelCommand(Args args) throws UsageException {
        double wheel = args.num("--wheel-diameter");
        Double givenRpm = args.num("--rpm");
        Double givenRpm2 = args.num("--rpm2");
        Double wheel2 = args.num("--wheel2-diameter");
        double transfer = args.num("--transfer");
        Double ballDiameter = args.num("--ball-diameter");
        Double ballMass = args.num("--ball-mass");
        double ballInertiaFactor = args.num("--ball-inertia-factor");
        Double wheelMass = args.num("--wheel-mass");
        String wheelShape = args.text("--wheel-shape");
        Double angleDegrees = args.num("--angle-deg");
        double release = args.num("--release-height");
        Double givenTargetHeight = args.num("--target-height");
        Double targetDistance = args.num("--target-distance");
        Double dragCd = args.num("--drag-cd");
        double airDensity = args.num("--air-density");
        if (wheel <= 0) {
            throw new UsageException("--wheel-diameter must be greater than zero");
        }

        Report report = new Report("Flywheel launcher");
        Drive drive = null;
        boolean haveMotor = truthy(args.text("--motor")) || args.num("--free-rpm") != null;
        if (haveMotor) {
            drive = driveFromArgs(args, 1.0);
            describeDrive(report, drive);
        }
        double radius = wheel / 2;
        boolean dual = "dual".equals(args.text("--mode"));
        double radius2 = (truthy(wheel2) ? wheel2 : wheel) / 2;
        report.input("wheel diameter", length(wheel));
        report.input("layout", dual ? "two counter-rotating wheels" : "one wheel against a fixed hood");
        report.input("speed transfer", f("%.2f%s", transfer,
                transfer == 1 ? "  (ideal; measure a real shot and set --transfer)" : ""));
        Double angle = angleDegrees != null ? Double.valueOf(Math.toRadians(angleDegrees)) : null;
        double targetHeight = givenTargetHeight != null ? givenTargetHeight : release;

        double dragK = 0.0;
        if (truthy(dragCd)) {
            if (!truthy(ballMass) || !truthy(ballDiameter)) {
                throw new UsageException("--drag-cd needs --ball-mass and --ball-diameter");
            }
            double area = Math.PI * Math.pow(ballDiameter / 2, 2);
            dragK = 0.5 * airDensity * dragCd * area / ballMass;
            report.input("air drag", f("Cd %.2f, air density %.2f kg/m^3", dragCd, airDensity));
        }

        // Exit speed = transfer x (surface speed of wheel 1 + surface speed of wheel 2) / 2,
        // where a fixed hood counts as a second wheel that is not moving.
        double exitPerRad = transfer * (radius + (dual ? radius2 : 0.0)) / 2;

        double rpm;
        if (targetDistance != null) {
            if (angle == null) {
                throw new UsageException("--target-distance needs --angle-deg");
            }
            if (givenRpm != null || givenRpm2 != null) {
                throw new UsageException("--target-distance solves for the wheel speed; leave out --rpm and --rpm2");
            }
            report.input("target", f("%s away, %s high; released at %s, %.0f deg", distance(targetDistance),
                    length(targetHeight), length(release), angleDegrees));
            double low = 0.05;
            double high = 120.0;
            double[] best = simulateShot(high, angle, release, targetHeight, dragK);
            if (best == null || best[0] < targetDistance) {
                report.check("FAIL", "reach", "the target cannot be reached at this angle at any practical speed");
                return report;
            }
            for (int i = 0; i < 70; i++) {
                double middle = (low + high) / 2;
                double[] shot = simulateShot(middle, angle, release, targetHeight, dragK);
                if (shot == null || shot[0] < targetDistance) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            double needed = high;
            rpm = needed / exitPerRad / RPM_TO_RAD;
            report.result("exit speed needed", speed(needed), "needed_exit_speed_mps", needed);
        } else if (givenRpm != null) {
            rpm = givenRpm;
        } else {
            if (drive == null) {
                throw new UsageException("give --rpm, or --target-distance, or a motor to evaluate at free speed");
            }
            rpm = drive.freeRpm;
            report.note("No --rpm given, so this is the ceiling: the wheel at motor free speed.");
        }

        double rpm2 = dual && givenRpm2 != null ? givenRpm2 : rpm;
        double surface1 = rpm * RPM_TO_RAD * radius;
        double surface2 = dual ? rpm2 * RPM_TO_RAD * radius2 : 0.0;
        double exitSpeed = transfer * (surface1 + surface2) / 2;
        report.result("wheel speed", f("%.0f RPM", rpm), "wheel_rpm", rpm);
        if (dual && rpm2 != rpm) {
            report.result("second wheel speed", f("%.0f RPM", rpm2));
        }
        report.result("wheel surface speed", speed(surface1));
        report.result("ball exit speed", speed(exitSpeed), "exit_speed_mps", exitSpeed);

        Double spin = null;         // rad/s
        if (truthy(ballDiameter)) {
            double ballRadius = ballDiameter / 2;
            spin = transfer * (surface1 - surface2) / (2 * ballRadius);
            report.input("ball diameter", length(ballDiameter));
            report.result("ball spin", f("%.0f RPM%s", Math.abs(spin) / RPM_TO_RAD,
                    Math.abs(spin) > 1e-9 ? "" : " (none)"), "ball_spin_rpm", Math.abs(spin) / RPM_TO_RAD);
        }

        if (drive != null) {
            double share = rpm / drive.freeRpm;
            report.result("share of motor free speed", f("%.0f%% at %.1f V", share * 100, drive.volts),
                    "share_of_free_speed", share);
            if (truthy(drive.motor.ppr)) {
                double ticks = rpm * drive.ratio / 60 * drive.motor.ppr;
                report.result("velocity setpoint", f("%.0f encoder counts per second", ticks),
                        "setpoint_counts_per_s", ticks);
                noteComputedCounts(report, drive.motor);
            }
            if (share > 1) {
                report.check("FAIL", "wheel speed", f("%.0f RPM is above the %.0f RPM the motor can reach at %.1f V",
                        rpm, drive.freeRpm, drive.volts));
            } else if (share > 0.85) {
                report.check("WARN", "wheel speed", f("%.0f%% of free speed leaves little room to hold speed as the "
                        + "battery sags", share * 100));
            } else {
                report.check("OK", "wheel speed", f("%.0f%% of free speed (guideline: at most 85%%)", share * 100));
            }
        }

        // Energy per shot and flywheel slowdown
        Double inertia = args.num("--flywheel-inertia-kgm2");
        if (inertia == null && truthy(wheelMass)) {
            double perWheel = wheelMass * (radius * radius) * ("disk".equals(wheelShape) ? 0.5 : 1.0);
            inertia = perWheel * (dual ? 2 : 1);
            report.input("flywheel inertia", f("%.2e kg*m^2 (%s of %.0f g%s)", inertia, wheelShape,
                    wheelMass * 1000, dual ? ", two wheels" : ""));
        } else if (inertia != null) {
            report.input("flywheel inertia", f("%.2e kg*m^2", inertia));
        }
        if (truthy(ballMass)) {
            report.input("ball mass", f("%.1f g", ballMass * 1000));
            double energy = 0.5 * ballMass * (exitSpeed * exitSpeed);
            if (spin != null) {
                double ballInertia = ballInertiaFactor * ballMass * Math.pow(ballDiameter / 2, 2);
                energy += 0.5 * ballInertia * (spin * spin);
            }
            report.result("energy per shot", f("%.2f J", energy), "energy_per_shot_j", energy);
            if (truthy(inertia) && !(dual && rpm2 != rpm)) {
                double omega = rpm * RPM_TO_RAD;
                double stored = 0.5 * inertia * (omega * omega);
                if (energy >= stored) {
                    report.check("FAIL", "flywheel energy", f("one shot needs %.2f J but the flywheel stores only "
                            + "%.2f J", energy, stored));
                } else {
                    double after = Math.sqrt(omega * omega - 2 * energy / inertia);
                    double drop = 1 - after / omega;
                    report.result("wheel speed after one shot", f("%.0f RPM (drops %.1f%%, at least)",
                            after / RPM_TO_RAD, drop * 100), "speed_drop_fraction", drop);
                    if (drive != null && rpm < drive.freeRpm) {
                        double free = drive.freeRpm * RPM_TO_RAD;
                        double tau = inertia * free / drive.stallTorque;
                        double recover = tau * Math.log((free - after) / (free - omega));
                        double spinUp = tau * Math.log(free / (free - omega));
                        report.result("time to recover", f("%.2f s at full power", recover), "recovery_time_s",
                                recover);
                        report.result("time to spin up from rest", f("%.2f s at full power", spinUp),
                                "spin_up_time_s", spinUp);
                    }
                }
            }
        }

        // Where the shot lands
        if (angle != null) {
            if (targetDistance == null) {
                report.input("launch", f("%.0f deg above horizontal, released at %s, target height %s",
                        angleDegrees, length(release), length(targetHeight)));
            }
            double[] shot = simulateShot(exitSpeed, angle, release, targetHeight, dragK);
            if (shot == null) {
                report.result("range", "the ball never reaches the target height");
            } else {
                report.result(dragK != 0 ? "range with drag" : "range without air drag",
                        f("%s, %.2f s in the air, peak height %s", distance(shot[0]), shot[1], distance(shot[2])),
                        "range_m", shot[0]);
            }
            if (dragK == 0) {
                report.note("No air drag was modeled, so this range is an upper bound. Light, hollow, or "
                        + "perforated balls fall well short; test shots are the real answer. Use --drag-cd to "
                        + "estimate.");
            }
        }
        report.note("Exit speed assumes no slip between wheel, ball, and hood. Measure one real shot, then set "
                + "--transfer so the numbers match.");
        return report;
    }

    // ----------------------------------------------------------------------
    // Command table
    // ----------------------------------------------------------------------

    static final Map<String, Command> COMMANDS = new LinkedHashMap<String, Command>();

    private static Command command(String name, String help, Calculation calculation) {
        Command command = new Command(name, help, calculation);
        COMMANDS.put(name, command);
        return command;
    }

    private static Command driveOptions(Command command, int motors) {
        return command
                .opt("--motor", Kind.TEXT, "motor name from the 'motors' command, for example gobilda-435")
                .opt("--free-rpm", Kind.POSITIVE, "custom motor: free speed at 12 V")
                .opt("--stall-torque", Kind.TORQUE, "custom motor: stall torque, for example 18.7kgcm")
                .opt("--stall-current", Kind.POSITIVE, "custom motor: stall current in amps")
                .opt("--free-current", Kind.POSITIVE, "custom motor: free current in amps")
                .opt("--count", Kind.INTEGER, Integer.valueOf(motors), "motors sharing the load (default: " + motors + ")")
                .opt("--ratio", Kind.POSITIVE, "external reduction; above 1 slows the output, below 1 speeds it up")
                .opt("--stage", Kind.STAGE, "one external gear, belt, or chain stage by tooth count; repeat for more")
                .opt("--efficiency", Kind.POSITIVE, "overall mechanical efficiency, 0 to 1 (default: estimated)")
                .opt("--voltage", Kind.POSITIVE, 12.0, "battery voltage under load (default: 12)");
    }

    private static Command limitOptions(Command command) {
        return command
                .opt("--max-load-fraction", Kind.POSITIVE, 0.33,
                        "share of stall torque allowed for a sustained load (default: 0.33)")
                .opt("--port-limit-a", Kind.POSITIVE, 10.0,
                        "continuous current limit of one motor port (default: 10, REV hub)")
                .opt("--fuse-a", Kind.POSITIVE, 20.0, "main battery fuse (default: 20)");
    }

    static {
        command("motors", "List the built-in motor data.", MechCalc::motorsCommand);

        limitOptions(driveOptions(command("gear", "Speed, torque, and current through a gear train.",
                MechCalc::gearCommand), 1))
                .opt("--wheel-diameter", Kind.LENGTH, "wheel, roller, or pulley on the output")
                .opt("--load-torque", Kind.TORQUE, "torque demanded at the output")
                .opt("--load-force", Kind.FORCE, "force demanded at the wheel rim");

        limitOptions(driveOptions(command("lift", "Linear lift on a spool or pulley.",
                MechCalc::liftCommand), 1))
                .required("--mass", Kind.MASS, "everything the lift raises")
                .required("--spool-diameter", Kind.LENGTH, "spool or pulley the string winds on")
                .opt("--travel", Kind.LENGTH, "distance the load moves")
                .opt("--travel-multiplier", Kind.POSITIVE, 1.0, "moving stages of a cascade lift (default: 1)")
                .opt("--assist-force", Kind.FORCE, 0.0, "upward help from a spring or counterweight");

        limitOptions(driveOptions(command("arm", "Rotating arm, worst case horizontal.",
                MechCalc::armCommand), 1))
                .required("--payload-mass", Kind.MASS, "mass at the end of the arm")
                .required("--length", Kind.LENGTH, "pivot to payload")
                .opt("--arm-mass", Kind.MASS, 0.0, "mass of the arm itself")
                .opt("--com-distance", Kind.LENGTH, "pivot to the arm's center of mass (default: half the length)")
                .opt("--sweep-deg", Kind.POSITIVE, "angle to travel, for a time estimate");

        limitOptions(driveOptions(command("drivetrain", "Speed, pushing force, and current for the drive.",
                MechCalc::drivetrainCommand), 4))
                .required("--mass", Kind.MASS, "robot mass")
                .required("--wheel-diameter", Kind.LENGTH, "drive wheel diameter")
                .opt("--friction", Kind.POSITIVE, 1.0, "wheel-to-floor friction coefficient (default: 1.0)")
                .opt("--speed-fraction", Kind.POSITIVE, 0.8, "share of free speed for the time estimate (default: 0.8)");

        driveOptions(command("flywheel", "Ball exit speed, flywheel slowdown, and range.",
                MechCalc::flywheelCommand), 1)
                .required("--wheel-diameter", Kind.LENGTH, "flywheel diameter")
                .opt("--rpm", Kind.POSITIVE, "wheel speed to evaluate")
                .choice("--mode", "single", "single: one wheel and a fixed hood; dual: two counter-rotating wheels",
                        "single", "dual")
                .opt("--rpm2", Kind.POSITIVE, "dual: second wheel speed (default: same)")
                .opt("--wheel2-diameter", Kind.LENGTH, "dual: second wheel diameter (default: same)")
                .opt("--transfer", Kind.POSITIVE, 1.0, "measured share of the ideal exit speed (default: 1.0)")
                .opt("--ball-diameter", Kind.LENGTH, "ball diameter")
                .opt("--ball-mass", Kind.MASS, "ball mass")
                .opt("--ball-inertia-factor", Kind.POSITIVE, 2.0 / 3.0, "0.667 hollow shell, 0.4 solid (default: hollow)")
                .opt("--wheel-mass", Kind.MASS, "mass of one flywheel, to estimate inertia")
                .choice("--wheel-shape", "disk", "how the wheel's mass is spread (default: disk)", "disk", "ring")
                .opt("--flywheel-inertia-kgm2", Kind.POSITIVE, "total flywheel inertia, if known")
                .opt("--angle-deg", Kind.NUMBER, "launch angle above horizontal")
                .opt("--release-height", Kind.LENGTH, 0.0, "height the ball leaves the launcher (default: 0)")
                .opt("--target-height", Kind.LENGTH, "height of the target (default: the release height)")
                .opt("--target-distance", Kind.LENGTH, "solve for the wheel speed that reaches this far")
                .opt("--drag-cd", Kind.POSITIVE, "drag coefficient, to include air drag")
                .opt("--air-density", Kind.POSITIVE, 1.2, "kg/m^3 (default: 1.2)");
    }

    static String overview() {
        StringBuilder out = new StringBuilder();
        out.append("usage: java MechCalc.java COMMAND [options]\n\n");
        out.append("FTC mechanism calculator. Lengths, masses, forces, and torques need units:\n");
        out.append("96mm, 4in, 12lb, 5.4kg, 20N, 18.7kgcm.\n\ncommands:\n");
        for (Command command : COMMANDS.values()) {
            out.append(pad("  " + command.name, 14)).append(command.help).append("\n");
        }
        out.append("\nAdd --help after a command for its options, for example: java MechCalc.java lift --help\n");
        out.append("Exit codes: 0 all checks passed, 1 a check failed, 2 bad arguments.\n");
        return out.toString();
    }

    // ----------------------------------------------------------------------
    // Formatting helpers
    // ----------------------------------------------------------------------

    /** String.format with a fixed locale, so decimals always print with a dot. */
    static String f(String format, Object... values) {
        return String.format(Locale.ROOT, format, values);
    }

    static String length(double meters) {
        return f("%.1f mm (%.2f in)", meters * 1000, meters / INCH);
    }

    static String distance(double meters) {
        return meters < 1 ? length(meters) : f("%.2f m (%.1f ft)", meters, meters / FOOT);
    }

    static String speed(double metersPerSecond) {
        return f("%.2f m/s (%.1f in/s, %.1f ft/s)", metersPerSecond, metersPerSecond / INCH, metersPerSecond / FOOT);
    }

    static String force(double newtons) {
        return f("%.1f N (%.1f lbf, %.2f kgf)", newtons, newtons / POUND_FORCE, newtons / GRAVITY);
    }

    static String torque(double newtonMeters) {
        return f("%.3f N*m (%.1f kg.cm)", newtonMeters, newtonMeters / KGCM_TO_NM);
    }

    static boolean truthy(Double value) {
        return value != null && value != 0;
    }

    static boolean truthy(String value) {
        return value != null && !value.isEmpty();
    }

    static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }

    static String join(Iterable<String> parts) {
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

    static String jsonNumber(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "null" : Double.toString(value);
    }

    // ----------------------------------------------------------------------
    // Entry points
    // ----------------------------------------------------------------------

    /** Runs one command and returns its report, without printing. Used by the tests. */
    static Report calculate(String... argv) throws UsageException {
        Command command = commandNamed(argv);
        return command.calculation.run(parse(command, Arrays.asList(argv).subList(1, argv.length)));
    }

    private static Command commandNamed(String[] argv) throws UsageException {
        if (argv.length == 0) {
            throw new UsageException("give a command: " + join(COMMANDS.keySet()));
        }
        Command command = COMMANDS.get(argv[0]);
        if (command == null) {
            throw new UsageException("unknown command '" + argv[0] + "'. Commands: " + join(COMMANDS.keySet()));
        }
        return command;
    }

    /** Runs the command line, printing to the given streams, and returns the exit code. */
    static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            if (argv.length > 0 && (argv[0].equals("-h") || argv[0].equals("--help") || argv[0].equals("help"))) {
                out.print(overview());
                return EXIT_OK;
            }
            Command command = commandNamed(argv);
            Args args = parse(command, Arrays.asList(argv).subList(1, argv.length));
            if (args.help) {
                out.print(command.helpText());
                return EXIT_OK;
            }
            Report report = command.calculation.run(args);
            out.print(args.flag("--json") ? report.json() : report.text());
            String worst = report.worst();
            if (worst.equals("FAIL") || (worst.equals("WARN") && args.flag("--strict"))) {
                return EXIT_FAIL;
            }
            return EXIT_OK;
        } catch (UsageException error) {
            err.print("error: " + error.getMessage() + "\n");
            err.print("Run with --help for the commands, or add --help after a command for its options.\n");
            return EXIT_USAGE;
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
        int code = run(argv, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }
}
