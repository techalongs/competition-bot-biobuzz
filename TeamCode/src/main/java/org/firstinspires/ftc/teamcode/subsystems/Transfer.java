package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;

public class Transfer {

    public final MotorEx transfer;

    public Transfer(HardwareMap hardwareMap, String transfer) {
        this.transfer = new MotorEx(hardwareMap, transfer);
        this.transfer.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        this.transfer.setInverted(false);
        this.transfer.setRunMode(Motor.RunMode.RawPower);
    }

    public void start() { this.transfer.set(1);}
    public void stop() { this.transfer.set(0);}
    public void eject() { this.transfer.set(-1);}
    public double getPower() {return this.transfer.getRawPower();}
}
