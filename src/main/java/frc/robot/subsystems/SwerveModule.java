package frc.robot.subsystems;

import com.ctre.phoenix.sensors.*;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj.DutyCycleEncoder;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants.DriveConstants;
import frc.robot.Constants.DriveConstants.ModuleConstants;
import frc.robot.util.NomadMathUtil;
import frc.robot.util.sim.DutyCycleEncoderSim;
import frc.robot.util.sim.SparkMaxEncoderWrapper;
import io.github.oblarg.oblog.Loggable;
import io.github.oblarg.oblog.annotations.Log;

public class SwerveModule extends SubsystemBase implements Loggable {

    /**
     * Class to represent and handle a swerve module
     * A module's state is measured by a CANCoder for the absolute position, integrated CANEncoder for relative position
     * for both rotation and linear movement
     */

    private SwerveModuleState desiredState = new SwerveModuleState();

    private static final double rotationkP = 3;
    //private static final double rotationkD = 0.05 / 2.5;
    private static final double rotationkD = 0;

    private static final double drivekP = 4.6; // 0.06 w/measurement delay?

    private final CANSparkMax driveMotor;
    private final CANSparkMax rotationMotor;

    // These wrappers handle sim value holding, as well as circumventing the Spark MAX velocity signal delay
    // by integrating position. Credit for the latter to 6328. 
    private final SparkMaxEncoderWrapper driveEncoderWrapper;
    private final SparkMaxEncoderWrapper rotationEncoderWrapper;

    private final CANCoder canCoder;
    private final CANCoderSimCollection canCoderSim;

    private final ProfiledPIDController rotationPIDController;
    private final double magEncoderOffset;
    // logging position error because it's actually the "process variable", vs its derivative
    @Log(methodName="getPositionError", name="speedError")
    private final PIDController drivePIDController;
    private final String loggingName;


