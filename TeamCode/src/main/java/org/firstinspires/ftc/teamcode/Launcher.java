package org.firstinspires.ftc.teamcode;

import android.util.Range;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

public class Launcher {
    private final static Range<Double> POWER_RANGE = Range.create(-1.0, 1.0);
    private final MotorEx flywheel;
    private double power;

    public Launcher(HardwareMap hardwareMap, String flywheel) {
        this.flywheel = new MotorEx(hardwareMap, flywheel); // TODO: add customCPR (28?) and customRPM (6000?)
        this.flywheel.setZeroPowerBehavior(Motor.ZeroPowerBehavior.FLOAT);
        this.flywheel.setInverted(true);
        this.flywheel.setRunMode(Motor.RunMode.RawPower);

        power = 0.1;
    }

    public void startLauncher() {
        this.flywheel.set(power);
    }

    public void stopLauncher() {
        this.power = 0;
        this.flywheel.set(power);
    }

    public void increaseSpeed() {
        this.power += 0.1;
        this.power = POWER_RANGE.clamp(this.power);
    }

    public void decreaseSpeed() {
        this.power -= 0.1;
        this.power = POWER_RANGE.clamp(this.power);
    }

    public double getPower() {
        return flywheel.getRawPower();
    }
}
