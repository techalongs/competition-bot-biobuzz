package org.firstinspires.ftc.teamcode.opmodes.autos;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

import org.firstinspires.ftc.teamcode.Robot;

@Autonomous(name = "Auto")
public class Auto extends OpMode {
    private Robot robot;
    private GamepadEx gamepad;
    private String[] autoType;

    @Override
    public void init() {
        robot = new Robot(hardwareMap, telemetry);
        gamepad = new GamepadEx(gamepad1);
        autoType = new String[] {"High", "Blue"};

        gamepad.getGamepadButton(GamepadKeys.Button.TRIANGLE)
                .whenPressed(new InstantCommand(() -> autoType[0] = "High"));

        gamepad.getGamepadButton(GamepadKeys.Button.CROSS)
                .whenPressed(new InstantCommand(() -> autoType[0] = "Low"));

        gamepad.getGamepadButton(GamepadKeys.Button.SQUARE)
                .whenPressed(new InstantCommand(() -> autoType[1] = "Blue"));

        gamepad.getGamepadButton(GamepadKeys.Button.CIRCLE)
                .whenPressed(new InstantCommand(() -> autoType[1] = "Red"));

        telemetry.addData("Status", "Initialized");
        telemetry.update();
    }

    @Override
    public void init_loop() {
        CommandScheduler.getInstance().run();
        gamepad.readButtons();

        telemetry.addData("Status", "Initialized");
        telemetry.addData("Auto Selected", autoType[0] + " " + autoType[1]);

        telemetry.addLine();
        telemetry.addLine("INSTRUCTIONS:");
        telemetry.addLine("Press Triangle for High");
        telemetry.addLine("Press Cross for Low");
        telemetry.addLine("Press Square for Blue");
        telemetry.addLine("Press Circle for Red");
        telemetry.update();
    }

    @Override
    public void loop() {

    }

    private void changeAutoType() {

    }
}