/*
 * Tests for MechCalc.java. Expected values were worked by hand from the formulas in
 * reference/formulas.md. No test library is needed. From the skill folder:
 *
 *     javac -d build scripts/MechCalc.java tests/MechCalcTest.java
 *     java -cp build MechCalcTest
 *
 * Exit code 0 means every test passed. Delete the build folder afterward.
 */

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

public final class MechCalcTest {

    static final double KGCM = 0.0980665;
    static final double G = 9.80665;
    static final double RAD = 2 * Math.PI / 60;       // RPM to rad/s

    private static int failures;
    private static int checks;

    private MechCalcTest() {
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

    static void near(double actual, double expected, double tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + ", got " + actual);
    }

    static void near(double actual, double expected, String what) {
        near(actual, expected, 5e-8, what);
    }

    static void same(Object actual, Object expected, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + ": expected " + expected + ", got " + actual);
    }

    static void rejects(MechCalc.Quantity kind, String text) {
        try {
            MechCalc.quantity(kind, text);
            check(false, kind + " should reject '" + text + "'");
        } catch (MechCalc.UsageException expected) {
            check(true, "");
        }
    }

    // ---- helpers -----------------------------------------------------------

    static final class Run {
        int code;
        String out;
        String err;
    }

    static Run run(String... argv) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Run result = new Run();
        result.code = MechCalc.run(argv, new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"));
        result.out = out.toString("UTF-8");
        result.err = err.toString("UTF-8");
        return result;
    }

    static MechCalc.Report report(String... argv) throws Exception {
        return MechCalc.calculate(argv);
    }

    static double value(MechCalc.Report report, String key) {
        Double value = report.values.get(key);
        if (value == null) {
            throw new IllegalStateException("no value named " + key + " in " + report.values.keySet());
        }
        return value;
    }

    static String[] with(String[] base, String... more) {
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    static final String[] FLYWHEEL = {"flywheel", "--wheel-diameter", "96mm", "--ball-diameter", "71mm",
            "--ball-mass", "30g"};

    // ---- tests -------------------------------------------------------------

    public static void main(String[] argv) {
        test("unit conversions", () -> {
            near(MechCalc.quantity(MechCalc.Quantity.LENGTH, "4in"), 0.1016, "4in");
            near(MechCalc.quantity(MechCalc.Quantity.LENGTH, "96 mm"), 0.096, "96 mm");
            near(MechCalc.quantity(MechCalc.Quantity.MASS, "12lb"), 5.44310844, "12lb");
            near(MechCalc.quantity(MechCalc.Quantity.FORCE, "2kgf"), 19.6133, "2kgf");
            for (String text : new String[] {"18.7kgcm", "18.7 kg.cm", "18.7kg-cm", "18.7 KG*CM"}) {
                near(MechCalc.quantity(MechCalc.Quantity.TORQUE, text), 18.7 * KGCM, text);
            }
            near(MechCalc.quantity(MechCalc.Quantity.TORQUE, "2.1N-m"), 2.1, "2.1N-m");
            near(MechCalc.quantity(MechCalc.Quantity.TORQUE, "100ozin"), 0.706155183333, "100ozin");
        });

        test("a unit is required and the sign is checked", () -> {
            rejects(MechCalc.Quantity.LENGTH, "96");
            rejects(MechCalc.Quantity.LENGTH, "-5mm");
            rejects(MechCalc.Quantity.MASS, "3 stone");
            rejects(MechCalc.Quantity.TORQUE, "kgcm");
            rejects(MechCalc.Quantity.LENGTH, "4 kg");
        });

        test("gear stage parsing", () -> {
            double[] teeth = MechCalc.stage("20:40");
            near(teeth[0], 20.0, "driving");
            near(teeth[1], 40.0, "driven");
            for (String bad : new String[] {"2to1", "20:", "20:40:60", "0:40"}) {
                try {
                    MechCalc.stage(bad);
                    check(false, "stage should reject '" + bad + "'");
                } catch (MechCalc.UsageException expected) {
                    check(true, "");
                }
            }
        });

        test("motor table is complete and cited", () -> {
            check(MechCalc.MOTORS.size() >= 15, "at least 15 motors");
            for (MechCalc.Motor motor : MechCalc.MOTORS.values()) {
                check(motor.source.startsWith("https://"), motor.name + " cites a source");
                check(motor.stallTorque > 0, motor.name + " stall torque");
            }
            near(MechCalc.MOTORS.get("gobilda-435").stallTorque, 18.7 * KGCM, "gobilda-435 stall torque");
            near(MechCalc.MOTORS.get("rev-hd-hex").stallTorque, 0.105, "rev-hd-hex stall torque");
            same(MechCalc.MOTORS.get("gobilda-435").label, "goBILDA 5203 Yellow Jacket 13.7:1 (435 RPM)", "label");
            // Plausibility, to catch typos: geared stall torque should be near the bare motor's
            // times the ratio. The published figures run from 14% below to 3% above that.
            MechCalc.Motor base = MechCalc.MOTORS.get("gobilda-6000");
            for (MechCalc.Motor motor : MechCalc.MOTORS.values()) {
                if (motor.name.startsWith("gobilda-") && motor != base) {
                    double share = motor.stallTorque / (base.stallTorque * base.freeRpm / motor.freeRpm);
                    check(share > 0.80 && share < 1.06, motor.name + ": " + share + " of ideal");
                    // Encoder counts should be 28 per motor turn times the gearbox ratio. The speed
                    // in a motor's name is rounded (the 188:1 "30 RPM" motor turns nearer 32), so
                    // the two ways of getting the ratio differ by up to 6%.
                    double ratio = motor.ppr / 28.0;
                    double speedRatio = base.freeRpm / motor.freeRpm;
                    check(ratio / speedRatio > 0.93 && ratio / speedRatio < 1.03, motor.name + " counts per turn");
                }
            }
        });

        test("gear: reduction", () -> {
            MechCalc.Report r = report("gear", "--motor", "gobilda-1620", "--stage", "20:40",
                    "--wheel-diameter", "72mm", "--load-torque", "0.2Nm");
            double stall = 5.4 * KGCM * 2 * 0.95;
            near(value(r, "output_free_rpm"), 810.0, "free rpm");
            near(value(r, "output_stall_torque_nm"), stall, "stall torque");
            near(value(r, "counts_per_output_rev"), 207.6, "counts");
            near(value(r, "free_surface_speed_mps"), 810 * RAD * 0.036, "surface speed");
            double fraction = 0.2 / stall;
            near(value(r, "loaded_rpm"), 810 * (1 - fraction), "loaded rpm");
            near(value(r, "current_each_a"), 0.25 + (9.2 - 0.25) * fraction, "current");
            same(r.worst(), "OK", "worst");
        });

        test("gear: overdrive and voltage", () -> {
            MechCalc.Report r = report("gear", "--motor", "gobilda-435", "--ratio", "0.5", "--voltage", "10",
                    "--efficiency", "1");
            near(value(r, "output_free_rpm"), 435 * (10.0 / 12) / 0.5, "free rpm");
            near(value(r, "output_stall_torque_nm"), 18.7 * KGCM * (10.0 / 12) * 0.5, "stall torque");
        });

        test("gear: custom motor and overload", () -> {
            String[] motor = {"gear", "--free-rpm", "100", "--stall-torque", "1Nm", "--stall-current", "5"};
            same(report(with(motor, "--load-torque", "0.8Nm")).status("load"), "FAIL", "80% load");
            same(run(with(motor, "--load-torque", "0.8Nm")).code, 1, "exit code at 80% load");
            same(report(with(motor, "--load-torque", "0.4Nm")).status("load"), "WARN", "40% load");
            same(run(with(motor, "--load-torque", "0.4Nm")).code, 0, "exit code at 40% load");
            same(run(with(motor, "--load-torque", "0.4Nm", "--strict")).code, 1, "exit code with --strict");
        });

        test("gear: load given as a force at the rim", () -> {
            MechCalc.Report r = report("gear", "--motor", "gobilda-312", "--wheel-diameter", "100mm",
                    "--load-force", "10N");
            double fraction = 10 * 0.05 / (24.3 * KGCM);
            near(value(r, "loaded_rpm"), 312 * (1 - fraction), "loaded rpm");
        });

        test("lift: two motors", () -> {
            MechCalc.Report r = report("lift", "--motor", "gobilda-435", "--count", "2", "--mass", "3kg",
                    "--spool-diameter", "38mm", "--travel", "700mm");
            double stall = 2 * 18.7 * KGCM * 0.85;
            double torque = 3 * G * 0.019;
            double fraction = torque / stall;
            near(value(r, "hold_force_n"), 29.41995, "hold force");
            near(value(r, "load_fraction"), fraction, "load fraction");
            near(value(r, "current_each_a"), 0.25 + 8.95 * fraction, "current");
            double speed = 435 * (1 - fraction) * RAD * 0.019;
            near(value(r, "loaded_speed_mps"), speed, "speed");
            near(value(r, "travel_time_s"), 0.7 / speed, "travel time");
            near(value(r, "stall_mass_kg"), stall / 0.019 / G, "stall mass");
            Set<String> statuses = new HashSet<String>();
            for (String[] check : r.checks) {
                statuses.add(check[0]);
            }
            same(statuses.toString(), "[OK]", "all checks OK");
        });

        test("lift: cascade and spring assist", () -> {
            String[] base = {"lift", "--motor", "gobilda-435", "--mass", "2kg", "--spool-diameter", "40mm"};
            double plain = value(report(base), "hold_force_n");
            near(value(report(with(base, "--travel-multiplier", "3")), "hold_force_n"), 3 * plain, "cascade");
            near(value(report(with(base, "--assist-force", "1kgf")), "hold_force_n"), plain / 2, "assist");
        });

        test("lift: too heavy fails", () -> {
            String[] heavy = {"lift", "--motor", "gobilda-1620", "--mass", "10kg", "--spool-diameter", "50mm"};
            same(report(heavy).status("load"), "FAIL", "load check");
            same(run(heavy).code, 1, "exit code");
        });

        test("arm", () -> {
            MechCalc.Report r = report("arm", "--motor", "gobilda-60", "--payload-mass", "400g", "--length", "350mm",
                    "--arm-mass", "250g");
            double torque = G * (0.4 * 0.35 + 0.25 * 0.175);
            near(value(r, "pivot_torque_nm"), torque, "pivot torque");
            near(value(r, "load_fraction"), torque / (133.2 * KGCM * 0.95), "load fraction");
        });

        test("drivetrain: four motors", () -> {
            String[] drive = {"drivetrain", "--motor", "gobilda-435", "--mass", "12kg", "--wheel-diameter", "104mm"};
            MechCalc.Report r = report(drive);
            double free = 435 * RAD * 0.052;
            double stall = 4 * 18.7 * KGCM * 0.9 / 0.052;
            double traction = 12 * G;
            near(value(r, "free_speed_mps"), free, "free speed");
            near(value(r, "stall_force_n"), stall, "stall force");
            near(value(r, "traction_force_n"), traction, "traction");
            double fraction = traction / stall;
            near(value(r, "slip_current_total_a"), 4 * (0.25 + 8.95 * fraction), "slip current");
            double crossover = free * (1 - fraction);
            double expected = crossover / G + (12 * free / stall) * Math.log((free - crossover) / (free - 0.8 * free));
            near(value(r, "time_to_speed_s"), expected, "time to speed");
            same(r.status("pushing"), "OK", "pushing");
            same(r.status("battery fuse"), "WARN", "fuse is a brief overload, not a failure");
            same(run(drive).code, 0, "exit code");
        });

        test("drivetrain: fast gearing stalls before slipping", () -> {
            String[] drive = {"drivetrain", "--motor", "gobilda-1150", "--mass", "12kg", "--wheel-diameter", "104mm"};
            same(report(drive).status("pushing"), "FAIL", "pushing");
            same(run(drive).code, 1, "exit code");
        });

        test("flywheel: single wheel with hood", () -> {
            MechCalc.Report r = report(with(FLYWHEEL, "--motor", "gobilda-6000", "--rpm", "3200", "--wheel-mass",
                    "100g", "--angle-deg", "45", "--release-height", "300mm"));
            double surface = 3200 * RAD * 0.048;
            double exit = surface / 2;
            near(value(r, "exit_speed_mps"), exit, "exit speed");
            near(value(r, "ball_spin_rpm"), exit / 0.0355 / RAD, "spin");
            double energy = 0.5 * 0.03 * exit * exit * (1 + 2.0 / 3);
            near(value(r, "energy_per_shot_j"), energy, "energy");
            near(value(r, "share_of_free_speed"), 3200.0 / 6000, "share of free speed");
            near(value(r, "setpoint_counts_per_s"), 3200.0 / 60 * 28, "setpoint");
            double inertia = 0.5 * 0.1 * 0.048 * 0.048;
            double omega = 3200 * RAD;
            double after = Math.sqrt(omega * omega - 2 * energy / inertia);
            near(value(r, "speed_drop_fraction"), 1 - after / omega, "speed drop");
            double free = 6000 * RAD;
            double tau = inertia * free / (1.47 * KGCM);
            near(value(r, "recovery_time_s"), tau * Math.log((free - after) / (free - omega)), "recovery");
            near(value(r, "spin_up_time_s"), tau * Math.log(free / (free - omega)), "spin up");
            // 45 degrees with the target at release height: range = v^2 / g
            near(value(r, "range_m"), exit * exit / G, "range");
        });

        test("flywheel: two wheels", () -> {
            double surface = 3200 * RAD * 0.048;
            MechCalc.Report r = report(with(FLYWHEEL, "--mode", "dual", "--rpm", "3200"));
            near(value(r, "exit_speed_mps"), surface, "exit speed");
            near(value(r, "ball_spin_rpm"), 0.0, "spin");
            near(value(r, "energy_per_shot_j"), 0.5 * 0.03 * surface * surface, "energy");
            r = report(with(FLYWHEEL, "--mode", "dual", "--rpm", "3200", "--rpm2", "1600"));
            near(value(r, "exit_speed_mps"), surface * 0.75, "exit speed, unequal wheels");
            near(value(r, "ball_spin_rpm"), surface * 0.5 / (2 * 0.0355) / RAD, "spin, unequal wheels");
        });

        test("flywheel: transfer scales speed", () -> {
            MechCalc.Report r = report(with(FLYWHEEL, "--rpm", "3200", "--transfer", "0.8"));
            near(value(r, "exit_speed_mps"), 0.8 * 3200 * RAD * 0.048 / 2, "exit speed");
        });

        test("flywheel: solving for distance round-trips", () -> {
            String[] shot = with(FLYWHEEL, "--angle-deg", "50", "--release-height", "300mm", "--target-height",
                    "900mm");
            double range = value(report(with(shot, "--rpm", "3200")), "range_m");
            MechCalc.Report solved = report(with(shot, "--target-distance", MechCalc.f("%.6fm", range)));
            near(value(solved, "wheel_rpm"), 3200, 0.01, "solved rpm");
        });

        test("flywheel: drag shortens range and vanishes at zero", () -> {
            String[] shot = with(FLYWHEEL, "--rpm", "3200", "--angle-deg", "40", "--release-height", "300mm");
            double clean = value(report(shot), "range_m");
            double tiny = value(report(with(shot, "--drag-cd", "0.000001")), "range_m");
            double draggy = value(report(with(shot, "--drag-cd", "0.5")), "range_m");
            near(tiny, clean, clean * 0.003, "tiny drag matches no drag");
            check(draggy < clean * 0.9, "real drag shortens the range");
        });

        test("flywheel: speed checks", () -> {
            same(report(with(FLYWHEEL, "--motor", "gobilda-1620", "--rpm", "1500")).status("wheel speed"), "WARN",
                    "93% of free speed");
            String[] tooFast = with(FLYWHEEL, "--motor", "gobilda-1620", "--rpm", "2000");
            same(report(tooFast).status("wheel speed"), "FAIL", "above free speed");
            same(run(tooFast).code, 1, "exit code above free speed");
            MechCalc.Report geared = report(with(FLYWHEEL, "--motor", "gobilda-1620", "--ratio", "0.5", "--rpm",
                    "2000"));
            same(geared.status("wheel speed"), "OK", "geared up");
            near(value(geared, "setpoint_counts_per_s"), 2000 * 0.5 / 60 * 103.8, "setpoint");
        });

        test("flywheel: unreachable target", () -> {
            MechCalc.Report r = report(with(FLYWHEEL, "--angle-deg", "10", "--release-height", "100mm",
                    "--target-height", "50m", "--target-distance", "1m"));
            same(r.status("reach"), "FAIL", "reach");
        });

        test("flywheel: free speed is the ceiling when no rpm is given", () -> {
            MechCalc.Report r = report(with(FLYWHEEL, "--motor", "gobilda-1620"));
            near(value(r, "wheel_rpm"), 1620, "wheel rpm");
            same(r.status("wheel speed"), "WARN", "at free speed");
        });

        test("usage errors exit 2", () -> {
            same(run("gear", "--motor", "no-such-motor").code, 2, "unknown motor");
            same(run("gear").code, 2, "no motor");
            same(run("lift", "--motor", "gobilda-435", "--mass", "3", "--spool-diameter", "38mm").code, 2,
                    "mass without a unit");
            same(run("gear", "--motor", "gobilda-435", "--ratio", "2", "--stage", "1:2").code, 2,
                    "ratio and stage together");
            same(run("flywheel", "--wheel-diameter", "96mm").code, 2, "no speed to evaluate");
            same(run().code, 2, "no command");
            same(run("spin").code, 2, "unknown command");
            same(run("lift", "--motor", "gobilda-435", "--mass", "3kg").code, 2, "missing required option");
            same(run("gear", "--motor", "gobilda-435", "--bogus", "1").code, 2, "unknown option");
            same(run("gear", "--motor").code, 2, "option without its value");
            same(run("lift", "--motor", "gobilda-435", "--mass", "3kg", "--spool-diameter", "0mm").code, 2,
                    "zero spool diameter");
            same(run("drivetrain", "--motor", "gobilda-435", "--mass", "0kg", "--wheel-diameter", "104mm").code, 2,
                    "zero robot mass");
            Run bad = run("gear", "--motor", "no-such-motor");
            check(bad.err.startsWith("error: unknown motor"), "error text goes to stderr: " + bad.err);
            same(bad.out, "", "nothing on stdout for a usage error");
        });

        test("text output", () -> {
            Run motors = run("motors");
            same(motors.code, 0, "exit code");
            check(motors.out.contains("gobilda-435"), "lists gobilda-435");
            check(!motors.out.contains("Inputs and assumptions"), "no empty section header");
            check(motors.out.contains("751.8 counts/rev (computed)"), "computed counts are marked");
            Run arm = run("arm", "--motor", "gobilda-60", "--payload-mass", "400g", "--length", "350mm");
            check(arm.out.contains("[OK  ] load:"), "check line format");
            check(arm.out.contains("https://www.gobilda.com/content/spec_sheets/5203-2402-0100_spec_sheet.pdf"),
                    "the motor source is printed");
        });

        test("options: --name=value, help, and json", () -> {
            near(value(report("gear", "--motor=gobilda-1620", "--stage=20:40"), "output_free_rpm"), 810.0,
                    "--name=value form");
            Run help = run("lift", "--help");
            same(help.code, 0, "help exit code");
            check(help.out.contains("--spool-diameter LENGTH"), "help lists options");
            check(run("--help").out.contains("drivetrain"), "overview lists commands");
            Run json = run("gear", "--motor", "gobilda-1620", "--stage", "20:40", "--json");
            check(json.out.startsWith("{\n  \"title\": \"Gear train\""), "json starts with the title");
            check(json.out.contains("\"output_free_rpm\": 810.0"), "json carries raw values");
            check(json.out.trim().endsWith("\"worst\": \"OK\"\n}"), "json ends with the worst status");
            near(value(report("flywheel", "--wheel-diameter", "96mm", "--rpm", "3000", "--angle-deg", "-10",
                    "--release-height", "1m", "--target-height", "0m"), "wheel_rpm"), 3000, "negative angle");
        });

        System.out.println();
        System.out.println(failures == 0
                ? "All tests passed (" + checks + " checks)."
                : failures + " problem(s) in " + checks + " checks.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
