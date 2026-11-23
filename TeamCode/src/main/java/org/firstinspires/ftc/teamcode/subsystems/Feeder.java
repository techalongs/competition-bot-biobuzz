package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.hardware.motors.CRServo;
import com.seattlesolvers.solverslib.hardware.motors.CRServoGroup;

public class Feeder extends SubsystemBase {
    private CRServoGroup feeders;

    public Feeder(HardwareMap hardwareMap, String leftFeeder, String rightFeeder) {
        CRServo lFeeder = new CRServo(hardwareMap, leftFeeder);
        CRServo rFeeder = new CRServo(hardwareMap, rightFeeder);

        lFeeder.setInverted(false);
        rFeeder.setInverted(true);

        feeders = new CRServoGroup(lFeeder, rFeeder);
    }

    public void start() {
        feeders.set(1);
    }

    public void stop() {
        feeders.set(0);
    }

    public double getPower() {
        return feeders.getRawPower();
    }
}