    public SwerveModule( ModuleConstants moduleConstants) {
        driveMotor = new CANSparkMax(moduleConstants.driveMotorID, MotorType.kBrushless);
        rotationMotor = new CANSparkMax(moduleConstants.rotationMotorID, MotorType.kBrushless);
        driveMotor.restoreFactoryDefaults(false);
        rotationMotor.restoreFactoryDefaults(false);
        magEncoderOffset = moduleConstants.magEncoderOffset;

        //set the output of the drive encoder to be in meters (instead of motor rots) for linear measurement
        // wheel diam * pi = wheel circumference (meters/wheel rot) *
        // 1/6.86 wheel rots per motor rot *
        // number of motor rots
        // = number of meters traveled
        driveMotor.getEncoder().setPositionConversionFactor(
            Math.PI * (DriveConstants.WHEEL_RADIUS_M * 2) // meters/ wheel rev
            / DriveConstants.WHEEL_ENC_COUNTS_PER_WHEEL_REV // 1/ (enc revs / wheel rev) = wheel rev/enc rev
        );

        //set the output of the drive encoder to be in meters per second (instead of motor rpm) for velocity measurement
        // wheel diam * pi = wheel circumference (meters/wheel rot) *
        // 1/60 minutes per sec *
        // 1/5.14 wheel rots per motor rot *
        // motor rpm = wheel speed, m/s
        driveMotor.getEncoder().setVelocityConversionFactor(
            (DriveConstants.WHEEL_RADIUS_M * 2) * Math.PI / 60 / DriveConstants.WHEEL_ENC_COUNTS_PER_WHEEL_REV
        );

        //set the output of the rotation encoder to be in radians
        // (2pi rad/(module rotation)) / 12.8 (motor rots/module rots)
        rotationMotor.getEncoder().setPositionConversionFactor(2.0 * Math.PI * DriveConstants.AZMTH_REVS_PER_ENC_REV);

        // Create the encoder wrappers after setting conversion factors so that the wrapper reads the conversions.
        driveEncoderWrapper = new SparkMaxEncoderWrapper(driveMotor);
        rotationEncoderWrapper = new SparkMaxEncoderWrapper(rotationMotor);
        //Config the mag encoder, which is directly on the module rotation shaft.
//        magEncoder = new DutyCycleEncoder(moduleConstants.magEncoderID);
        canCoder = new CANCoder(moduleConstants.magEncoderID);

        CANCoderConfiguration config = new CANCoderConfiguration();
        config.absoluteSensorRange = AbsoluteSensorRange.Unsigned_0_to_360;
        config.unitString = "rad";
        config.sensorCoefficient = 2 * Math.PI / 4096.0; // 2PI radians over 4096 ticks
        config.sensorTimeBase = SensorTimeBase.PerSecond;
        //config.magnetOffsetDegrees = moduleConstants.magEncoderOffset * 180.0 / Math.PI;
        config.initializationStrategy = SensorInitializationStrategy.BootToAbsolutePosition;

        canCoder.configAllSettings(config);

        //magEncoder.setPositionOffset(measuredOffsetRadians/(2*Math.PI));
        // The magnet in the module is not aligned straight down the direction the wheel points, but it is fixed in place.
        // This means we can subtract a fixed position offset from the encoder reading,
        // I.E. if the module is at 0 but the magnet points at 30 degrees, we can subtract 30 degrees from all readings
        //magEncoder.setPositionOffset(measuredOffsetRadians/(2*Math.PI));
        
        //Allows us to set what the mag encoder reads in sim.
        // Start with what it would read if the module is forward.
//        magEncoderSim = new DutyCycleEncoderSim(magEncoder);
        canCoderSim = canCoder.getSimCollection();

        //Drive motors should brake, rotation motors should coast (to allow module realignment)
        driveMotor.setIdleMode(IdleMode.kBrake);
        rotationMotor.setIdleMode(IdleMode.kBrake);

        driveMotor.setInverted(true);
        rotationMotor.setInverted(true);

        // Config the pid controllers

        // For a position controller we use a P loop on the position error
        // and a D loop, which is P on the derivative/rate of change of the position error
        // Theoretically, if the error is increasing (aka, the setpoint is getting away),
        // we should match the velocity of the setpoint with our D term to stabilize the error,
        // then add the additional output proportional to the size of the error.
        // Trapezoid Profile Constraints: 7.8 rot/s (limit of the NEO), 40 rot/s^2
        rotationPIDController = new ProfiledPIDController(rotationkP, 0.0, rotationkD, new TrapezoidProfile.Constraints(7.8*2 *Math.PI, 400*2*Math.PI));
        // Tell the PID controller that it can move across the -pi to pi rollover point.
        rotationPIDController.enableContinuousInput(-Math.PI, Math.PI);

        // For a velocity controller we just use P
        // (and feedforward, which is handled in #setDesiredStateClosedLoop)
        drivePIDController = new PIDController(drivekP, 0, 0);
        // Give this module a unique name on the dashboard so we have four separate sub-tabs.
        loggingName = "SwerveModule-" + moduleConstants.name + "-[" + driveMotor.getDeviceId() + ',' + rotationMotor.getDeviceId() + ']';
        resetDistance();
    }

    /**
     * We override this method to setup Oblog with the module's unique name.
     */
    public String configureLogName() {
        System.out.println(loggingName);
        return loggingName;
    }
    /**
     * Reset the driven distance to 0.
     */
    public void resetDistance() {
        driveEncoderWrapper.setPosition(0);
    }

    /**
     * Returns the distance driven by the module in meters since the last reset.
     * @return the distance in meters.
     */
    public double getDriveDistanceMeters() {
        return driveEncoderWrapper.getPosition();
    }
    
    /**
     * Returns the current angle of the module in radians, from the mag encoder.
     * @return a Rotation2d, where 0 is forward and pi/-pi is backward.
     */
    @Log(methodName = "getRadians")
    public Rotation2d getMagEncoderAngle() {
        double unsignedAngle = canCoder.getAbsolutePosition() - magEncoderOffset;
        //double unsignedAngle = canCoder.getAbsolutePosition() ;
        return new Rotation2d(unsignedAngle);
    }


    /**
     * Returns the current angle of the module in radians, from the mag encoder.
     * @return a Rotation2d, where 0 is forward and pi/-pi is backward.
     */
    @Log(methodName = "getRadians")
    public Rotation2d getRawCANCoderAngle() {
        double unsignedAngle = canCoder.getAbsolutePosition();
        return new Rotation2d(unsignedAngle);
    }

