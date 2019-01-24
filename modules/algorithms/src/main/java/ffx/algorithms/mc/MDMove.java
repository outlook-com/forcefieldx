/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2019.
 * <p>
 * This file is part of Force Field X.
 * <p>
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 * <p>
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * <p>
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx.algorithms.mc;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.lang.Math.abs;
import static java.lang.String.format;

import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.io.FilenameUtils;

import ffx.algorithms.AlgorithmListener;
import ffx.algorithms.MolecularDynamics;
import ffx.algorithms.MolecularDynamicsOpenMM;
import ffx.algorithms.integrators.IntegratorEnum;
import ffx.algorithms.thermostats.ThermostatEnum;
import ffx.numerics.Potential;
import ffx.potential.MolecularAssembly;

/**
 * Use MD as a coordinate based MC move.
 *
 * @author Mallory R. Tollefson
 */
public class MDMove implements MCMove {

    private static final Logger logger = Logger.getLogger(MDMove.class.getName());

    /**
     * Number of MD steps per move.
     */
    private int mdSteps = 50;
    /**
     * Time step in femtoseconds.
     */
    private double timeStep = 1.0;
    /**
     * Print interval in picoseconds.
     */
    private double printInterval = 0.05;
    /**
     * Temperature in Kelvin.
     */
    private double temperature = 298.15;
    private boolean initVelocities = true;
    private int mdMoveCounter = 0;
    private double energyDriftTotalAbs;
    private double energyDriftTotalNet;
    private double energyDriftAverageAbs;
    private double energyDriftAverageNet;
    private double dt;
    private int intervalSteps;
    private double normalizedEnergyDriftAbs;
    private double normalizedEnergyDriftNet;
    private int natoms;


    private final double saveInterval = 10000.0;
    private final MolecularDynamics molecularDynamics;

    /**
     * <p>Constructor for MDMove.</p>
     *
     * @param assembly            a {@link ffx.potential.MolecularAssembly} object.
     * @param potentialEnergy     a {@link ffx.numerics.Potential} object.
     * @param properties          a {@link org.apache.commons.configuration2.CompositeConfiguration} object.
     * @param listener            a {@link ffx.algorithms.AlgorithmListener} object.
     * @param requestedThermostat a {@link ffx.algorithms.thermostats.ThermostatEnum} object.
     * @param requestedIntegrator a {@link ffx.algorithms.integrators.IntegratorEnum} object.
     */
    public MDMove(MolecularAssembly assembly, Potential potentialEnergy,
                  CompositeConfiguration properties, AlgorithmListener listener,
                  ThermostatEnum requestedThermostat, IntegratorEnum requestedIntegrator) {

        molecularDynamics = MolecularDynamics.dynamicsFactory(assembly,
                potentialEnergy, properties, listener, requestedThermostat, requestedIntegrator);

        /**
         * Ensure at least one interval is printed.
         */
        if (printInterval < mdSteps * timeStep) {
            printInterval = mdSteps * timeStep;
        }


        String name = assembly.getFile().getAbsolutePath();
        File dyn = new File(FilenameUtils.removeExtension(name) + ".dyn");
        if (!dyn.exists()) {
            dyn = null;
        }

        molecularDynamics.init(mdSteps, timeStep, printInterval, saveInterval, temperature, true, dyn);

        molecularDynamics.setQuiet(true);

    }

    /**
     * <p>Setter for the field <code>temperature</code>.</p>
     *
     * @param temperature a double.
     */
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /**
     * <p>initVelocities.</p>
     *
     * @param initVelocities a boolean.
     */
    public void initVelocities(boolean initVelocities) {
        this.initVelocities = initVelocities;
    }

    /**
     * <p>setMDParameters.</p>
     *
     * @param mdSteps  a int.
     * @param timeStep a double.
     */
    public void setMDParameters(int mdSteps, double timeStep) {
        this.mdSteps = mdSteps;
        this.timeStep = timeStep;
        printInterval = mdSteps * timeStep / 1000;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void move() {
        mdMoveCounter++;

        molecularDynamics.dynamic(mdSteps, timeStep, printInterval, saveInterval, temperature, initVelocities, null);

        if (molecularDynamics instanceof MolecularDynamicsOpenMM && logger.isLoggable(Level.FINE)) {
            energyDriftTotalNet += molecularDynamics.getEndTotalEnergy() - molecularDynamics.getStartingTotalEnergy();
            energyDriftAverageNet = energyDriftTotalNet / mdMoveCounter;
            energyDriftTotalAbs += abs(molecularDynamics.getStartingTotalEnergy() - molecularDynamics.getEndTotalEnergy());
            energyDriftAverageAbs = energyDriftTotalAbs / mdMoveCounter;
            logger.fine(format(" Mean signed/unsigned energy drift:                   %8.4f/%8.4f",
                    energyDriftAverageNet, energyDriftAverageAbs));

            dt = molecularDynamics.getTimeStep();
            intervalSteps = molecularDynamics.getIntervalSteps();
            natoms = molecularDynamics.getNumAtoms();
            normalizedEnergyDriftNet = (energyDriftAverageNet / (dt * intervalSteps * natoms)) * 1000;
            normalizedEnergyDriftAbs = (energyDriftAverageAbs / (dt * intervalSteps * natoms)) * 1000;
            logger.fine(format(" Mean singed/unsigned energy drift per psec per atom: %8.4f/%8.4f\n",
                    normalizedEnergyDriftNet, normalizedEnergyDriftAbs));
        }
    }

    /**
     * <p>getStartingKineticEnergy.</p>
     *
     * @return a double.
     */
    public double getStartingKineticEnergy() {
        return molecularDynamics.getStartingKineticEnergy();
    }

    /**
     * <p>getKineticEnergy.</p>
     *
     * @return a double.
     */
    public double getKineticEnergy() {
        return molecularDynamics.getKineticEnergy();
    }

    /**
     * <p>getPotentialEnergy.</p>
     *
     * @return a double.
     */
    public double getPotentialEnergy() {
        return molecularDynamics.getPotentialEnergy();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void revertMove() {
        try {
            molecularDynamics.revertState();
        } catch (Exception ex) {
            logger.severe(" The MD state could not be reverted.");
        }
    }

    /**
     * <p>getMDTime.</p>
     *
     * @return a long.
     */
    public long getMDTime() {
        return molecularDynamics.getMDTime();
    }
    
    /**
     * Write coordinate and velocity restart files out for MCOSRW.
     */
    public void writeRestart(){
        molecularDynamics.writeRestart();
    }
    
    public void writeLambdaThresholdRestart(double lambda, double lambdaWriteOut){
        molecularDynamics.writeLambdaThresholdRestart(lambda, lambdaWriteOut);
    }

}
