package org.firstinspires.ftc.teamcode.autonomous;

import static com.pedropathing.api.Paths.line;

import com.pedropathing.api.PoseFactory;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.seattlesolvers.solverslib.pedroCommand.FollowPathCommand;

import org.firstinspires.ftc.teamcode.pedro.Constants;

public class CornerBasicAuto extends OpMode {

    private Follower follower;
    private final PoseFactory p = PoseFactory.degrees();

    private final Pose startPose = p.of(132, 8.75, 90);
    private final Pose park = p.of(132, 25, 90);


public void init() {
    follower = Constants.create(hardwareMap);
    follower.setPose(startPose);

    new FollowPathCommand(follower, parkPath(), true, 1.0).setGlobalMaxPower(1.0);

}

    @Override
    public void loop() {

        // Feedback to Driver Hub for debugging
        telemetry.addData("X", follower.pose().x());
        telemetry.addData("Y", follower.pose().y());
        telemetry.addData("Heading", Math.toDegrees(follower.pose().heading()));
        telemetry.addData("Follower Mode", follower.mode());
        telemetry.update();
    }

    private Path parkPath() {
        return line(startPose, park).linear(startPose, park);
    }

}