    /**
     * Returns the current angle of the module in radians, from the rotation NEO built-in encoder.
     * The sim model is immediate and perfect response, which is to say that in sim,
     * current angle is always desired angle.
     * @return a Rotation2d, where 0 is forward and pi/-pi is backward.
     */
    @Log(methodName = "getRadians")
    public Rotation2d getCanEncoderAngle() {
        return new Rotation2d(rotationEncoderWrapper.getPosition());
    }

    /**
     * Returns the current velocity of the module in meters per second.
     * The sim model is immediate and perfect response, which is to say that in sim,
     * current velocity is always desired velocity.
     * @return
     */
    @Log
    public double getCurrentVelocityMetersPerSecond() {
        return driveEncoderWrapper.getVelocity();
        // if(RobotBase.isSimulation()) {
        //     return driveEncoderSim.getVelocity();
        // }
        // else {
        //     return driveEncoder.getVelocity();
        // }
    }

    @Log
    public double getAppliedDriveVoltage() {
        return driveMotor.getAppliedOutput();
    }
    @Log
    public double getAppliedRotationVoltage() {
        return rotationMotor.getAppliedOutput();
    }


    /**
     * Initialize the integrated mag encoder
     * The mag encoder will read a (magnet offset) + (module offset from forward)
     * But we subtract the magnet offset in the encoder library, so when starting up, the encoder will report
     * the module offset from forward.
    */
    public void initRotationOffset() {
        rotationEncoderWrapper.setPosition(getMagEncoderAngle().getRadians());
        System.out.println("initRotationOffset "  + getMagEncoderAngle().getRadians());
    }

    /**
     * Method to set the desired state of the swerve module
     * Parameter: 
     * SwerveModuleState object that holds a desired linear and rotational setpoint
     * Uses PID and a feedforward to control the output
     */
    public void setDesiredStateClosedLoop(SwerveModuleState desiredState) {

        // Save the desired state for reference (Simulation assumes the modules always are at the desired state)
        
        desiredState = SwerveModuleState.optimize(desiredState, getCanEncoderAngle());
        desiredState = NomadMathUtil.optimize(desiredState, getCanEncoderAngle(), 90.0);
        SwerveModuleState previousState = this.desiredState;
        this.desiredState = desiredState;

        
        double goal = this.desiredState.angle.getRadians();
        double measurement = getCanEncoderAngle().getRadians();
        double rotationVolts = rotationPIDController.calculate(measurement, goal);
//        /*
        if (rotationVolts > 0.0) {
            rotationVolts += 0.04;
        } else if (rotationVolts < 0.0) {
            rotationVolts -= 0.04;
        }
//        */
        double driveVolts = drivePIDController.calculate(getCurrentVelocityMetersPerSecond(), this.desiredState.speedMetersPerSecond)
            + DriveConstants.driveFeedForward.calculate(this.desiredState.speedMetersPerSecond);
            // (this.desiredState.speedMetersPerSecond - previousState.speedMetersPerSecond) / 0.02);
        rotationMotor.setVoltage(rotationVolts);
        driveMotor.setVoltage(driveVolts);
    }

    public void periodic() {
    }

    public SwerveModuleState getCurrentState() {
        return new SwerveModuleState(
                getCurrentVelocityMetersPerSecond(),
                getCanEncoderAngle());
    }
    

    /**
     * Resets drive and rotation encoders to 0 position. (in sim and irl)
     */
    public void resetEncoders() {
        initRotationOffset();
        driveEncoderWrapper.setPosition(0);
    }
    
    /**
     * Set the state of the module as specified by the simulator
     * @param angle_rad
     * @param wheelPos_m
     * @param wheelVel_mps
     */
    public void setSimState(double angle_rad, double wheelPos_m, double wheelVel_mps) {
        rotationEncoderWrapper.setSimPosition(angle_rad);
        driveEncoderWrapper.setSimPosition(wheelPos_m);
        driveEncoderWrapper.setSimVelocity(wheelVel_mps);

        canCoderSim.setRawPosition((int) (angle_rad * 4096 / 2 * Math.PI));
    }

    @Log
    public double getRotationSetpoint() {
        return rotationPIDController.getSetpoint().position;
    }

    @Log
    public double getVelocitySetpoint() {
        return drivePIDController.getSetpoint();
    }
}