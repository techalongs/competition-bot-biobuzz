package org.firstinspires.ftc.teamcode.teleops;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

@TeleOp(name = "One Controller TeleOp")
public class OneController extends OpMode {
    private DcMotor frontRight;
    private DcMotor frontLeft;
    private DcMotor backRight;
    private DcMotor backLeft;
    private double limiter;

    @Override
    public void init() {
        frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        backRight = hardwareMap.get(DcMotor.class, "backRight");

        frontRight.setDirection(DcMotorSimple.Direction.FORWARD);
        frontLeft.setDirection(DcMotorSimple.Direction.FORWARD);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backRight.setDirection(DcMotorSimple.Direction.FORWARD);

        frontLeft.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        frontRight.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        backLeft.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        backRight.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);

        frontLeft.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        frontRight.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        backLeft.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        backRight.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);

        frontLeft.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        frontRight.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        backLeft.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        backRight.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);

        limiter = 0.5;

        telemetry.addData("Status", "Initialized");
        telemetry.update();
    }

    @Override
    public void loop() {
        float FLPower = (-gamepad1.left_stick_y + gamepad1.right_stick_x) + gamepad1.left_stick_x;
        float FRPower = (-gamepad1.left_stick_y - gamepad1.right_stick_x) - gamepad1.left_stick_x;
        float BLPower = (-gamepad1.left_stick_y + gamepad1.right_stick_x) - gamepad1.left_stick_x;
        float BRPower = (-gamepad1.left_stick_y - gamepad1.right_stick_x) + gamepad1.left_stick_x;

        if (gamepad1.right_bumper) limiter = 0.8;
        else limiter = 0.5;

        frontLeft.setPower(FLPower * limiter);
        frontRight.setPower(FRPower * limiter);
        backLeft.setPower(BLPower * limiter);
        backRight.setPower(BRPower * limiter);

        telemetry.addData("FL Power:", FLPower);
        telemetry.addData("FR Power:", FRPower);
        telemetry.addData("BL Power:", BLPower);
        telemetry.addData("BR Power:", BRPower);
        telemetry.update();
    }
}
