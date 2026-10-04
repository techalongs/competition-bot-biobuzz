package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

public class Launcher {
    private final MotorEx flywheel;
    private double power;

    public Launcher(HardwareMap hardwareMap, String flywheel) {
        this.flywheel = new MotorEx(hardwareMap, flywheel); // TODO: add customCPR (28?) and customRPM (6000?)
        this.flywheel.setZeroPowerBehavior(Motor.ZeroPowerBehavior.FLOAT);
        this.flywheel.setInverted(false);
        this.flywheel.setRunMode(Motor.RunMode.RawPower);

        power = 0.1;
    }

    public void startLauncher() {
        this.flywheel.set(power);
    }

    public void stopLauncher() {
        this.flywheel.set(0);
    }

    public void increaseSpeed() {
        this.power += 0.1;
    }

    public void decreaseSpeed() {
        this.power -= 0.1;
    }
}
