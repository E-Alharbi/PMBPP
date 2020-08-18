package PMBPP.ML.Model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.FilenameUtils;

import PMBPP.Data.Preparation.ClassificationPreparerWithOptimizeClasses.SortedByIntKeys;
import PMBPP.Log.Log;
import PMBPP.Utilities.CSVReader;
import PMBPP.Utilities.CSVWriter;
import PMBPP.Utilities.FilesUtilities;
import PMBPP.Utilities.TxtFiles;
import weka.attributeSelection.ClassifierAttributeEval;
import weka.attributeSelection.Ranker;
import weka.attributeSelection.WrapperSubsetEval;
import weka.classifiers.Evaluation;
import weka.classifiers.trees.RandomForest;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.SelectedTag;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

/*
 * contains methods to train a model, split data for training and testing, and evaluation  
 */
public class MLModel {

	public RandomForest MLPredictor;
	private Instances train;

	public Instances getTrain() {
		return train;
	}

	public void setTrain(Instances train) {
		this.train = train;
	}

	public Instances getTest() {
		return test;
	}

	public void setTest(Instances test) {
		this.test = test;
	}

	private Instances test;
	private Random rand = new Random(1);
	private Instances dataset;
	private Instances Unfiltereddataset;

	public void ModelReset() {
		init();
	}

	public MLModel() {
		init();
	}

	void init() {
		MLPredictor = new RandomForest();
		MLPredictor.setNumIterations(Integer.parseInt(Parameters.getNumberOfTrees()));
		MLPredictor.setNumExecutionSlots(Runtime.getRuntime().availableProcessors());
	}

	void init(RandomForest model) {
		MLPredictor = model;
	}

	void LoadData(String DataPath, String ClassName) throws Exception {
		DataSource source = new DataSource(DataPath);

		dataset = source.getDataSet();
		dataset.setClassIndex(GetAttIndexByItName(dataset, ClassName));

		train = dataset;
		test = dataset;

		train.setClassIndex(GetAttIndexByItName(dataset, ClassName));

		Unfiltereddataset = new Instances(dataset);
	}

	int GetAttIndexByItName(Instances DatasetToGetAtt, String Label) {

		for (int i = 0; i < DatasetToGetAtt.numAttributes(); ++i) {
			if (DatasetToGetAtt.attribute(i).name().equals(Label)) {

				return i;
			}
		}

		return -1;
	}

	void Split(double Percentage, String PipelineAndLabel) throws Exception {

		if (dataset.attribute(dataset.classIndex()).numValues() == 1) { // this will cause "Cannot handle unary class"
																		// in Weka
			new Log().Error(this,
					"Can not create a predictive model with only one value in the class that want to predict. This might happen when you have a small dataset. ");
		}

		if (Parameters.getSplitOnStructureLevel().equals("F")) {
			dataset.randomize(rand);

			// https://stackoverflow.com/questions/14682057/java-weka-how-to-specify-split-percentage
			int trainSize = (int) Math.round(dataset.numInstances() * Percentage);
			int testSize = dataset.numInstances() - trainSize;
			train = new Instances(dataset, 0, trainSize);
			test = new Instances(dataset, trainSize, testSize);

		}

		if (Parameters.getSplitOnStructureLevel().equals("T"))
			SplitOnStructureLevel(Percentage);
		
		

		// SaveToCSV
		String WhereToSave = "TrainAndTestData" + Parameters.getModelFolderName();
		PMBPP.CheckDirAndFile(WhereToSave);
		new CSVWriter().WriteInstancesToCSV(train, WhereToSave + "/" + PipelineAndLabel + "-train");
		new CSVWriter().WriteInstancesToCSV(test, WhereToSave + "/" + PipelineAndLabel + "-test");
		new CSVWriter().WriteInstancesToCSV(dataset, WhereToSave + "/" + PipelineAndLabel + "-dataset");

	}

