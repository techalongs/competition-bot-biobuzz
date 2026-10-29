package org.firstinspires.ftc.teamcode.subsystems;

import static org.firstinspires.ftc.teamcode.opmodes.TransferData.ALLIANCE;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.hardware.motors.Motor;
import com.seattlesolvers.solverslib.hardware.motors.MotorEx;
import com.seattlesolvers.solverslib.hardware.servos.ServoEx;

import org.firstinspires.ftc.teamcode.hardware.LogitechCamera;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;

import java.util.Comparator;

public class Launcher extends SubsystemBase {
    private final double SHOOT_POWER = 0.8;
    private final MotorEx flywheel;
    private final ServoEx turret;
    private final LogitechCamera camera;

    public Launcher(HardwareMap hardwareMap, String flywheel, String turret, String camera) {
        this.flywheel = new MotorEx(hardwareMap, flywheel); // TODO: add customCPR (28?) and customRPM (6000?)
        this.flywheel.setZeroPowerBehavior(Motor.ZeroPowerBehavior.FLOAT);
        this.flywheel.setInverted(true);
        this.flywheel.setRunMode(Motor.RunMode.RawPower);

        this.turret = new ServoEx(hardwareMap, turret, 150); // TODO: Test and ensure range
        this.turret.setInverted(false);

        this.camera = new LogitechCamera(hardwareMap, camera);
    }

    public void startLauncher() {
        this.flywheel.set(SHOOT_POWER);
    }

    public void stopLauncher() {
        this.flywheel.set(0);
    }

    public void aim() {
        // Minimize bearing

        // Get closest cluster that pertains to our alliance
        AprilTagClusterDetection cluster =
                camera.getDetections().stream()
                .map(d -> (AprilTagClusterDetection) d)
                .filter(c -> !c.metadata.name.contains(ALLIANCE)) // In alliance
                .min(Comparator.comparingDouble(c -> c.ftcPose.y)) // Closest
                .orElse(null);

        if (cluster == null) return;

        double bearing = cluster.ftcPose.bearing; // Positive - left, Negative - right
        turret.set(turret.get() + bearing);
    }

    public double getPower() {
        return flywheel.getRawPower();
    }

    public double getAngle() {
        return turret.getRawPosition();
    }
}
