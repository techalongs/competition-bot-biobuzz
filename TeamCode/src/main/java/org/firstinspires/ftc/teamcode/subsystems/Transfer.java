package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.CRServo;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

public class Transfer {

    public final MotorEx elevator;
    private final CRServo auger;

    public Transfer(HardwareMap hardwareMap, String auger, String elevator) {
        this.auger = new CRServo(hardwareMap, auger);
        this.auger.setInverted(false);

        this.elevator = new MotorEx(hardwareMap, elevator);
        this.elevator.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        this.elevator.setInverted(true);
        this.elevator.setRunMode(Motor.RunMode.RawPower);
    }

    public void start() {
        this.auger.set(1);
        this.elevator.set(1);
    }

    public void stop() {
        this.auger.set(0);
        this.elevator.set(0);
    }

    public void eject() {
        this.auger.set(-1);
        this.elevator.set(-1);
    }

    public double getPower() {
        return this.elevator.getRawPower();
    }
}
