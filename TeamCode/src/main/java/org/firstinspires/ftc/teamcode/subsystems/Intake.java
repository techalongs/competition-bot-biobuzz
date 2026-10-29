package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

public class Intake extends SubsystemBase {
    public final MotorEx intake;

    public Intake(HardwareMap hardwareMap, String intake) {
        this.intake = new MotorEx(hardwareMap, intake);
        this.intake.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        this.intake.setInverted(false);
        this.intake.setRunMode(Motor.RunMode.RawPower);
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
