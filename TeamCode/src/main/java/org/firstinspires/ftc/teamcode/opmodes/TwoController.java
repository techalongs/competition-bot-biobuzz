package org.firstinspires.ftc.teamcode.opmodes;

import static org.firstinspires.ftc.robotcore.external.BlocksOpModeCompanion.telemetry;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.ConditionalCommand;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

import org.firstinspires.ftc.teamcode.Robot;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;

@TeleOp(name = "Two Controller TeleOp")
public class TwoController extends OpMode {
    private Robot robot;
    private GamepadEx driver1;
    private GamepadEx driver2;
    private Drivetrain.DriveState driveState;
    private double limiter;

    @Override
    public void init() {
        robot = new Robot(hardwareMap, telemetry);
        driver1 = new GamepadEx(gamepad1);
        driver2 = new GamepadEx(gamepad2);

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
        driver1.getGamepadButton(GamepadKeys.Button.CIRCLE)
                .whenHeld(new InstantCommand(() -> limiter = 0.8))
                .whenReleased(new InstantCommand(() -> limiter = 0.5));

        // Intake Start/Stop Button
        driver2.getGamepadButton(GamepadKeys.Button.DPAD_UP)
                .whenPressed(new ConditionalCommand(
                        robot.stopIntake(),
                        robot.startIntake(),
                        () -> (robot.isIntakeActive())
                ));
        // Eject Start/Stop Button// fix this.
        driver2.getGamepadButton(GamepadKeys.Button.DPAD_DOWN)
                .whenPressed(new ConditionalCommand(
                        robot.stopIntake(),
                        robot.startIntakeEject(),
                        () -> (robot.isIntakeActive())
                ));


        // Launcher Start/Stop Button
        driver2.getGamepadButton(GamepadKeys.Button.TRIANGLE)
                .whenPressed(new ConditionalCommand(
                        robot.stopLauncher(),
                        robot.startLauncher(),
                        () -> (robot.isLauncherActive())
                ));

        // Telemetry
        telemetry.addData("Status", "Initialized");
        telemetry.addData("Drive Mode", driveState);
        telemetry.update();
    }

    @Override
    public void loop() {
        CommandScheduler.getInstance().run();
        driver1.readButtons();
        driver2.readButtons();

        robot.drive(driveState, driver1, limiter);

        telemetry = robot.getTelemetry();
        telemetry.addData("Drive Mode", driveState);
        telemetry.addData("Drive Limiter", limiter);
        telemetry.update();
    }

}
