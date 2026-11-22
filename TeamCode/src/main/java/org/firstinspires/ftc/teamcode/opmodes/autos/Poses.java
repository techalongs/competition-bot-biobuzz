package org.firstinspires.ftc.teamcode.opmodes.autos;

import com.pedropathing.api.PoseFactory;
import com.pedropathing.math.Pose;

public enum Poses {
    START_POSE(0, 0, 0);

    private final PoseFactory factory = PoseFactory.radians();
    Pose pose;
    Poses(double x, double y, double heading) {
        this.pose = factory.of(x, y, heading);
    }
}