	void SplitOnStructureLevel(double Percentage) throws Exception {

		Vector<String> PDB = new Vector<String>();
		for (int i = 0; i < Unfiltereddataset.numInstances(); ++i) { // find unique PDB entities

			String PDBEntity = Unfiltereddataset.get(i).stringValue(GetAttIndexByItName(Unfiltereddataset, "PDB"))
					.substring(0, 4);
			if (!PDB.contains(PDBEntity))
				PDB.add(PDBEntity);

		}
		int trainSize = (int) Math.round(PDB.size() * Percentage); // now find the size

		Collections.shuffle(PDB); // randomize

		Vector<String> PDBtrain = new Vector<String>();
		Vector<String> PDBtest = new Vector<String>();
		PDBtrain.addAll(PDB.subList(0, trainSize)); // split unique PDB entities
		PDBtest.addAll(PDB.subList(trainSize, PDB.size()));
if(!new File("Train.list").exists()) {// if this list exists, then use the list of the PDB as in this list. This to avoid using different PDB by different pipelines models and begin not be able to analysis the results  
	
	new TxtFiles().WriteVectorToTxtFile("Train.list", PDBtrain);
	
	new TxtFiles().WriteVectorToTxtFile("Test.list", PDBtest);
}
else {
	PDBtrain.clear();
	PDBtest.clear();
	PDBtrain=new TxtFiles().ReadIntoVec("Train.list");
	PDBtest=new TxtFiles().ReadIntoVec("Test.list");
	
}
		
		Instances traindataset = FillIn(Unfiltereddataset, PDBtrain); // add all PDB original and synthetic that for
																		// same PDB
		Instances testdataset = FillIn(Unfiltereddataset, PDBtest);
		;

		Vector<String> AttributesToRemove = new Vector<String>();
		for (int a = 0; a < Unfiltereddataset.numAttributes(); ++a) { // Unfiltereddataset contains all attributes and
																		// we need to remove unwanted attributes to
																		// match the attributes in dataset
			boolean found = false;
			for (int d = 0; d < dataset.numAttributes(); ++d) {
				if (dataset.attribute(d).name().equals(Unfiltereddataset.attribute(a).name())) {
					found = true;
				}
			}
			if (found == false) {
				AttributesToRemove.add(Unfiltereddataset.attribute(a).name());

			}
		}

		Remove removeFilter = new Remove();
		removeFilter.setAttributeIndices(AttributeIndices(AttributesToRemove, Unfiltereddataset));
		removeFilter.setInputFormat(Unfiltereddataset);
		traindataset = Filter.useFilter(traindataset, removeFilter);
		testdataset = Filter.useFilter(testdataset, removeFilter);

		traindataset.setClassIndex(GetAttIndexByItName(traindataset, dataset.attribute(dataset.classIndex()).name()));
		testdataset.setClassIndex(GetAttIndexByItName(testdataset, dataset.attribute(dataset.classIndex()).name()));

		train = traindataset;
		test = testdataset;

	}

	Instances FillIn(Instances dataset, Vector<String> PDB) {

		Instances temp = new Instances(dataset);
		temp.clear();

		for (String pdb : PDB) {
			for (int i = 0; i < Unfiltereddataset.numInstances(); ++i) {
				String PDBEntity = Unfiltereddataset.get(i).stringValue(GetAttIndexByItName(Unfiltereddataset, "PDB"))
						.substring(0, 4);
				if (PDBEntity.equals(pdb)) {
					temp.add(Unfiltereddataset.get(i));
				}
			}
		}
		return temp;
	}

	void Train() throws Exception {

		MLPredictor.buildClassifier(train);

	}

	String AttributeIndices(Vector<String> Attributes, Instances DatasetToGetIndex) {
		String Indices = "";
		for (int i = 0; i < Attributes.size(); ++i) {
			if (i + 1 < Attributes.size())
				Indices += String.valueOf(GetAttIndexByItName(DatasetToGetIndex, Attributes.get(i)) + 1) + ","; // here
																												// we
																												// start
																												// from
																												// 1 for
																												// first
																												// Attribute
																												// not
																												// like
																												// when
																												// set
																												// class
																												// index
			else
				Indices += String.valueOf(GetAttIndexByItName(DatasetToGetIndex, Attributes.get(i)) + 1);
		}

		return Indices;
	}

