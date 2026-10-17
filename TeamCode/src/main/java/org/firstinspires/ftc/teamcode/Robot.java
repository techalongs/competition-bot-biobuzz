package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.hardware.NewIMU;

public class Robot {
    private final Drivetrain drivetrain;
    private final Telemetry telemetry;
    private final NewIMU imu;

    public Robot(HardwareMap hardwareMap, Telemetry telemetry) {
        imu = new NewIMU(hardwareMap, "imu");
        drivetrain = new Drivetrain(hardwareMap, "frontLeft", "frontRight", "backLeft", "backRight", imu);

        this.telemetry = telemetry;
    }

    public void drive(Drivetrain.DriveState state, GamepadEx gamepad, double limiter) {
        if (state == Drivetrain.DriveState.ROBOT_CENTRIC) drivetrain.driveRobotCentric(gamepad, limiter);
        else if (state == Drivetrain.DriveState.FIELD_CENTRIC) drivetrain.driveFieldCentric(gamepad, limiter);
        else throw new IllegalArgumentException("Not a valid Drive State");
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }
}
