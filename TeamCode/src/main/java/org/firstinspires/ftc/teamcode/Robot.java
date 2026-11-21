package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.Command;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.command.SequentialCommandGroup;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.hardware.NewIMU;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;
import org.firstinspires.ftc.teamcode.subsystems.Intake;
import org.firstinspires.ftc.teamcode.subsystems.Launcher;
import org.firstinspires.ftc.teamcode.subsystems.Transfer;

public class Robot {
    private final Drivetrain drivetrain;
    private final Intake intake;
    private final Transfer transfer;
    private final Launcher launcher;
    private final Telemetry telemetry;
    private final NewIMU imu;

    public Robot(HardwareMap hardwareMap, Telemetry telemetry) {
        imu = new NewIMU(hardwareMap, "imu");
        drivetrain = new Drivetrain(hardwareMap, "frontLeft", "frontRight", "backLeft", "backRight", imu);
        intake = new Intake(hardwareMap, "intake");
        transfer = new Transfer(hardwareMap, "transfer");

        launcher = new Launcher(hardwareMap, "flywheel", "camera"); // TODO: Change camera id

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

    public Command startIntakeEject() { return new InstantCommand(intake::eject); }

    public Command startTransfer() { return new InstantCommand(transfer::start); }

    public Command stopTransfer() { return new InstantCommand(transfer::stop); }

    public Command startTransferEject() { return new InstantCommand(transfer::eject); }

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

    public boolean isIntakeActive() {
        return intake.getPower() != 0;
    }

    public boolean isTransferActive() { return transfer.getPower() != 0; }

    public boolean isLauncherActive() {
        return launcher.getPower() != 0;
    }

    public Telemetry getTelemetry() {
        telemetry.addData("Launcher Power", launcher.getPower());
        telemetry.addData("Intake Power", intake.getPower());
        return telemetry;
    }
}
