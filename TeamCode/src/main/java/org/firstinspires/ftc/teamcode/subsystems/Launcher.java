package org.firstinspires.ftc.teamcode.subsystems;

import android.util.Range;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

import org.firstinspires.ftc.teamcode.hardware.LogitechCamera;

public class Launcher {
    private final double SHOOT_POWER = 0.8;
    private final MotorEx flywheel;
    private final LogitechCamera camera;

    public Launcher(HardwareMap hardwareMap, String flywheel, String camera) {
        this.flywheel = new MotorEx(hardwareMap, flywheel); // TODO: add customCPR (28?) and customRPM (6000?)
        this.flywheel.setZeroPowerBehavior(Motor.ZeroPowerBehavior.FLOAT);
        this.flywheel.setInverted(true);
        this.flywheel.setRunMode(Motor.RunMode.RawPower);

        this.camera = new LogitechCamera(hardwareMap, camera);
    }

    public void startLauncher() {
        this.flywheel.set(SHOOT_POWER);
    }

    public void stopLauncher() {
        this.flywheel.set(0);
    }

    public double getPower() {
        return flywheel.getRawPower();
    }
}
