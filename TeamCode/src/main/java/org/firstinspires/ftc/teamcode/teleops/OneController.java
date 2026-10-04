package org.firstinspires.ftc.teamcode.teleops;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.ConditionalCommand;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

import org.firstinspires.ftc.teamcode.Drivetrain;
import org.firstinspires.ftc.teamcode.Robot;

@TeleOp(name = "One Controller TeleOp")
public class OneController extends OpMode {
    private Robot robot;
    private GamepadEx driver1;
    private Drivetrain.DriveState driveState;
    private double limiter;

    @Override
    public void init() {
        robot = new Robot(hardwareMap, telemetry);
        driver1 = new GamepadEx(gamepad1);

        driveState = Drivetrain.DriveState.ROBOT_CENTRIC;
        limiter = 0.5;

        // Drive State Button
        driver1.getGamepadButton(GamepadKeys.Button.TOUCHPAD)
                .whenPressed(new ConditionalCommand(
                        new InstantCommand(() -> driveState = Drivetrain.DriveState.ROBOT_CENTRIC),
                        new InstantCommand(() -> driveState = Drivetrain.DriveState.FIELD_CENTRIC),
                        () -> (driveState == Drivetrain.DriveState.FIELD_CENTRIC)
                ));

        // Fast Button
        driver1.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER)
                .whenHeld(new InstantCommand(() -> limiter = 0.8))
                .whenReleased(new InstantCommand(() -> limiter = 0.5));

        telemetry.addData("Status", "Initialized");
        telemetry.addData("Drive Mode", driveState);
        telemetry.update();
    }

    @Override
    public void loop() {
        CommandScheduler.getInstance().run();
        driver1.readButtons();

        robot.drive(driveState, driver1, limiter);

        telemetry.addData("Drive Mode", driveState);
        telemetry.addData("Drive Limiter", limiter);
        telemetry.update();
    }
}
