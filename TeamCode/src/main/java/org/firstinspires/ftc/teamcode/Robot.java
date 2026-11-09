package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.Command;
import com.seattlesolvers.solverslib.command.DeferredCommand;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.command.SequentialCommandGroup;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.hardware.NewIMU;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;
import org.firstinspires.ftc.teamcode.subsystems.Intake;
import org.firstinspires.ftc.teamcode.subsystems.Launcher;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;

import java.util.List;

public class Robot {
    private final Drivetrain drivetrain;
    private final Intake intake;
    private final Launcher launcher;
    private final Telemetry telemetry;
    private final NewIMU imu;

    public Robot(HardwareMap hardwareMap, Telemetry telemetry) {
        imu = new NewIMU(hardwareMap, "imu");
        drivetrain = new Drivetrain(hardwareMap, "frontLeft", "frontRight", "backLeft", "backRight", imu);
        intake = new Intake(hardwareMap, "rightIntake", "leftIntake");

        launcher = new Launcher(hardwareMap, "flywheel", "turret", "switcher", "webcam");
        launcher.setDefaultCommand(new DeferredCommand(() -> {
              autoaim();
              return new InstantCommand();
        }, List.of(launcher)).perpetually());

        this.telemetry = telemetry;
    }

    public void drive(Drivetrain.DriveState state, GamepadEx gamepad, double limiter) {
        if (state == Drivetrain.DriveState.ROBOT_CENTRIC) drivetrain.driveRobotCentric(gamepad, limiter);
        else if (state == Drivetrain.DriveState.FIELD_CENTRIC) drivetrain.driveFieldCentric(gamepad, limiter);
        else throw new IllegalArgumentException("Not a valid Drive State");
    }

    public Command startIntake() {
        return new InstantCommand(intake::start);
    }

    public Command stopIntake() {
        return new InstantCommand(intake::stop);
    }

    public Command startLauncher() {
        return new InstantCommand(launcher::startLauncher);
    }

    public Command stopLauncher() {
        return new InstantCommand(launcher::stopLauncher);
    }

    public Command autoaim() {
        return new DeferredCommand(() -> {
            AprilTagClusterDetection cluster = launcher.getCluster();
            if (cluster == null) return new InstantCommand();
            return new SequentialCommandGroup(
                    new InstantCommand(launcher::resetTurret),
                    new InstantCommand(() -> launcher.aim(cluster))
            );
        }, null);
    }

    public Command setSwitcher(Launcher.SwitcherPosition position) {
        return new InstantCommand(() -> launcher.setPosition(position));
    }

    public boolean isIntakeActive() {
        return intake.getPower() != 0;
    }

    public boolean isLauncherActive() {
        return launcher.getPower() != 0;
    }

    public Launcher.SwitcherPosition getSwitcherPosition() {
        return launcher.getSwitcherPosition();
    }

    public Telemetry getTelemetry() {
        telemetry.addData("Launcher Power", launcher.getPower());
        telemetry.addData("Switcher Position", launcher.getSwitcherPosition());
        telemetry.addData("Turret Angle", launcher.getAngle());
        telemetry.addData("Detection Angle", launcher.getClusterAngle());
        telemetry.addData("Intake Power", intake.getPower());
        return telemetry;
    }
}
