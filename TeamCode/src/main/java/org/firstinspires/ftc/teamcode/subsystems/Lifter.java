package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.hardware.motors.CRServo;
import com.seattlesolvers.solverslib.hardware.servos.ServoEx;

public class Lifter extends SubsystemBase {
    private CRServo lifter;
    private ServoEx starter;
    private ServoEx gate;

    // TODO: Need to add limit switch on lifter, probably magnetic
    // TODO: Then, finish lifter code

    public enum GatePosition {
        OPEN(0.4),
        CLOSE(0);

        double pos;
        GatePosition(double pos) {
            this.pos = pos;
        }
    }

    public Lifter(HardwareMap hardwareMap, String lifter, String starter, String gate) {
        this.lifter = new CRServo(hardwareMap, lifter);
        this.lifter.setInverted(false);

        this.starter = new ServoEx(hardwareMap, starter);
        this.starter.setInverted(false);

        this.gate = new ServoEx(hardwareMap, gate);
        this.gate.setInverted(false);
    }

    public void raiseLifter() {
        this.lifter.set(1);
    }

    public void lowerLifter() {
        this.lifter.set(0);
    }

    public void lowerStarter() {
        this.starter.set(0.5);
    }

    public void raiseStarter() {
        this.starter.set(0);
    }

    public void openGate() {
        this.gate.set(GatePosition.OPEN.pos);
    }

    public void closeGate() {
        this.gate.set(GatePosition.CLOSE.pos);
    }

    public GatePosition getGatePosition() {
        return (this.gate.getRawPosition() == GatePosition.OPEN.pos) ?
                GatePosition.OPEN : GatePosition.CLOSE;
    }
}
