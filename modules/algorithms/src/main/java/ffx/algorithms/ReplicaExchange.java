/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2018.
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
package ffx.algorithms;

import java.io.IOException;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.math3.util.FastMath.exp;

import edu.rit.mp.DoubleBuf;
import edu.rit.pj.Comm;

import ffx.algorithms.thermostats.Thermostat;

/**
 * The ReplicaExchange implements temperature and lambda replica exchange
 * methods.
 *
 * @author Timothy D. Fenn and Michael J. Schnieders
 * @since 1.0
 */
public class ReplicaExchange implements Terminatable {

    private static final Logger logger = Logger.getLogger(ReplicaExchange.class.getName());
    private MolecularDynamics replica;
    private final AlgorithmListener algorithmListener;
    private final int nReplicas;
    private final Random random;
    private boolean done = false;
    private boolean terminate = false;

    /**
     * Parallel Java world communicator.
     */
    private final Comm world;
    /**
     * Number of processes is equal to the number of replicas.
     */
    private final int numProc;
    /**
     * Rank of this process.
     */
    private final int rank;

    /**
     * The parameters array stores communicated parameters for each process (i.e. each RepEx
     * system). Currently the array is of size [number of Processes][2].
     * <p>
     */
    private final double parameters[][];
    private double myParameters[];
    /**
     * Each parameter array is wrapped inside a Parallel Java DoubleBuf for the
     * All-Gather communication calls.
     */
    private final DoubleBuf parametersBuf[];
    private DoubleBuf myParametersBuf;

    private double temperatures[];
    private double lowTemperature;

    /**
     * ReplicaExchange constructor.
     *
     * @param molecularDynamics
     * @param listener
     * @param temperature
     */
    public ReplicaExchange(MolecularDynamics molecularDynamics,
                           AlgorithmListener listener, double temperature) {

        this.replica = molecularDynamics;
        this.algorithmListener = listener;
        this.lowTemperature = temperature;

        /**
         * Set up the Replica Exchange communication variables for Parallel Java
         * communication between nodes.
         */
        world = Comm.world();
        numProc = world.size();
        rank = world.rank();

        nReplicas = numProc;
        temperatures = new double[nReplicas];

        setExponentialTemperatureLadder(lowTemperature, 0.01);

        random = new Random();
        random.setSeed(0);

        // Create arrays to store the parameters of all processes.
        parameters = new double[nReplicas][2];
        parametersBuf = new DoubleBuf[nReplicas];
        for (int i = 0; i < nReplicas; i++) {
            parametersBuf[i] = DoubleBuf.buffer(parameters[i]);
        }

        /**
         * A convenience reference to the parameters of this process are updated
         * during communication calls.
         */
        myParameters = parameters[rank];
        myParametersBuf = parametersBuf[rank];
    }

    public void setTemperatures(double temperatures[]) {
        assert (temperatures.length == nReplicas);
        this.temperatures = temperatures;
    }

    public void setExponentialTemperatureLadder(double lowTemperature, double exponent) {
        for (int i = 0; i < nReplicas; i++) {
            temperatures[i] = lowTemperature * exp(exponent * i);
        }
    }

    public void sample(int cycles, int nSteps, double timeStep, double printInterval, double saveInterval) {
        done = false;
        terminate = false;
        for (int i = 0; i < cycles; i++) {
            /**
             * Check for termination request.
             */
            if (terminate) {
                done = true;
                break;
            }

            dynamic(nSteps, timeStep, printInterval, saveInterval);
            logger.info(String.format(" Applying exchange condition for cycle %d.", i));
            exchange();
        }
    }

    /**
     * All processes complete the exchanges identically given the same Random number seed.
     */
    private void exchange() {
        for (int i = 0; i < nReplicas - 1; i++) {
            double tempA = parameters[i][0];
            double tempB = parameters[i + 1][0];
            double betaA = Thermostat.convert / (tempA * Thermostat.kB);
            double betaB = Thermostat.convert / (tempB * Thermostat.kB);
            double energyA = parameters[i][1];
            double energyB = parameters[i + 1][1];
            /**
             * Compute the change in energy over kT (E/kT) for the Metropolis
             * criteria.
             */
            double deltaE = (energyA - energyB) * (betaB - betaA);

            logger.info(String.format(" RepEx attempt for systems %d and %d (dE=%10.4f)", i, i + 1, deltaE));

            /**
             * If the Metropolis criteria is satisfied, do the switch.
             */
            if (deltaE < 0.0 || random.nextDouble() < exp(-deltaE)) {
                /**
                 * Swap temperature and energy values.
                 */
                temperatures[i] = tempB;
                temperatures[i + 1] = tempA;

                parameters[i][0] = tempB;
                parameters[i + 1][0] = tempA;

                parameters[i][1] = energyB;
                parameters[i + 1][1] = energyA;

                logger.info(String.format(" Exchanged systems %d and %d.", i, i + 1));
            }
        }
    }

    /**
     * Blocking dynamic steps: when this method returns each replica has
     * completed the requested number of steps.
     *
     * @param nSteps
     * @param timeStep
     * @param printInterval
     * @param saveInterval
     */
    private void dynamic(final int nSteps, final double timeStep, final double printInterval,
                         final double saveInterval) {

        /**
         * Start this processes MolecularDynamics instance sampling.
         */
        boolean initVelocities = true;
        replica.dynamic(nSteps, timeStep, printInterval, saveInterval, temperatures[rank], initVelocities, null);

        /**
         * Update this ranks' parameter array to be consistent with the dynamics.
         */
        myParameters[0] = temperatures[rank];
        myParameters[1] = replica.currentPotentialEnergy;

        /**
         * Gather all parameters from the other processes.
         */
        try {
            world.allGather(myParametersBuf, parametersBuf);
        } catch (IOException ex) {
            String message = " Replica Exchange allGather failed.";
            logger.log(Level.SEVERE, message, ex);
        }
    }

    /**
     * This should be implemented as a blocking interrupt; when the method
     * returns the <code>Terminatable</code> algorithm has reached a clean
     * termination point. For example, between minimize or molecular dynamics
     * steps.
     */
    @Override
    public void terminate() {
        terminate = true;
        while (!done) {
            synchronized (this) {
                try {
                    wait(1);
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Exception terminating replica exchange.\n", e);
                }
            }
        }
    }

}
