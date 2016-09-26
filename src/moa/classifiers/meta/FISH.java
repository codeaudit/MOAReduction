package moa.classifiers.meta;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import moa.classifiers.AbstractClassifier;
import moa.classifiers.lazy.kNN;
import moa.core.Measurement;
import moa.options.ClassOption;
import weka.classifiers.Classifier;

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.yahoo.labs.samoa.instances.Instance;

/**
 * Learning in non-stationary environments.
 *
 * <p>
 * I. Žliobaite. Combining similarity in time and space for training set formation under concept drift, 
 * Intell. Data Anal. 15 (4) (2011) 589–611.
 * </p>
 *
 * @author Sergio Ramírez (sramirez at decsai dot ugr dot es)
 *
 */
@SuppressWarnings("serial")
// Version 2 of FISH family of algorithms.
public class FISH extends AbstractClassifier {

    public ClassOption baseLearnerOption = new ClassOption("baseLearner", 'l',
            "Classifier to train.", Classifier.class, "lazy.IBk");

    public IntOption periodOption = new IntOption("period", 'p',
            "Size of the environments.", 100, 100, Integer.MAX_VALUE);

    public FloatOption distancePropOption = new FloatOption(
            "distanceProportion",
            'd',
            "Distance proportion between space and time varibles.",
            0.5, 0, 1);
    public IntOption kOption = new IntOption("k", 'k',
            "Number of neighbors.", 3, 1, 50);

    protected Classifier testClassifier;
    protected List<STInstance> buffer;
    protected long index = 0;
    protected double distanceProp;
    protected int k, period;
  

    @Override
    public void resetLearningImpl() {
        this.index = 0;
        this.buffer = null;
        this.period = this.periodOption.getValue();
        this.distanceProp = this.distancePropOption.getValue();
        this.k = this.kOption.getValue();
        this.testClassifier = ((Classifier) getPreparedClassOption(this.baseLearnerOption));
		if(testClassifier instanceof kNN){
			((kNN) testClassifier).kOption.setValue(kOption.getValue());
		}
    }

    @Override
    public void trainOnInstanceImpl(Instance inst) {

        // Store instance in the buffer
        if (this.buffer == null) {
            this.buffer = new LinkedList<STInstance>();
        }

        weka.core.Instance winst = new weka.core.DenseInstance(inst.weight(), inst.toDoubleArray());
    	this.buffer.add(new STInstance(winst, index));
        if (this.index < this.periodOption.getValue()) { 
        	trainClassifier(testClassifier, buffer);
        }       
        
        this.index++;
    }
    
