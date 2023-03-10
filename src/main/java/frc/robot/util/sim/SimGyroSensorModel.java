package frc.robot.util.sim;

import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.simulation.SimDeviceSim;
import frc.robot.Robot;

public class SimGyroSensorModel{

    SimDeviceSim gyroSim;
    SimDouble rateSimDouble;
    SimDouble yawSimDouble;
    double gyroPosReading_deg;

    public SimGyroSensorModel(){
        if (RobotBase.isSimulation()) {
            gyroSim = new SimDeviceSim("navX-Sensor[0]");
            rateSimDouble = gyroSim.getDouble("Rate");
            yawSimDouble = gyroSim.getDouble("Yaw");
        }
    }

    public void resetToPose(Pose2d resetPose){
        if (RobotBase.isSimulation()) {
            yawSimDouble.set(resetPose.getRotation().getDegrees() * -1.0);
        }
    }

    public void update(Pose2d curRobotPose, Pose2d prevRobotPose) {
        if (RobotBase.isSimulation()) {
            double delta = curRobotPose.getRotation().minus(prevRobotPose.getRotation()).getDegrees();
            double gyroRate = (delta) / 0.02; //Gyro reads backward from sim reference frames.
            // Pass our model of what the sensor would be measuring back into the simGyro object
            // for the embedded code to interact with.
            rateSimDouble.set(gyroRate);
            yawSimDouble.set(yawSimDouble.get() + delta);
        }
    }

    public Rotation2d getRotation2d() {
        if (RobotBase.isSimulation()) {
            return Rotation2d.fromDegrees(yawSimDouble.get());
        }
        return new Rotation2d();
    }
}