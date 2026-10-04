package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.Command;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.command.SequentialCommandGroup;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.hardware.NewIMU;

public class Robot {
    private final Drivetrain drivetrain;
    private final Launcher launcher;
    private final Telemetry telemetry;
    private final NewIMU imu;

    public Robot(HardwareMap hardwareMap, Telemetry telemetry) {
        imu = new NewIMU(hardwareMap, "imu");
        drivetrain = new Drivetrain(hardwareMap, "frontLeft", "frontRight", "backLeft", "backRight", imu);

        launcher = new Launcher(hardwareMap, "flywheel");

        this.telemetry = telemetry;
    }

    public void drive(Drivetrain.DriveState state, GamepadEx gamepad, double limiter) {
        if (state == Drivetrain.DriveState.ROBOT_CENTRIC) drivetrain.driveRobotCentric(gamepad, limiter);
        else if (state == Drivetrain.DriveState.FIELD_CENTRIC) drivetrain.driveFieldCentric(gamepad, limiter);
        else throw new IllegalArgumentException("Not a valid Drive State");
    }

    public Command startLauncher() {
        return new InstantCommand(launcher::startLauncher);
    }

    public Command stopLauncher() {
        return new InstantCommand(launcher::stopLauncher);
    }

    public Command increaseLauncherSpeed() {
        return new SequentialCommandGroup(
                new InstantCommand(launcher::increaseSpeed),
                new InstantCommand(launcher::startLauncher)
        );
    }

    public Command decreaseLauncherSpeed() {
        return new SequentialCommandGroup(
                new InstantCommand(launcher::decreaseSpeed),
                new InstantCommand(launcher::startLauncher)
        );
    }

    public boolean isLauncherActive() {
        return launcher.getPower() > 0;
    }

    public Telemetry getTelemetry() {
        telemetry.addData("Launcher Power", (isLauncherActive()) ? launcher.getPower() : 0);
        return telemetry;
    }
}
