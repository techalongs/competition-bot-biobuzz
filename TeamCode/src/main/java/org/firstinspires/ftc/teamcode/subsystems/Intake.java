package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;
import com.seattlesolvers.solverslib.hardware.motors.MotorGroup;

public class Intake extends SubsystemBase {
    private final MotorGroup intake;

    public Intake(HardwareMap hardwareMap, String rIntake, String lIntake) {
        MotorEx rightIntake = new MotorEx(hardwareMap, rIntake);
        MotorEx leftIntake = new MotorEx(hardwareMap, lIntake);

        rightIntake.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        rightIntake.setInverted(true);
        rightIntake.setRunMode(Motor.RunMode.RawPower);

        leftIntake.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        leftIntake.setInverted(false);
        leftIntake.setRunMode(Motor.RunMode.RawPower);

        intake = new MotorGroup(rightIntake, leftIntake);
    }

    public void start() {
        this.intake.set(1);
    }

    public void stop() {
        this.intake.set(0);
    }

    public double getPower() {
        return this.intake.getRawPower();
    }
}
