package frc.robot.commands.drivetrain;

import java.util.function.DoubleSupplier;

import edu.wpi.first.math.filter.SlewRateLimiter;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj2.command.CommandBase;
import frc.robot.Constants.DriveConstants;
import frc.robot.subsystems.DrivebaseS;

import static edu.wpi.first.math.MathUtil.applyDeadband;

public class OperatorControlC extends CommandBase {

    /**
     * Command to allow for driver input in teleop
     * Can't be inlined efficiently if we want to edit the inputs in any way (deadband, square, etc.)
     */

    private final DrivebaseS drive;

    /**
     * Joysticks return DoubleSuppliers when the get methods are called
     * This is so that joystick getter methods can be passed in as a parameter but will continuously update, 
     * versus using a double which would only update when the constructor is called
     */
    private final DoubleSupplier forwardX;
    private final SlewRateLimiter xRateLimiter = new SlewRateLimiter(3);
    private final DoubleSupplier forwardY;
    private final SlewRateLimiter yRateLimiter = new SlewRateLimiter(3);
    private final DoubleSupplier rotation;
    private final SlewRateLimiter thetaRateLimiter = new SlewRateLimiter(2);

    private final double MAX_LINEAR_SPEED = 4;

    private final double JOYSTICK_DEADBAND = 0.07;

    public OperatorControlC(
        DoubleSupplier fwdX, 
        DoubleSupplier fwdY, 
        DoubleSupplier rot,
        DrivebaseS subsystem
    ) {

        drive = subsystem;
        forwardX = fwdX;
        forwardY = fwdY;
        rotation = rot;

        addRequirements(subsystem);

    }

    @Override
    public void initialize() {
        xRateLimiter.reset(0);
        yRateLimiter.reset(0);
        thetaRateLimiter.reset(0);
    }
    
    @Override
    public void execute() {
        /**
         * Units are given in meters per second radians per second
         * Since joysticks give output from -1 to 1, we multiply the outputs by the max speed
         * Otherwise, our max speed would be 1 meter per second and 1 radian per second
         */

        double fwdX = -forwardX.getAsDouble();
        fwdX = Math.copySign(fwdX, fwdX);
        fwdX = applyDeadband(fwdX, JOYSTICK_DEADBAND);
        fwdX = xRateLimiter.calculate(fwdX);

        double fwdY = -forwardY.getAsDouble();
        fwdY = Math.copySign(fwdY, fwdY);
        fwdY = applyDeadband(fwdY, JOYSTICK_DEADBAND);
        fwdY = yRateLimiter.calculate(fwdY);

        double driveDirectionRadians = Math.atan2(fwdY, fwdX);
        double driveMagnitude = Math.hypot(fwdX, fwdY);
        driveMagnitude *= MAX_LINEAR_SPEED;
        fwdX = driveMagnitude * Math.cos(driveDirectionRadians);
        fwdY = driveMagnitude * Math.sin(driveDirectionRadians);

        double rot = -rotation.getAsDouble();
        //rot = Math.copySign(rot * rot, rot);
        rot = applyDeadband(rot, JOYSTICK_DEADBAND);
        rot = thetaRateLimiter.calculate(rot);
        rot *= Units.degreesToRadians(DriveConstants.teleopTurnRateDegPerSec);
        

        drive.driveFieldRelative(new ChassisSpeeds(fwdX, fwdY, rot));
//        drive.drive(new ChassisSpeeds(fwdX, fwdY, rot));
    }
}