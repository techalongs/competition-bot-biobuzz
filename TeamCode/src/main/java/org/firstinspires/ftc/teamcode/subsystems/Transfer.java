package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.hardware.motors.CRServo;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;
import com.seattlesolvers.solverslib.hardware.motors.MotorGroup;

public class Transfer {
    private final MotorGroup transfer;

    public enum TransferState {
        ON(1),
        REVERSE(-1),
        OFF(0);
        final int power;
        TransferState(int power) {
            this.power = power;
        }
    }

    public Transfer(HardwareMap hardwareMap, String augerName, String elevatorName) {
        CRServo auger = new CRServo(hardwareMap, augerName);
        auger.setInverted(false);

        MotorEx elevator = new MotorEx(hardwareMap, elevatorName);
        elevator.setZeroPowerBehavior(Motor.ZeroPowerBehavior.BRAKE);
        elevator.setInverted(true);
        elevator.setRunMode(Motor.RunMode.RawPower);

        this.transfer = new MotorGroup(elevator, auger);
    }

    public void start() {
        this.transfer.set(TransferState.ON.power);
    }

    public void stop() {
        this.transfer.set(TransferState.OFF.power);
    }

    public void eject() {
        this.transfer.set(TransferState.REVERSE.power);
    }

    public TransferState getTransferState() {
        if (this.transfer.getRawPower() == TransferState.ON.power) return TransferState.ON;
        else if (this.transfer.getRawPower() == TransferState.REVERSE.power) return TransferState.REVERSE;
        return TransferState.OFF;
    }
}