    private void trainClassifier(Classifier c, List<STInstance> instances) {
    	weka.core.Instances trai = new weka.core.Instances(instances.get(0).getInstance().dataset());
    	for(int z = 0; z < instances.size(); z++){
    		weka.core.Instance i = instances.get(z).getInstance();
    		i.setWeight(1);
    		trai.add(i);
    	}
		try {
			c.buildClassifier(trai);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
    private double euclideanDistance(weka.core.Instance i1, weka.core.Instance i2) throws Exception {
    	if(i1.numAttributes() != i2.numAttributes())
			throw new Exception("Euclidean distance performed on uneven instances");
		
		double accum = 0;
		for(int i = 0; i < i1.numAttributes(); i++) {
			if(i != i1.classIndex()) {
				accum += Math.pow(i1.value(i) - i2.value(i), 2);
			}
		}
		
		return Math.sqrt(accum);
    }

    @Override
    public boolean isRandomizable() {
        return false;
    }

    @Override
    public double[] getVotesForInstance(Instance inst) {
    	
    	weka.core.Instance winst = new weka.core.DenseInstance(inst.weight(), inst.toDoubleArray());
        
    	if (this.index >= this.periodOption.getValue()) {
        	// The specified size is gotten, now the online process is started
            Classifier classifier = ((Classifier) getPreparedClassOption(this.baseLearnerOption));
    		if(classifier instanceof kNN){
    			((kNN) classifier).kOption.setValue(kOption.getValue());
    		}
    		
            // Compute distances (space/time)
            float[] space = new float[buffer.size()];
            float[] time = new float[buffer.size()];
            float maxSpace = Float.MIN_VALUE;
            int idx = 0;
            for(STInstance sti : buffer) {            	
            	try {
					space[idx] = (float) euclideanDistance(sti.getInstance(), winst);
					time[idx] = this.index - sti.getTime();
					if(space[idx] > maxSpace)
						maxSpace = space[idx];
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            	idx++;
            }
            
            // Normalize distances
            for (int j = 0; j < time.length; j++) {
            	float distance = (float) ((1 - distanceProp) * space[j] / maxSpace + 
            			distanceProp * time[j] / this.index);
            	buffer.get(j).setDistance(distance);
			}
            
            // Sort instances by time/space distance (ascending)
            Collections.sort(buffer);
            
            List<Double> measurements = new LinkedList<Double>();
			// Apply leave-one-out cross validation (validation set formed by top-k nearest neighbors)
            for (int i = k; i < buffer.size(); i++){
            	try {
	            	List<STInstance> tra = buffer.subList(0, i);
					weka.core.Instances instancestr = new weka.core.Instances(tra.get(0).getInstance().dataset());
            		for(int z = 0; z < tra.size(); z++) {
            			weka.core.Instance instance = tra.get(z).getInstance();
            			instance.setWeight(1);
            			instancestr.add(instance);
            		}
            		
	            	double errors = 0;
	            	for(int j = 0; j < k ; j++) {
		            	// Validation instance is removed from the training set for this run
	            		weka.core.Instance removed = instancestr.remove(j);
	            		// Train for the tuple i, j
	            			
	            		classifier.buildClassifier(instancestr);
		            	
	                	// validate on instance j (from val set)
	            		int pred = (int) classifier.classifyInstance(removed);
	            		if (pred != removed.classValue()) 
							errors++;
	                	
	                	// We add the skipped instance again
	                	instancestr.add(j, removed);
	            	}
                	measurements.add(errors / k);
	            	
            	} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }           
            
            int min = 0;
            double minval = Double.MAX_VALUE;
            for(int i = 0; i < measurements.size(); i++) {
            	if(measurements.get(i) < minval) {
            		min = i;
            		minval = measurements.get(i);
            	}
            }
            List<STInstance> lastTrain = buffer.subList(0, min + k);
            //System.out.println("New size to train the classifier: " + lastTrain.size());
            trainClassifier(testClassifier, lastTrain);
    	}
    	
    	int pred = 0;
		try {
			if(index != 0)
				pred = (int) testClassifier.classifyInstance(winst);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	double[] v = new double[pred + 1];
    	v[pred] = 1.0;
    	//boolean good = pred == (int) inst.classValue();
    	//System.out.println("Good prediction?: " + good);
    	return v;
    }

    @Override
    public void getModelDescription(StringBuilder out, int indent) {
    }

    @Override
    protected Measurement[] getModelMeasurementsImpl() {
        return null;
    }
    
    class STInstance implements Comparable<STInstance>{
    	private weka.core.Instance instance;
    	private double distance;
    	private long time;
    	
    	public STInstance(weka.core.Instance instance, long time) {
			// TODO Auto-generated constructor stub
    		this.instance = instance;
    		this.time = time;
    		distance = Double.MIN_VALUE;
		}
    	
    	public double getDistance() {
			return distance;
		}
    	
    	public void setDistance(double distance) {
			this.distance = distance;
		}
    	
    	public weka.core.Instance getInstance() {
			return instance;
		}
    	
    	public void setInstance(weka.core.Instance instance) {
			this.instance = instance;
		}
    	
    	public long getTime() {
			return time;
		}
    	
    	public int compareTo(STInstance other){
	       // your logic here
		   if(this.distance < other.getDistance()){
		        return -1;
		    }else if(this.distance > other.getDistance()){
		        return 1;
		    }		   
		    return 0;
    	}
	 
    }
}