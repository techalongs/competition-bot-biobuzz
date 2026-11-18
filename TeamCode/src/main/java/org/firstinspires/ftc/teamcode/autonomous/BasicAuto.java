package org.firstinspires.ftc.teamcode.autonomous;

import com.pedropathing.api.PoseFactory;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.teamcode.pedro.Constants;

public class BasicAuto extends OpMode {

    private Follower follower;
    private final PoseFactory p = PoseFactory.degrees();

    private final Pose startPose = p.of(132, 8.75, 90);
    private final Pose park = p.of(132, 25, 90);


public void init() {
    follower = Constants.create(hardwareMap);
    follower.setPose(startPose);
}

    @Override
    public void loop() {

    }

}
