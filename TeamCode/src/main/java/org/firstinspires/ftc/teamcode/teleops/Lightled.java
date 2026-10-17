package org.firstinspires.ftc.teamcode.teleops;

import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.LED;

@TeleOp(name = "Concept: RevLED", group = "Concept")
public class Lightled extends OpMode {
    LED frontLED;

    @Override
    public void init() {
        frontLED = hardwareMap.get(LED.class, "front_led");
    }

    @Override
    public void loop() {
        if (gamepad1.a) {
            frontLED.on();
        } else {
            frontLED.off();
        }
    }
}
