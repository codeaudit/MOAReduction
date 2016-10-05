package moa.classifiers.meta;

import java.util.ArrayList;
import java.util.List;

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.github.javacliparser.MultiChoiceOption;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.Instances;

import moa.classifiers.AbstractClassifier;
import moa.classifiers.Classifier;
import moa.classifiers.lazy.kNN;
import moa.core.DoubleVector;
import moa.core.Measurement;
import moa.options.ClassOption;

/**
 * Learning in non-stationary environments.
 *
 * <p>
 * Ryan Elwell and Robi Polikar. Incremental learning of concept drift in
 * non-stationary environments. IEEE Transactions on Neural Networks,
 * 22(10):1517-1531, October 2011. ISSN 1045-9227. URL
 * http://dx.doi.org/10.1109/TNN.2011.2160459.
 * </p>
 *
 * @author Paulo Goncalves (paulomgj at gmail dot com)
 * @author Dariusz Brzezinski
 *
 * @version 0.4 (Corrected instance weights in classifier training)
 *
 */
@SuppressWarnings("serial")
public class LearnNSE extends AbstractClassifier {

    public ClassOption baseLearnerOption = new ClassOption("baseLearner", 'l',
            "Classifier to train.", Classifier.class, "lazy.kNN");
    
    public IntOption kOption = new IntOption("k", 'k',
            "Number of neighbors.", 3, 1, 50);

    public IntOption periodOption = new IntOption("period", 'p',
            "Size of the environments.", 500, 1, Integer.MAX_VALUE);

    public FloatOption sigmoidSlopeOption = new FloatOption(
            "sigmoidSlope",
            'a',
            "Slope of the sigmoid function controlling the number "
            + "of previous periods taken into account during weighting.",
            0.5, 0, Float.MAX_VALUE);

    public FloatOption sigmoidCrossingPointOption = new FloatOption(
            "sigmoidCrossingPoint",
            'b',
            "Halfway crossing point of the sigmoid function controlling the number of previous "
            + "periods taken into account during weighting.", 10, 0,
            Float.MAX_VALUE);

    public IntOption ensembleSizeOption = new IntOption("ensembleSize", 'e',
            "Ensemble size.", 15, 1, Integer.MAX_VALUE);

    public MultiChoiceOption pruningStrategyOption = new MultiChoiceOption(
            "pruningStrategy", 's', "Classifiers pruning strategy to be used.",
            new String[]{"NO", "AGE", "ERROR"}, new String[]{
                "Don't prune classifiers", "Age-based", "Error-based"}, 0);

    protected List<Classifier> ensemble;
    protected List<Double> ensembleWeights;
    protected List<ArrayList<Double>> bkts, wkts;
    protected Instances buffer;
    protected long index;
    
    public LearnNSE() {
		// TODO Auto-generated constructor stub
    	resetLearningImpl();
	}

    @Override
    public void resetLearningImpl() {
        this.ensemble = new ArrayList<>();
        this.ensembleWeights = new ArrayList<>();
        this.bkts = new ArrayList<>();
        this.wkts = new ArrayList<>();
        this.index = 0;
        this.buffer = null;
    }

