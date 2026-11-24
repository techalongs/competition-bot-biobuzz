package org.firstinspires.ftc.teamcode.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.ConditionalCommand;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

import org.firstinspires.ftc.teamcode.Robot;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;
import org.firstinspires.ftc.teamcode.subsystems.Intake;
import org.firstinspires.ftc.teamcode.subsystems.Transfer;

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
        driver1.getGamepadButton(GamepadKeys.Button.CROSS)
                .whenHeld(new InstantCommand(() -> limiter = 0.8))
                .whenReleased(new InstantCommand(() -> limiter = 0.5));

        // Intake Start/Stop Button
        driver1.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER)
                .whenPressed(new ConditionalCommand(
                        robot.stopIntake(),
                        robot.startIntake(),
                        () -> (robot.getIntakeState() == Intake.IntakeState.ON)
                ));

        // Intake Eject Start/Stop Button
        driver1.getGamepadButton(GamepadKeys.Button.LEFT_BUMPER)
                .whenPressed(new ConditionalCommand(
                        robot.stopIntake(),
                        robot.ejectIntake(),
                        () -> (robot.getIntakeState() == Intake.IntakeState.REVERSE)
                ));

        // Transfer Start/Stop Button
        driver2.getGamepadButton(GamepadKeys.Button.DPAD_RIGHT)
                .whenPressed(new ConditionalCommand(
                        robot.stopTransfer(),
                        robot.startTransfer(),
                        () -> (robot.getTransferState() == Transfer.TransferState.ON)
                ));

        // Transfer Eject Start/Stop Button
        driver2.getGamepadButton(GamepadKeys.Button.DPAD_LEFT)
                .whenPressed(new ConditionalCommand(
                        robot.stopTransfer(),
                        robot.ejectTransfer(),
                        () -> (robot.getTransferState() == Transfer.TransferState.REVERSE)
                ));


        // Launcher Start/Stop Button
        driver2.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER)
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