	void RemoveAttribute(Vector<String> Attributes, String ClassName) throws Exception {

		Remove removeFilter = new Remove();
		removeFilter.setAttributeIndices(AttributeIndices(Attributes, train));
		removeFilter.setInputFormat(train);
		dataset = Filter.useFilter(dataset, removeFilter);
		dataset.setClassIndex(GetAttIndexByItName(dataset, ClassName));

	}

	Evaluation Evaluate() throws Exception {

		Evaluation evaluation = new Evaluation(train); // it is only need train to get the instance structure not values
		evaluation.evaluateModel(MLPredictor, test);

		return evaluation;

	}
//https://stackoverflow.com/questions/44177149/weka-serialized-model-file-too-big
	public void SaveModel(String Name, boolean SaveAtt) throws Exception {
		if (new File(Name + ".model").exists()) {
			Name=Name + Parameters.getNumberOfTrees()+ ".model";
		}
		else {
			Name=Name + ".model";
		}
		
		if(Parameters.getCompressModel().equals("F")) {
			weka.core.SerializationHelper.write(Name, MLPredictor);
			Name=Name.replaceAll(FilenameUtils.getExtension(Name), "");

		}
		
		
		if(Parameters.getCompressModel().equals("T")) {
		File f = new File(Name);
        FileOutputStream fileOutputStream = new FileOutputStream(f);
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fileOutputStream);
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(gzipOutputStream);
        objectOutputStream.writeObject(MLPredictor);
        objectOutputStream.flush();
        objectOutputStream.close();
        gzipOutputStream.close();
        fileOutputStream.close();
        Name=Name.replaceAll(FilenameUtils.getExtension(Name), "");
       
	}
		if(SaveAtt==true) {
			SaveAttributes(Name);
		}
		 
	}

	public void ReadModel(String Name) throws Exception {
		
		if (!Name.contains(".model"))
			Name = Name + ".model";
		
		
		if(Parameters.getPreloadedMLModels().containsKey(Name)) {
			MLPredictor = (RandomForest)Parameters.getPreloadedMLModels().get(Name);
			
			return;// stop here
		}
		if(new FilesUtilities().isGZipped(new File(Name)) ==false) {
				MLPredictor = (RandomForest) weka.core.SerializationHelper.read(Name);
		}
		
		if(new FilesUtilities().isGZipped(new File(Name)) ==true) {
		File f = new File(Name);
        FileInputStream fileInputStream = new FileInputStream(f);
        GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
        ObjectInputStream objectOutputStream = new ObjectInputStream(gzipInputStream);
        MLPredictor = (RandomForest) objectOutputStream.readObject();
        objectOutputStream.close();
        gzipInputStream.close();
        fileInputStream.close();
		}
	}

	
	void Normalize() throws Exception {
		int Class = dataset.classIndex();
		Normalize normalize = new Normalize();
		normalize.setInputFormat(dataset);
		Instances newdata = Filter.useFilter(dataset, normalize);
		dataset = newdata;
		dataset.setClassIndex(Class);

	}

	public String Predicte(double[] instanceValue1, String Att) throws Exception {
		
		DataSource source = new DataSource(Att);
		
		Instances Tempdataset = source.getDataSet();
		Tempdataset.clear();// we just want the attributes
		Tempdataset.setClassIndex(Tempdataset.numAttributes() - 1);
		Tempdataset.add(new DenseInstance(1.0, instanceValue1));
		
		String Prediction = Tempdataset.classAttribute()
				.value((int) MLPredictor.classifyInstance(Tempdataset.firstInstance()));
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_UP);
		
		if (Prediction.isEmpty()) {

			Prediction = df.format(BigDecimal.valueOf(MLPredictor.classifyInstance(Tempdataset.firstInstance())));
		}
		
		if (Prediction.contains("±")) {
			Parameters.setLog ( "F");// no need for the log here only tables are needed
			HashMap<String, Boolean> MeasurementUnitsToPredict = new CSVReader().FilterByFeatures(Parameters.getAttCSV(),
					false);
			Vector<String> Headers = new Vector<String>();
			Headers.add(String.valueOf(MeasurementUnitsToPredict.keySet().toArray()[0]));
			HashMap<String, Vector<HashMap<String, String>>> map = new CSVReader().ReadIntoHashMapWithFilterdHeaders(
					Parameters.getAttCSV(), String.valueOf(MeasurementUnitsToPredict.keySet().toArray()[0]), Headers);
			if (map.keySet().size() == 2) { // if it binary classification
				List<String> classes = new ArrayList<>(map.keySet());
				Collections.sort(classes, new SortedByIntKeys());

				if (Prediction.equals(classes.get(0)))
					Prediction = "High";
				if (Prediction.equals(classes.get(1)))
					Prediction = "Low";
			}
			Parameters.setLog ("T");
		}
		
		return Prediction;

	}

	double Accuracy(boolean Completeness) throws Exception {

		double WithinFiveDiff = 0;
		double diff = 0.05;
		if (Completeness == true)
			diff = 5;
		for (int i = 0; i < test.numInstances(); i++) {
			String trueClassLabel;
			trueClassLabel = test.instance(i).toString(test.classIndex());

			// Discreet prediction
			double predictionIndex = MLPredictor.classifyInstance(test.instance(i));

			if (predictionIndex >= Double.parseDouble(trueClassLabel) - diff
					&& predictionIndex <= Double.parseDouble(trueClassLabel) + diff)
				WithinFiveDiff++;

		}

		return (WithinFiveDiff * 100) / test.numInstances();

	}

	public void SaveAttributes(String Name) throws FileNotFoundException {

		String CSV = "";

		for (int i = 0; i < dataset.numAttributes(); ++i) {

			if (i + 1 < dataset.numAttributes())
				CSV += dataset.attribute(i).name() + ",";
			else
				CSV += dataset.attribute(i).name() + "\n";

		}

		for (int i = 0; i < dataset.attribute(train.classIndex()).numValues(); ++i) {
			for (int a = 0; a < dataset.numAttributes(); ++a) {
				if (a + 1 < dataset.numAttributes())
					CSV += "0,";
				else
					CSV += dataset.attribute(train.classIndex()).value(i) + "\n";
			}
		}

		if (dataset.attribute(train.classIndex()).numValues() == 0) {
			for (int i = 0; i < dataset.numAttributes(); ++i) {

				if (i + 1 < dataset.numAttributes())
					CSV += "0,";
				else
					CSV += "0\n";

			}
		}

		try (PrintWriter out = new PrintWriter(Name + "csv")) {
			out.println(CSV);
		}
	}

	Ranker RankAttributes() throws Exception {
		// We used the full dataset here as in weka GUI

		ClassifierAttributeEval evaluator = new ClassifierAttributeEval();
		evaluator.setLeaveOneAttributeOut(true);
		evaluator.setEvaluationMeasure(new SelectedTag(WrapperSubsetEval.EVAL_MAE, WrapperSubsetEval.TAGS_EVALUATION));

		evaluator.setClassifier(MLPredictor);

		evaluator.buildEvaluator(dataset);
		Ranker ranker = new Ranker();
		ranker.search(evaluator, dataset);

		// for (int i = 0; i < ranker.rankedAttributes().length; i++) {
		// System.out.println(
		// test.attribute((int)ranker.rankedAttributes()[i][0]).name() + " " +
		// ranker.rankedAttributes()[i][1] );
		// }
		return ranker;
	}

	
}