    @Override
    public void trainOnInstanceImpl(Instance inst) {
        this.index++;
        // Store instance in the buffer
        if (this.buffer == null) {
            this.buffer = new Instances(inst.dataset());
        }
        this.buffer.add(inst);

        if (this.index % this.periodOption.getValue() == 0) {
            this.index = 0;
            double mt = this.buffer.numInstances();
            Classifier classifier = (Classifier) getPreparedClassOption(this.baseLearnerOption);
            classifier.resetLearning();
    		if(classifier instanceof kNN){
    			((kNN) classifier).kOption.setValue(kOption.getValue());
    		}

            if (this.ensemble.size() > 0) {
                double et = 0;
                // Reading all data chunk instances
                for (int i = 0; i < mt; i++) {
                    // Compute error of the existing ensemble on new data
                    boolean vote = this.correctlyClassifies(this.buffer
                            .instance(i));
                    if (!vote) {
                        et += 1.0 / mt;
                    }
                }
                // Normalizing error
                double weightSum = 0.0;
                // Reading all data chunk instances
                for (int i = 0; i < mt; i++) {
                    Instance instance = this.buffer.instance(i);
                    // Updating instance weights
                    boolean vote = this.correctlyClassifies(instance);
                    double error = (1.0 / mt) * (vote ? et : 1.0);
                    instance.setWeight(error);
                    weightSum += error;
                }
                // Reading all data chunk instances
                for (int i = 0; i < mt; i++) {
                    Instance instance = this.buffer.instance(i);
                    // Normalize weights
                    instance.setWeight(instance.weight() / weightSum);

                    // Call base classifier
                    Instance trainingInstance = (Instance) instance.copy();
                    trainingInstance.setWeight(1);
                    classifier.trainOnInstance(trainingInstance);
                }
            } else {
                // First run! Iterating through all instances in the data chunk
                for (int i = 0; i < mt; i++) {
                    Instance instance = this.buffer.instance(i);

                    // Initialize weights
                    instance.setWeight(1.0 / mt);

                    // Call base classifier
                    Instance trainingInstance = (Instance) instance.copy();
                    trainingInstance.setWeight(1);
                    classifier.trainOnInstance(trainingInstance);
                }
            }
            this.ensemble.add(classifier);
            this.bkts.add(new ArrayList<Double>());
            this.wkts.add(new ArrayList<Double>());
            this.ensembleWeights.clear();
            int t = this.ensemble.size();
            double maxError = Double.NEGATIVE_INFINITY;
            int errorIndex = Integer.MIN_VALUE;
            // Evaluate all existing classifiers on new data set
            for (int k = 1; k <= t; k++) {
                double ekt = 0;
                // Reading all data chunk instances
                for (int i = 0; i < mt; i++) {
                    Instance instance = this.buffer.instance(i);
                    if (!this.ensemble.get(k - 1).correctlyClassifies(instance)) {
                        // Ensemble incorrectly classifies this instance
                        ekt += instance.weight();
                    }
                }
                if (k == t && ekt > 0.5) {
                    // Generate a new classifier
                    Classifier c = (Classifier) getPreparedClassOption(this.baseLearnerOption);
                    c.resetLearning();
                    this.ensemble.set(k - 1, c);
                } else if (ekt > 0.5) {
                    // Remove voting power of this classifier
                    ekt = 0.5;
                }
				// Storing the index of the classifier with higher error in case
                // of error-based pruning
                if (ekt > maxError) {
                    maxError = ekt;
                    errorIndex = k;
                }
                // Normalizing errors
                double bkt = ekt / (1.0 - ekt);
                // Retrieving normalized errors for this classifier
                ArrayList<Double> nbkt = this.bkts.get(k - 1);
                nbkt.add(bkt);
				// Compute the weighted average of all normalized errors for kth
                // classifier h_k
                double wkt = 1.0 / (1.0 + Math.exp(-this.sigmoidSlopeOption.getValue()
                        * (t - k - this.sigmoidCrossingPointOption.getValue())));
                List<Double> weights = this.wkts.get(k - 1);
                double sum = 0;
                for (Double weight : weights) {
                    sum += weight;
                }
                weights.add(wkt / (sum + wkt));
                double sbkt = 0.0;
                for (int j = 0; j < weights.size(); j++) {
                    sbkt += weights.get(j) * nbkt.get(j);
                }
                // Calculate classifier voting weights
                this.ensembleWeights.add(Math.log(1.0 / sbkt));
            }
            // Ensemble pruning strategy				
            if (this.pruningStrategyOption.getChosenIndex() == 1 && 
            		t > this.ensembleSizeOption.getValue()) { // Age-based
                this.ensemble.remove(0);
                this.ensembleWeights.remove(0);
                this.bkts.remove(0);
                this.wkts.remove(0);
            } else if (this.pruningStrategyOption.getChosenIndex() == 2 && 
            		t > this.ensembleSizeOption.getValue()) { // Error-based
                this.ensemble.remove(errorIndex - 1);
                this.ensembleWeights.remove(errorIndex - 1);
                this.bkts.remove(errorIndex - 1);
                this.wkts.remove(errorIndex - 1);
            }
            this.buffer = new Instances(this.getModelContext());
            System.out.println("\nNum Cases in the new casebase: " + buffer.size());
        }
    }

    @Override
    public boolean isRandomizable() {
        return false;
    }

    @Override
    public double[] getVotesForInstance(Instance inst) {
        DoubleVector combinedVote = new DoubleVector();
        if (this.trainingWeightSeenByModel > 0.0) {
            for (int i = 0; i < this.ensemble.size(); i++) {
                if (this.ensembleWeights.get(i) > 0.0) {
                    DoubleVector vote = new DoubleVector(this.ensemble.get(i)
                            .getVotesForInstance(inst));
                    if (vote.sumOfValues() > 0.0) {
                        vote.normalize();
                        vote.scaleValues(this.ensembleWeights.get(i));
                        combinedVote.addValues(vote);
                    }
                }
            }
        }
        return combinedVote.getArrayRef();
    }

    @Override
    public void getModelDescription(StringBuilder out, int indent) {
    }

    @Override
    protected Measurement[] getModelMeasurementsImpl() {
        return null;
    }
}