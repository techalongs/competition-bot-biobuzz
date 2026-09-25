package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.drivebase.MecanumDrive;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

import org.firstinspires.ftc.teamcode.hardware.NewIMU;

public class Drivetrain {
    private final MecanumDrive drivetrain;
    private final NewIMU imu;

    public enum DriveState {
        ROBOT_CENTRIC,
        FIELD_CENTRIC
    }

    public Drivetrain(HardwareMap hardwareMap, String fL, String fR, String bL, String bR, NewIMU imu) {
        // TODO: Check RPMs
        MotorEx frontLeft = new MotorEx(hardwareMap, fL, Motor.GoBILDA.RPM_312);
        MotorEx frontRight = new MotorEx(hardwareMap, fR, Motor.GoBILDA.RPM_312);
        MotorEx backLeft = new MotorEx(hardwareMap, bL, Motor.GoBILDA.RPM_312);
        MotorEx backRight = new MotorEx(hardwareMap, bR, Motor.GoBILDA.RPM_312);

        frontLeft.setInverted(false);
        frontRight.setInverted(false);
        backLeft.setInverted(true);
        backRight.setInverted(false);

        frontLeft.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        frontRight.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        backLeft.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        backRight.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);

        frontLeft.stopAndResetEncoder();
        frontRight.stopAndResetEncoder();
        backLeft.stopAndResetEncoder();
        backRight.stopAndResetEncoder();

        frontLeft.setRunMode(Motor.RunMode.RawPower);
        frontRight.setRunMode(Motor.RunMode.RawPower);
        backLeft.setRunMode(Motor.RunMode.RawPower);
        backRight.setRunMode(Motor.RunMode.RawPower);

        drivetrain = new MecanumDrive(frontLeft, frontRight, backLeft, backRight);
        this.imu = imu;
    }

    public void driveRobotCentric(GamepadEx gamepad, double limiter) {
        double strafeSpeed = gamepad.getLeftX() * limiter;
        double forwardSpeed = gamepad.getLeftY() * limiter;
        double turnSpeed = gamepad.getRightX() * limiter;

        drivetrain.driveRobotCentric(strafeSpeed, forwardSpeed, turnSpeed);
    }

    public void driveFieldCentric(GamepadEx gamepad, double limiter) {
        double strafeSpeed = gamepad.getLeftX() * limiter;
        double forwardSpeed = gamepad.getLeftY() * limiter;
        double turnSpeed = gamepad.getRightX() * limiter;

        drivetrain.driveFieldCentric(strafeSpeed, forwardSpeed, turnSpeed, imu.getRotation2d().getDegrees());
    }
}
