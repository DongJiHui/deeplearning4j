/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.multilayer;


import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.deeplearning4j.datasets.iterator.AsyncDataSetIterator;
import org.deeplearning4j.datasets.iterator.MultiDataSetWrapperIterator;
import org.deeplearning4j.eval.*;
import org.deeplearning4j.exception.DL4JException;
import org.deeplearning4j.exception.DL4JInvalidInputException;
import org.deeplearning4j.nn.api.*;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.activations.ActivationsSingle;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.api.layers.IOutputLayer;
import org.deeplearning4j.nn.api.layers.RecurrentLayer;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.BaseLayer;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.layers.FrozenLayer;
import org.deeplearning4j.nn.updater.UpdaterCreator;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.Solver;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.optimize.api.TrainingListener;
import org.deeplearning4j.optimize.solvers.accumulation.GradientsAccumulator;
import org.deeplearning4j.util.ModelSerializer;
import org.deeplearning4j.util.OneTimeLogger;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.heartbeat.Heartbeat;
import org.nd4j.linalg.heartbeat.reports.Environment;
import org.nd4j.linalg.heartbeat.reports.Event;
import org.nd4j.linalg.heartbeat.reports.Task;
import org.nd4j.linalg.heartbeat.utils.EnvironmentUtils;
import org.nd4j.linalg.heartbeat.utils.TaskUtils;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.memory.abstracts.DummyWorkspace;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.linalg.primitives.Triple;

import java.io.Serializable;
import java.util.*;

import static org.deeplearning4j.nn.graph.ComputationGraph.workspaceConfigurationCache;


/**
 * MultiLayerNetwork is a neural network with multiple layers in a stack, and usually an output layer.
 * For neural networks with a more complex connection architecture, use {@link org.deeplearning4j.nn.graph.ComputationGraph}
 * which allows for an arbitrary directed acyclic graph connection structure between layers.
 * MultiLayerNetwork is trainable via backprop, with optional pretraining, depending on the type of layers it contains.
 *
 * @author Adam Gibson
 */
@Slf4j
public class MultiLayerNetwork implements Serializable, Model, NeuralNetwork {

    //the hidden neural network layers (including output layer)
    protected Layer[] layers;
    protected LinkedHashMap<String, Layer> layerMap = new LinkedHashMap<>();

    //Current training data: input features and labels
    protected Activations input = new ActivationsSingle(null, null, null);        //Input activations, and mask

    protected INDArray labels;
    protected INDArray labelsMask;

    protected boolean initCalled = false;
    private Collection<IterationListener> listeners = new ArrayList<>();
    private Collection<TrainingListener> trainingListeners = new ArrayList<>();

    protected NeuralNetConfiguration defaultConfiguration;
    @Getter
    protected MultiLayerConfiguration layerWiseConfigurations;
    protected Gradient gradient;
    protected INDArray epsilon;
    protected double score;
    @Setter
    protected boolean initDone = false;
    protected INDArray flattenedParams; //Params for all layers are a view/subset of this array
    @Getter
    protected transient INDArray flattenedGradients; //Gradients for all layers are a view/subset of this array

    protected transient ThreadLocal<Long> lastEtlTime = new ThreadLocal<>();

    protected int layerIndex; //For Layer.get/setIndex()

    protected transient Solver solver; //Used to call optimizers during backprop

    protected final static String workspaceExternal = "LOOP_EXTERNAL";
    protected final static String workspaceFeedForward = "LOOP_FF";
    protected final static String workspaceBackProp = "LOOP_BP";
    public final static String workspaceTBPTT = "LOOP_TBPTT";

    protected final static WorkspaceConfiguration workspaceConfigurationExternal = WorkspaceConfiguration.builder()
                    .initialSize(0).overallocationLimit(0.3).policyLearning(LearningPolicy.FIRST_LOOP)
                    .policyReset(ResetPolicy.BLOCK_LEFT).policySpill(SpillPolicy.REALLOCATE)
                    .policyAllocation(AllocationPolicy.OVERALLOCATE).build();

    protected WorkspaceConfiguration workspaceConfigurationFeedForward = WorkspaceConfiguration.builder().initialSize(0)
                    .overallocationLimit(0.2).policyReset(ResetPolicy.BLOCK_LEFT)
                    .policyLearning(LearningPolicy.OVER_TIME).policySpill(SpillPolicy.REALLOCATE)
                    .policyAllocation(AllocationPolicy.OVERALLOCATE).build();

    protected final static WorkspaceConfiguration workspaceConfigurationTBPTT = WorkspaceConfiguration.builder()
                    .initialSize(0).overallocationLimit(0.2).policyReset(ResetPolicy.BLOCK_LEFT)
                    .policyAllocation(AllocationPolicy.OVERALLOCATE).policySpill(SpillPolicy.REALLOCATE)
                    .policyLearning(LearningPolicy.OVER_TIME).build();

    public MultiLayerNetwork(MultiLayerConfiguration conf) {
        this.layerWiseConfigurations = conf;
        this.defaultConfiguration = conf.getConf(0).clone();
    }

    @Override
    public int numInputs() {
        return 1;
    }

    @Override
    public int numOutputs() {
        return 1;
    }

    /**
     * This method sets specified CacheMode for all layers within network
     *
     * @param mode
     */
    public void setCacheMode(CacheMode mode) {
        if (mode == null)
            mode = CacheMode.NONE;

        for (Layer layer : layers) {
            layer.setCacheMode(mode);
        }
    }

    public void setLastEtlTime(long time) {
        lastEtlTime.set(time);
    }

    public long getLastEtlTime() {
        Long time = lastEtlTime.get();
        return time == null ? 0L : time;
    }

    /**
     * Initialize the network based on the configuration
     *
     * @param conf   the configuration json
     * @param params the parameters
     */
    public MultiLayerNetwork(String conf, INDArray params) {
        this(MultiLayerConfiguration.fromJson(conf));
        init();
        setParameters(params);
    }


    /**
     * Initialize the network based on the configuraiton
     *
     * @param conf   the configuration
     * @param params the parameters
     */
    public MultiLayerNetwork(MultiLayerConfiguration conf, INDArray params) {
        this(conf);
        init();
        setParameters(params);
    }


    protected void intializeConfigurations() {

        if (layerWiseConfigurations == null)
            layerWiseConfigurations = new MultiLayerConfiguration.Builder().build();

        if (layers == null)
            layers = new Layer[getnLayers()];

        if (defaultConfiguration == null)
            defaultConfiguration = new NeuralNetConfiguration.Builder().build();
    }


    /**
     * Perform layerwise pretraining on all pre-trainable layers in the network (VAEs, RBMs, Autoencoders, etc)<br>
     * Note that pretraining will be performed on one layer after the other, resetting the DataSetIterator between iterations.<br>
     * For multiple epochs per layer, appropriately wrap the iterator (for example, a MultipleEpochsIterator) or train
     * each layer manually using {@link #pretrainLayer(int, DataSetIterator)}
     *
     * @param iter Training data
     */
    public void pretrain(DataSetIterator iter) {
        if (flattenedGradients == null) {
            initGradientsView();
        }
        if (!layerWiseConfigurations.isPretrain())
            return;

        for (int i = 0; i < getnLayers(); i++) {
            pretrainLayer(i, iter);
        }
    }

    /**
     * Perform layerwise unsupervised training on a single pre-trainable layer in the network (VAEs, RBMs, Autoencoders, etc)<br>
     * If the specified layer index (0 to numLayers - 1) is not a pretrainable layer, this is a no-op.
     *
     * @param layerIdx Index of the layer to train (0 to numLayers-1)
     * @param iter Training data
     */
    public void pretrainLayer(int layerIdx, DataSetIterator iter) {
        if (flattenedGradients == null) {
            initGradientsView();
        }
        if (!layerWiseConfigurations.isPretrain())
            return;
        if (layerIdx >= layers.length) {
            throw new IllegalArgumentException(
                            "Cannot pretrain layer: layerIdx (" + layerIdx + ") >= numLayers (" + layers.length + ")");
        }

        Layer layer = layers[layerIdx];
        if (!layer.isPretrainLayer())
            return;

        if (!iter.hasNext() && iter.resetSupported()) {
            iter.reset();
        }

        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        ComputationGraph.workspaceConfigurationExternal,
                                                        ComputationGraph.workspaceExternal);
        MemoryWorkspace cache =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        ComputationGraph.workspaceConfigurationCache,
                                                        ComputationGraph.workspaceCache);

        log.info("Starting unsupervised training on layer " + layerIdx);
        while (iter.hasNext()) {
            DataSet next = iter.next();

            try (MemoryWorkspace wsCache = cache.notifyScopeEntered()) {
                try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                    MaskState ms = (next.getFeaturesMaskArray() == null ? null : MaskState.Active);
                    input = ActivationsFactory.getInstance().create(next.getFeatureMatrix(), next.getFeaturesMaskArray(), ms);
                    pretrainLayer(layerIdx, input);
                }
            }
        }

        int ec = getLayer(layerIdx).conf().getEpochCount() + 1;
        getLayer(layerIdx).conf().setEpochCount(ec);
    }

    public void pretrainLayer(int layerIdx, INDArray networkInput){
        pretrainLayer(layerIdx, ActivationsFactory.getInstance().create(networkInput));
    }

    /**
     * Perform layerwise unsupervised training on a single pre-trainable layer in the network (VAEs, RBMs, Autoencoders, etc)<br>
     * If the specified layer index (0 to numLayers - 1) is not a pretrainable layer, this is a no-op.
     *
     * @param layerIdx Index of the layer to train (0 to numLayers-1)
     * @param networkInput Training data array
     */
    public void pretrainLayer(int layerIdx, Activations networkInput) {
        if (flattenedGradients == null) {
            initGradientsView();
        }
        if (!layerWiseConfigurations.isPretrain())
            return;
        if (layerIdx >= layers.length) {
            throw new IllegalArgumentException(
                            "Cannot pretrain layer: layerIdx (" + layerIdx + ") >= numLayers (" + layers.length + ")");
        }

        Activations layerInput = networkInput;

        Layer layer = layers[layerIdx];
        if (!layer.isPretrainLayer() || !(layer instanceof Model))
            return;
        layer.conf().setPretrain(true);

        Model m = (Model)layer;

        MemoryWorkspace workspace = layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE
                        ? new DummyWorkspace()
                        : layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SINGLE
                                        ? Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceExternal)
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationFeedForward, workspaceFeedForward);

        MemoryWorkspace pretrain = layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE
                        ? new DummyWorkspace()
                        : layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SINGLE
                                        ? Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceExternal)
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationFeedForward,
                                                        ComputationGraph.workspacePretrain);

        try (MemoryWorkspace wsP = pretrain.notifyScopeEntered()) {
            //Do forward pass to the layer to be pretrained
            for (int j = 0; j < layerIdx; j++) {
                try (MemoryWorkspace wsFF = workspace.notifyScopeEntered()) {
                    layerInput = layers[j].activate(layerInput, true);
                    if (Nd4j.getWorkspaceManager().checkIfWorkspaceExists(ComputationGraph.workspacePretrain)) {
                        layerInput = layerInput.leverageTo(ComputationGraph.workspacePretrain);
                    }
                }
            }
            m.fit(layerInput);
        }

        // Turn off pretrain after it is complete
        layer.conf().setPretrain(false);
    }

    @Override
    public NeuralNetConfiguration conf() {
        return defaultConfiguration;
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {
        throw new UnsupportedOperationException();
    }


    public INDArray input() {
        return input == null ? null : input.get(0);
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return solver.getOptimizer();
    }

    @Override
    public INDArray getParam(String param) {
        //Get params for MultiLayerNetwork sub layers.
        //Parameter keys here: same as MultiLayerNetwork.backprop().
        int idx = param.indexOf('_');
        if (idx == -1)
            throw new IllegalStateException("Invalid param key: not have layer separator: \"" + param + "\"");
        int layerIdx = Integer.parseInt(param.substring(0, idx));
        String newKey = param.substring(idx + 1);

        return layers[layerIdx].getParam(newKey);
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return paramTable(false);
    }

    public Map<String, INDArray> paramTable(boolean backpropParamsOnly) {
        //Get all parameters from all layers
        Map<String, INDArray> allParams = new LinkedHashMap<>();
        for (int i = 0; i < layers.length; i++) {
            Map<String, INDArray> paramMap = layers[i].paramTable(backpropParamsOnly);
            for (Map.Entry<String, INDArray> entry : paramMap.entrySet()) {
                String newKey = i + "_" + entry.getKey();
                allParams.put(newKey, entry.getValue());
            }
        }
        return allParams;
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        Map<String, INDArray> currParamTable = paramTable();
        if (!currParamTable.keySet().equals(paramTable.keySet())) {
            throw new IllegalArgumentException("Cannot set param table: parameter keys do not match.\n" + "Current: "
                            + currParamTable.keySet() + "\nTo set: " + paramTable.keySet());
        }

        for (String s : paramTable.keySet()) {
            INDArray curr = currParamTable.get(s);
            INDArray toSet = paramTable.get(s);
            if (!Arrays.equals(curr.shape(), toSet.shape())) {
                throw new IllegalArgumentException("Cannot set parameter table: parameter \"" + s + "\" shapes "
                                + "do not match. Current = " + Arrays.toString(curr.shape()) + ", to set = "
                                + Arrays.toString(toSet.shape()));
            }
        }

        //Now that we've checked ALL params (to avoid leaving net in half-modified state)
        for (String s : paramTable.keySet()) {
            INDArray curr = currParamTable.get(s);
            INDArray toSet = paramTable.get(s);
            curr.assign(toSet);
        }
    }

    @Override
    public void setParam(String key, INDArray val) {
        //Set params for MultiLayerNetwork sub layers.
        //Parameter keys here: same as MultiLayerNetwork.backprop().
        int idx = key.indexOf('_');
        if (idx == -1)
            throw new IllegalStateException("Invalid param key: not have layer separator: \"" + key + "\"");
        int layerIdx = Integer.parseInt(key.substring(0, idx));
        String newKey = key.substring(idx + 1);

        layers[layerIdx].setParam(newKey, val);
    }

    /**
     * Initialize the MultiLayerNetwork. This should be called once before the network is used.
     */
    public void init() {
        init(null, false);
    }

    /**
     * Initialize the MultiLayerNetwork, optionally with an existing parameters array.
     * If an existing parameters array is specified, it will be used (and the values will not be modified) in the network;
     * if no parameters array is specified, parameters will be initialized randomly according to the network configuration.
     *
     * @param parameters              Network parameter. May be null. If null: randomly initialize.
     * @param cloneParametersArray    Whether the parameter array (if any) should be cloned, or used directly
     */
    public void init(INDArray parameters, boolean cloneParametersArray) {
        if (layerWiseConfigurations == null || layers == null)
            intializeConfigurations();
        if (initCalled)
            return;

        OneTimeLogger.info(log, "Starting MultiLayerNetwork with WorkspaceModes set to [training: {}; inference: {}]",
                        layerWiseConfigurations.getTrainingWorkspaceMode(),
                        layerWiseConfigurations.getInferenceWorkspaceMode());

        if (layerWiseConfigurations.getCacheMode() == CacheMode.HOST) {
            workspaceConfigurationCache.setPolicyMirroring(MirroringPolicy.HOST_ONLY);
        }

        int nLayers = getnLayers();

        if (nLayers < 1)
            throw new IllegalStateException("Unable to create network: number of layers is less than 1");

        if (this.layers == null || this.layers[0] == null) {
            if (this.layers == null)
                this.layers = new Layer[nLayers];

            //First: Work out total length of (backprop) params
            int paramLength = 0;
            int[] nParamsPerLayer = new int[nLayers];
            for (int i = 0; i < nLayers; i++) {
                NeuralNetConfiguration conf = layerWiseConfigurations.getConf(i);
                nParamsPerLayer[i] = conf.getLayer().initializer().numParams(conf);
                paramLength += nParamsPerLayer[i];
            }

            //Create parameters array, if required
            boolean initializeParams;
            if (parameters != null) {
                if (!parameters.isRowVector())
                    throw new IllegalArgumentException("Invalid parameters: should be a row vector");
                if (parameters.length() != paramLength)
                    throw new IllegalArgumentException("Invalid parameters: expected length " + paramLength
                                    + ", got length " + parameters.length());

                if (cloneParametersArray)
                    flattenedParams = parameters.dup();
                else
                    flattenedParams = parameters;

                initializeParams = false;
            } else {
                flattenedParams = Nd4j.create(1, paramLength);
                initializeParams = true;
            }

            //Set RNG seed, for repeatability between initializations when set
            if (initializeParams) {
                Nd4j.getRandom().setSeed(getDefaultConfiguration().getSeed());
            }

            // construct multi-layer
            int paramCountSoFar = 0;
            for (int i = 0; i < nLayers; i++) {
                INDArray paramsView;
                if (nParamsPerLayer[i] > 0) {
                    paramsView = flattenedParams.get(NDArrayIndex.point(0),
                                    NDArrayIndex.interval(paramCountSoFar, paramCountSoFar + nParamsPerLayer[i]));
                } else {
                    paramsView = null;
                }
                paramCountSoFar += nParamsPerLayer[i];

                NeuralNetConfiguration conf = layerWiseConfigurations.getConf(i);
                layers[i] = conf.getLayer().instantiate(conf, listeners, i, paramsView, initializeParams);
                layerMap.put(conf.getLayer().getLayerName(), layers[i]);
            }
            initCalled = true;
        }

        //Set parameters in MultiLayerNetwork.defaultConfiguration for later use in BaseOptimizer.setupSearchState() etc
        //Keyed as per backprop()
        defaultConfiguration.clearVariables();
        List<String> variables = defaultConfiguration.variables(false);
        for (int i = 0; i < layers.length; i++) {
            for (String s : layers[i].conf().variables()) {
                variables.add(i + "_" + s);
            }
        }

        // now we init solver & optimizer
        if (solver == null) {
            try (MemoryWorkspace wsO = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this).build();
                solver.initOptimizer();
            }
        }

        synchronizeIterEpochCounts();
    }

    /**
     * This method allows you to specificy GradientsAccumulator instance to be used with this model
     *
     * PLEASE NOTE: Do not use this method unless you understand how to use GradientsAccumulator & updates sharing.
     * PLEASE NOTE: Do not use this method on standalone model
     *
     * @param accumulator
     */
    public void setGradientsAccumulator(GradientsAccumulator accumulator) {
        if (!isInitCalled())
            init();

        solver.getOptimizer().setGradientsAccumulator(accumulator);
    }

    public boolean isInitCalled() {
        return initCalled;
    }

    /**
     * This method: initializes the flattened gradients array (used in backprop) and sets the appropriate subset in all layers.
     * As a general rule, this shouldn't ever need to be called manually when doing training via fit(DataSet) or fit(DataSetIterator)
     */
    public void initGradientsView() {
        try (MemoryWorkspace ws = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
            if (layers == null)
                init();

            int nLayers = layers.length;

            //First: Work out total length of params
            int paramLength = 0;
            int[] nParamsPerLayer = new int[nLayers];
            for (int i = 0; i < nLayers; i++) {
                NeuralNetConfiguration conf = layerWiseConfigurations.getConf(i);
                nParamsPerLayer[i] = conf.getLayer().initializer().numParams(conf);
                paramLength += nParamsPerLayer[i];
            }

            flattenedGradients = Nd4j.zeros(new int[] {1, paramLength}, 'f'); //No need to initialize, as each layer will do it each iteration anyway

            int backpropParamsSoFar = 0;
            for (int i = 0; i < layers.length; i++) {
                if (nParamsPerLayer[i] == 0)
                    continue; //This layer doesn't have any parameters...
                INDArray thisLayerGradView = flattenedGradients.get(NDArrayIndex.point(0),
                                NDArrayIndex.interval(backpropParamsSoFar, backpropParamsSoFar + nParamsPerLayer[i]));
                layers[i].setBackpropGradientsViewArray(thisLayerGradView);
                backpropParamsSoFar += nParamsPerLayer[i];
            }
        }
    }

    /**
     * Triggers the activation of the given layer
     *
     * @param layer the layer to trigger on
     * @param input the input to the hidden layer
     * @return the activation of the layer based on the input
     */
    public INDArray activate(int layer, INDArray input, boolean training) {
        return getLayer(layer).activate(ActivationsFactory.getInstance().create(input, null, null), training).get(0);
    }

    /**
     * Calculate activation for few layers at once. Suitable for autoencoder partial activation.
     *
     * In example: in 10-layer deep autoencoder, layers 0 - 4 inclusive are used for encoding part, and layers 5-9 inclusive are used for decoding part.
     *
     * @param from first layer to be activated, inclusive
     * @param to last layer to be activated, inclusive
     * @return the activation from the last layer
     */
    public INDArray activateSelectedLayers(int from, int to, INDArray input) {
        return activateSelectedLayers(from, to, ActivationsFactory.getInstance().create(input)).get(0);
    }

    public Activations activateSelectedLayers(int from, int to, Activations input) {
        if (input == null)
            throw new IllegalStateException("Unable to perform activation; no input found");
        if (from < 0 || from >= layers.length || from >= to)
            throw new IllegalStateException("Unable to perform activation; FROM is out of layer space");
        if (to < 1 || to >= layers.length)
            throw new IllegalStateException("Unable to perform activation; TO is out of layer space");

        Activations out = input;
        for (int l = from; l <= to; l++) {
            out = layers[l].activate(out, false);
        }
        return out;
    }

    /**
     * Compute activations from input to output of the output layer
     *
     * @return the list of activations for each layer
     */
    public List<INDArray> feedForward(INDArray input, boolean train) {
        setInput(input);
        return feedForward(train);
    }

    /**
     * Compute activations from input to output of the output layer
     *
     * @return the list of activations for each layer
     */
    public List<INDArray> feedForward(boolean train) {
        return feedForwardToLayer(layers.length - 1, train);
    }

    /** Compute the activations from the input to the specified layer.<br>
     * To compute activations for all layers, use feedForward(...) methods<br>
     * Note: output list includes the original input. So list.get(0) is always the original input, and
     * list.get(i+1) is the activations of the ith layer.
     * @param layerNum Index of the last layer to calculate activations for. Layers are zero-indexed.
     *                 feedForwardToLayer(i,input) will return the activations for layers 0..i (inclusive)
     * @param input Input to the network
     * @return list of activations.
     */
    public List<INDArray> feedForwardToLayer(int layerNum, INDArray input) {
        return feedForwardToLayer(layerNum, input, false);
    }

    /** Compute the activations from the input to the specified layer.<br>
     * To compute activations for all layers, use feedForward(...) methods<br>
     * Note: output list includes the original input. So list.get(0) is always the original input, and
     * list.get(i+1) is the activations of the ith layer.
     * @param layerNum Index of the last layer to calculate activations for. Layers are zero-indexed.
     *                 feedForwardToLayer(i,input) will return the activations for layers 0..i (inclusive)
     * @param input Input to the network
     * @param train true for training, false for test (i.e., false if using network after training)
     * @return list of activations.
     */
    public List<INDArray> feedForwardToLayer(int layerNum, INDArray input, boolean train) {
        setInput(input);
        return feedForwardToLayer(layerNum, train);
    }

    /** Compute the activations from the input to the specified layer, using the currently set input for the network.<br>
     * To compute activations for all layers, use feedForward(...) methods<br>
     * Note: output list includes the original input. So list.get(0) is always the original input, and
     * list.get(i+1) is the activations of the ith layer.
     * @param layerNum Index of the last layer to calculate activations for. Layers are zero-indexed.
     *                 feedForwardToLayer(i,input) will return the activations for layers 0..i (inclusive)
     * @param train true for training, false for test (i.e., false if using network after training)
     * @return list of activations.
     */
    public List<INDArray> feedForwardToLayer(int layerNum, boolean train) {
        List<Activations> temp = feedForwardToLayer(input, layerNum, train);
        List<INDArray> out = new ArrayList<>(temp.size());
        for(Activations a : temp){
            out.add(a.get(0));
            ActivationsFactory.getInstance().release(a);
        }
        return out;
    }

    public List<Activations> feedForwardToLayer(Activations input, int layerNum, boolean train){
        //TODO this next call should eventually be removed (after redesign etc)
        for(Layer l : layers){
            l.setInputMiniBatchSize(input.get(0).size(0));
        }

        // TODO: maybe remove that?
        Activations currInput = layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? input : input.migrate();
        List<Activations> activations = new ArrayList<>();
        activations.add(currInput);

        currInput = currInput.cloneShallow();   //To avoid layers modifying the original activations (dropout, etc)

        MemoryWorkspace workspace = layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE
                        ? new DummyWorkspace()
                        : layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SINGLE
                                        ? Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceExternal)
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationFeedForward, workspaceFeedForward);

        for (int i = 0; i <= layerNum; i++) {
            // log.info("Activating layer: {}", i);
            try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                currInput = layers[i].activate(currInput, train).leverageTo(workspaceExternal);
                activations.add(currInput);
            }
        }

        if (!train)
            if (layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SEPARATE)
                Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceFeedForward).initializeWorkspace();

        return activations;
    }

    /**
     * Compute activations from input to output of the output layer
     *
     * @return the list of activations for each layer
     */
    public List<INDArray> feedForward() {
        return feedForward(false);
    }

    /**
     * Compute activations from input to output of the output layer
     *
     * @return the list of activations for each layer
     */
    public List<INDArray> feedForward(INDArray input) {
        return feedForward(input, null, null);
    }

    /** Compute the activations from the input to the output layer, given mask arrays (that may be null)
     * The masking arrays are used in situations such an one-to-many and many-to-one rucerrent neural network (RNN)
     * designs, as well as for supporting time series of varying lengths within the same minibatch for RNNs.
     */
    public List<INDArray> feedForward(INDArray input, INDArray featuresMask, INDArray labelsMask) {
        if (input == null)
            throw new IllegalStateException("Unable to perform feed forward with null input");
        MaskState ms = (featuresMask == null ? null : MaskState.Active);
        List<Activations> list = feedForward(ActivationsFactory.getInstance().create(input, featuresMask, ms), false);
        List<INDArray> out = new ArrayList<>();
        for(Activations a : list){
            out.add(a.get(0));
            ActivationsFactory.getInstance().release(a);
        }
        return out;
    }


    public List<Activations> feedForward(Activations input, boolean train){
        return feedForwardToLayer(input, layers.length-1, train);
    }


    @Override
    public Gradient gradient() {
        return gradient;
    }

    public INDArray epsilon() {
        return epsilon;
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(), score());
    }


    /**
     * Clones the multilayernetwork
     * @return
     */
    @Override
    public MultiLayerNetwork clone() {
        MultiLayerConfiguration conf = this.layerWiseConfigurations.clone();
        MultiLayerNetwork ret = new MultiLayerNetwork(conf);
        ret.init(this.params().dup(), false);

        if (solver != null) {
            //If  solver is null: updater hasn't been initialized -> getUpdater call will force initialization, however
            Updater u = this.getUpdater();
            INDArray updaterState = u.getStateViewArray();
            if (updaterState != null) {
                ret.getUpdater().setStateViewArray(ret, updaterState.dup(), false);
            }
        }

        if (hasAFrozenLayer()) {
            //correct layers to frozen layers
            Layer[] clonedLayers = ret.getLayers();
            for (int i = 0; i < layers.length; i++) {
                if (layers[i] instanceof FrozenLayer) {
                    clonedLayers[i] = new FrozenLayer(ret.getLayer(i));
                }
            }
            ret.setLayers(clonedLayers);
        }
        return ret;
    }

    private boolean hasAFrozenLayer() {
        for (int i = 0; i < layers.length - 1; i++) {
            if (layers[i] instanceof FrozenLayer)
                return true;
        }
        return false;
    }


    /**
     * Returns a 1 x m vector where the vector is composed of
     * a flattened vector of all of the weights for the
     * various neuralNets(w,hbias NOT VBIAS) and output layer
     *
     * @return the params for this neural net
     */
    public INDArray params(boolean backwardOnly) {
        if (backwardOnly)
            return params();

        List<INDArray> params = new ArrayList<>();
        for (Layer layer : getLayers()) {
            INDArray layerParams = layer.params();
            if (layerParams != null)
                params.add(layerParams); //may be null: subsampling etc layers
        }

        return Nd4j.toFlattened('f', params);
    }


    /**
     * Returns a 1 x m vector where the vector is composed of
     * a flattened vector of all of the weights for the
     * various neuralNets(w,hbias NOT VBIAS) and output layer
     *
     * @return the params for this neural net
     */
    @Override
    public INDArray params() {
        return flattenedParams;
    }

    /**
     * Set the parameters for this model.
     * This expects a linear ndarray
     * which then be unpacked internally
     * relative to the expected ordering of the model
     *
     * @param params the parameters for the model
     */
    @Override
    public void setParams(INDArray params) {
        if (flattenedParams == params) {
            return; //No op
        }

        if (flattenedParams != null && params.length() == flattenedParams.length()) {
            if (params != flattenedParams) {
                flattenedParams.assign(params);
            }
        } else {
            if (flattenedParams == null)
                flattenedParams = params.dup();
            int idx = 0;
            for (int i = 0; i < getLayers().length; i++) {
                Layer layer = getLayer(i);
                int range = layer.numParams();
                if (range <= 0)
                    continue; //Some layers: no parameters (subsampling, etc)
                INDArray get = params.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, range + idx));
                layer.setParams(get);
                idx += range;
            }
        }
    }

    @Override
    public void setParamsViewArray(INDArray params) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public INDArray getGradientsViewArray() {
        return flattenedGradients;
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        int paramsSoFar = 0;
        for (Layer layer : layers) {
            if (layer.numParams() == 0)
                continue;
            layer.setBackpropGradientsViewArray(gradients.get(NDArrayIndex.point(0),
                            NDArrayIndex.interval(paramsSoFar, paramsSoFar + layer.numParams())));
            paramsSoFar += layer.numParams();
        }
    }

    /**
     * Returns a 1 x m vector where the vector is composed of
     * a flattened vector of all of the weights for the
     * various neuralNets and output layer
     *
     * @return the params for this neural net
     */
    @Override
    public int numParams() {
        if (isInitCalled())
            return numParams(false);
        else
            log.info("Model is not initialized. Initialize net with init()");
        return 0;
    }

    @Override
    public int numParams(boolean backwards) {
        int length = 0;
        for (int i = 0; i < layers.length; i++)
            length += layers[i].numParams(backwards);

        return length;
    }

    @Override
    public void fit(DataSetIterator iterator) {
        // we're wrapping all iterators into AsyncDataSetIterator to provide background prefetch - where appropriate
        DataSetIterator iter;
        boolean destructable = false;
        if (iterator.asyncSupported()) {
            iter = new AsyncDataSetIterator(iterator, Math.min(Nd4j.getAffinityManager().getNumberOfDevices() * 2, 2),
                            layerWiseConfigurations.getTrainingWorkspaceMode() != WorkspaceMode.NONE);
            destructable = true;
        } else {
            iter = iterator;
        }

        for (TrainingListener tl : trainingListeners) {
            tl.onEpochStart(this);
        }

        if (layerWiseConfigurations.isPretrain()) {
            pretrain(iter);
            if (iter.resetSupported()) {
                iter.reset();
            }
        }


        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);
        MemoryWorkspace cache =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        ComputationGraph.workspaceConfigurationCache,
                                                        ComputationGraph.workspaceCache);

        if (layerWiseConfigurations.isBackprop()) {
            update(TaskUtils.buildTask(iter));
            if (!iter.hasNext() && iter.resetSupported()) {
                iter.reset();
            }
            long time1 = System.currentTimeMillis();
            while (iter.hasNext()) {

                DataSet next = iter.next();
                long time2 = System.currentTimeMillis();

                lastEtlTime.set((time2 - time1));

                if (next.getFeatureMatrix() == null || next.getLabels() == null)
                    break;

                // TODO: basically we want to wrap internals of this loop into workspace


                boolean hasMaskArrays = next.hasMaskArrays();

                if (layerWiseConfigurations.getBackpropType() == BackpropType.TruncatedBPTT) {
                    doTruncatedBPTT(next.getFeatureMatrix(), next.getLabels(), next.getFeaturesMaskArray(),
                                    next.getLabelsMaskArray());
                } else {

                    setInput(next.getFeatures());
                    input.setMask(0, next.getFeaturesMaskArray());
                    if(next.getFeaturesMaskArray() != null){
                        input.setMaskState(0, MaskState.Active);
                    } else {
                        input.setMaskState(0, null);
                    }
                    setLabels(next.getLabels());

                    if (solver == null) {
                        try (MemoryWorkspace wsO = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                            solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this)
                                            .build();
                        }
                    }

                    try (MemoryWorkspace wsCache = cache.notifyScopeEntered()) {
                        try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                            solver.optimize();
                        }
                    }
                }

                clear();

                time1 = System.currentTimeMillis();
            }
        } else if (layerWiseConfigurations.isPretrain()) {
            log.warn("Warning: finetune is not applied.");
        }

        if (trainingListeners.size() > 0) {
            for (TrainingListener tl : trainingListeners) {
                tl.onEpochEnd(this);
            }
        }

        clearLayersStates();

        if (destructable)
            ((AsyncDataSetIterator) iter).shutdown();

        incrementEpochCount();
    }

    /** Calculate and set gradients for MultiLayerNetwork, based on OutputLayer and labels*/
    protected void backprop() {
        Gradients pair = calcBackpropGradients(null, true);
        this.gradient = (pair == null ? null : pair.getParameterGradients());
        this.epsilon = (pair == null ? null : pair.get(0));
    }

    /** Calculate gradients and errors. Used in two places:
     * (a) backprop (for standard multi layer network learning)
     * (b) backpropGradient (layer method, for when MultiLayerNetwork is used as a layer)
     * @param epsilon Errors (technically errors .* activations). Not used if withOutputLayer = true
     * @param withOutputLayer if true: assume last layer is output layer, and calculate errors based on labels. In this
     *                        case, the epsilon input is not used (may/should be null).
     *                        If false: calculate backprop gradients
     * @return Gradients and the error (epsilon) at the input
     */
    protected Gradients calcBackpropGradients(INDArray epsilon, boolean withOutputLayer) {
        if (flattenedGradients == null) {
            initGradientsView();
        }
        String multiGradientKey;
        Gradient gradient = new DefaultGradient(flattenedGradients);
        Layer currLayer;



        //calculate and apply the backward gradient for every layer
        /**
         * Skip the output layer for the indexing and just loop backwards updating the coefficients for each layer.
         * (when withOutputLayer == true)
         *
         * Activate applies the activation function for each layer and sets that as the input for the following layer.
         *
         * Typical literature contains most trivial case for the error calculation: wT * weights
         * This interpretation transpose a few things to get mini batch because ND4J is rows vs columns organization for params
         */
        int numLayers = getnLayers();
        //Store gradients is a list; used to ensure iteration order in DefaultGradient linked hash map. i.e., layer 0 first instead of output layer
        LinkedList<Triple<String, INDArray, Character>> gradientList = new LinkedList<>();

        int layerFrom;
        Gradients currGrad;
        if (withOutputLayer) {
            if (!(getOutputLayer() instanceof IOutputLayer)) {
                log.warn("Warning: final layer isn't output layer. You cannot use backprop without an output layer.");
                return null;
            }

            IOutputLayer outputLayer = (IOutputLayer) getOutputLayer();
            if (labels == null)
                throw new IllegalStateException("No labels found");
            outputLayer.setLabels(labels, labelsMask);
            currGrad = outputLayer.backpropGradient(null);

            for (Map.Entry<String, INDArray> entry : currGrad.getParameterGradients().gradientForVariable().entrySet()) {
                String origName = entry.getKey();
                multiGradientKey = String.valueOf(numLayers - 1) + "_" + origName;
                gradientList.addLast(new Triple<>(multiGradientKey, entry.getValue(),
                                currGrad.getParameterGradients().flatteningOrderForVariable(origName)));
            }

            layerFrom = numLayers - 2;
        } else {
            currGrad = GradientsFactory.getInstance().create(epsilon, null);
            layerFrom = numLayers - 1;
        }

        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SINGLE
                                                        ? Nd4j.getWorkspaceManager()
                                                                        .getWorkspaceForCurrentThread(workspaceExternal)
                                                        //: Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(wsConf, workspaceBackProp);
                                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                                        workspaceConfigurationFeedForward,
                                                                        workspaceFeedForward);

        // Calculate gradients for previous layers & drops output layer in count
        for (int j = layerFrom; j >= 0; j--) {
            try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                currLayer = getLayer(j);
                if (currLayer instanceof FrozenLayer) {
                    break;
                }
                currGrad = currLayer.backpropGradient(currGrad);
                currGrad.leverageActGradsToWorkspace(workspaceExternal);

                LinkedList<Triple<String, INDArray, Character>> tempList = new LinkedList<>();
                for (Map.Entry<String, INDArray> entry : currGrad.getParameterGradients().gradientForVariable().entrySet()) {
                    String origName = entry.getKey();
                    multiGradientKey = String.valueOf(j) + "_" + origName;
                    tempList.addFirst(new Triple<>(multiGradientKey, entry.getValue(),
                                    currGrad.getParameterGradients().flatteningOrderForVariable(origName)));
                }
                for (Triple<String, INDArray, Character> triple : tempList)
                    gradientList.addFirst(triple);

                //log.info("This layer space: {}", ((Nd4jWorkspace) ws).getThisCycleAllocations());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.SEPARATE) {
            Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceFeedForward).initializeWorkspace();
        }

        //Add gradients to Gradients (map), in correct order
        for (Triple<String, INDArray, Character> triple : gradientList) {
            gradient.setGradientFor(triple.getFirst(), triple.getSecond(), triple.getThird());
        }

//        return new Pair<>(gradient, currPair.getSecond());
        return GradientsFactory.getInstance().create(gradient, currGrad.getActivationGradAsArray());
    }

    protected void doTruncatedBPTT(INDArray input, INDArray labels, INDArray featuresMaskArray,
                    INDArray labelsMaskArray) {
        if (input.rank() != 3 || labels.rank() != 3) {
            log.warn("Cannot do truncated BPTT with non-3d inputs or labels. Expect input with shape [miniBatchSize,nIn,timeSeriesLength], got "
                            + Arrays.toString(input.shape()) + "\tand labels with shape "
                            + Arrays.toString(labels.shape()));
            return;
        }
        if (input.size(2) != labels.size(2)) {
            log.warn("Input and label time series have different lengths: {} input length, {} label length",
                            input.size(2), labels.size(2));
            return;
        }

        int fwdLen = layerWiseConfigurations.getTbpttFwdLength();
        update(TaskUtils.buildTask(input, labels));
        int timeSeriesLength = input.size(2);
        int nSubsets = timeSeriesLength / fwdLen;
        if (timeSeriesLength % fwdLen != 0)
            nSubsets++; //Example: 100 fwdLen with timeSeriesLength=100 -> want 2 subsets (1 of size 100, 1 of size 20)

        rnnClearPreviousState();

        workspaceConfigurationExternal.setCyclesBeforeInitialization(0);
        workspaceConfigurationExternal.setPolicyLearning(LearningPolicy.OVER_TIME);

        MemoryWorkspace workspaceT =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationTBPTT, workspaceTBPTT);
        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);

        try (MemoryWorkspace wsT = workspaceT.notifyScopeEntered()) {
            for (int i = 0; i < nSubsets; i++) {
                try (MemoryWorkspace wsE = workspace.notifyScopeEntered()) {
                    int startTimeIdx = i * fwdLen;
                    int endTimeIdx = startTimeIdx + fwdLen;
                    if (endTimeIdx > timeSeriesLength)
                        endTimeIdx = timeSeriesLength;

                    INDArray inputSubset = input.get(NDArrayIndex.all(), NDArrayIndex.all(),
                                    NDArrayIndex.interval(startTimeIdx, endTimeIdx));
                    INDArray labelSubset = labels.get(NDArrayIndex.all(), NDArrayIndex.all(),
                                    NDArrayIndex.interval(startTimeIdx, endTimeIdx));

                    setInput(inputSubset);
                    setLabels(labelSubset);

                    INDArray featuresMaskSubset = null;
                    INDArray labelsMaskSubset = null;
                    if (featuresMaskArray != null) {
                        featuresMaskSubset = featuresMaskArray.get(NDArrayIndex.all(),
                                        NDArrayIndex.interval(startTimeIdx, endTimeIdx));
                    }
                    if (labelsMaskArray != null) {
                        labelsMaskSubset = labelsMaskArray.get(NDArrayIndex.all(),
                                        NDArrayIndex.interval(startTimeIdx, endTimeIdx));
                    }
                    if (featuresMaskSubset != null ) {
                        this.input.setMask(0, featuresMaskSubset);
                        this.input.setMaskState(0, MaskState.Active);
                    }

                    IOutputLayer outputLayer = (IOutputLayer)getOutputLayer();
                    outputLayer.setLabels(labelSubset, labelsMaskSubset);


                    if (solver == null) {
                        try (MemoryWorkspace wsO = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                            solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this)
                                            .build();
                        }
                    }
                    solver.optimize();

                    //Finally, update the state of the RNN layers:
                    updateRnnStateWithTBPTTState();
                }
            }
        }

        if (layerWiseConfigurations.getTrainingWorkspaceMode() != WorkspaceMode.NONE) {
            workspace.initializeWorkspace();
            workspaceT.initializeWorkspace();
        }

        rnnClearPreviousState();
        clear();
    }

    public void updateRnnStateWithTBPTTState() {
        for (int i = 0; i < layers.length; i++) {
            if (layers[i] instanceof RecurrentLayer) {
                RecurrentLayer l = ((RecurrentLayer) layers[i]);
                l.rnnSetPreviousState(l.rnnGetTBPTTState());
            } else if (layers[i] instanceof MultiLayerNetwork) {
                ((MultiLayerNetwork) layers[i]).updateRnnStateWithTBPTTState();
            }
        }
    }

    /** Equivalent to backprop(), but calculates gradient for truncated BPTT instead. */
    protected void truncatedBPTTGradient() {
        synchronizeIterEpochCounts();
        if (flattenedGradients == null) {
            initGradientsView();
        }
        String multiGradientKey;
        gradient = new DefaultGradient(flattenedGradients);
        Layer currLayer;

        if (!(getOutputLayer() instanceof IOutputLayer)) {
            log.warn("Warning: final layer isn't output layer. You cannot use backprop (truncated BPTT) without an output layer.");
            return;
        }

        IOutputLayer outputLayer = (IOutputLayer) getOutputLayer();
        if (labels == null)
            throw new IllegalStateException("No labels found");
        if (outputLayer instanceof BaseLayer
                        && ((BaseLayer) outputLayer.conf().getLayer()).getWeightInit() == WeightInit.ZERO) {
            throw new IllegalStateException("Output layer weights cannot be initialized to zero when using backprop.");
        }

        outputLayer.setLabels(labels, labelsMask);

        //calculate and apply the backward gradient for every layer
        int numLayers = getnLayers();
        //Store gradients is a list; used to ensure iteration order in DefaultGradient linked hash map. i.e., layer 0 first instead of output layer
        LinkedList<Pair<String, INDArray>> gradientList = new LinkedList<>();

        Gradients currPair = outputLayer.backpropGradient(null);

        for (Map.Entry<String, INDArray> entry : currPair.getParameterGradients().gradientForVariable().entrySet()) {
            multiGradientKey = String.valueOf(numLayers - 1) + "_" + entry.getKey();
            gradientList.addLast(new Pair<>(multiGradientKey, entry.getValue()));
        }

        // Calculate gradients for previous layers & drops output layer in count
        for (int j = numLayers - 2; j >= 0; j--) {
            currLayer = getLayer(j);
            if (currLayer instanceof RecurrentLayer) {


//                currPair = ((RecurrentLayer) currLayer).tbpttBackpropGradient(currPair.getSecond(),
//                                layerWiseConfigurations.getTbpttBackLength());
                currPair = ((RecurrentLayer) currLayer).tbpttBackpropGradient(currPair,
                        layerWiseConfigurations.getTbpttBackLength());
            } else {
                currPair = currLayer.backpropGradient(currPair);
            }

            LinkedList<Pair<String, INDArray>> tempList = new LinkedList<>();
            for (Map.Entry<String, INDArray> entry : currPair.getParameterGradients().gradientForVariable().entrySet()) {
                multiGradientKey = String.valueOf(j) + "_" + entry.getKey();
                tempList.addFirst(new Pair<>(multiGradientKey, entry.getValue()));
            }

            for (Pair<String, INDArray> pair : tempList)
                gradientList.addFirst(pair);
        }

        //Add gradients to Gradients, in correct order
        for (Pair<String, INDArray> pair : gradientList)
            gradient.setGradientFor(pair.getFirst(), pair.getSecond());
    }


    /**
     *
     * @return
     */
    public Collection<IterationListener> getListeners() {
        return listeners;
    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        this.listeners = listeners;

        if (layers == null) {
            init();
        }
        for (Layer layer : layers) {
            if(layer instanceof Model){
                ((Model)layer).setListeners(listeners);
            }
        }

        if (solver != null) {
            solver.setListeners(listeners);
        }

        this.trainingListeners.clear();
        if (listeners != null) {
            for (IterationListener il : listeners) {
                if (il instanceof TrainingListener) {
                    this.trainingListeners.add((TrainingListener) il);
                }
            }
        }
    }

    /**
     * This method ADDS additional IterationListener to existing listeners
     *
     * @param listeners
     */
    @Override
    public void addListeners(IterationListener... listeners) {
        if (this.listeners == null) {
            setListeners(listeners);
            return;
        }

        for (IterationListener listener : listeners) {
            this.listeners.add(listener);
            if (listener instanceof TrainingListener) {
                this.trainingListeners.add((TrainingListener) listener);
            }
        }

        if (solver != null) {
            solver.setListeners(this.listeners);
        }
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        Collection<IterationListener> cListeners = new ArrayList<>();
        //Check: user might have done setListeners(null) thinking this would clear the current listeners.
        //This results in an IterationListener[1] with a single null value -> results in a NPE later
        if (listeners != null && listeners.length > 0) {
            for (IterationListener i : listeners) {
                if (i != null)
                    cListeners.add(i);
            }
        }
        setListeners(cListeners);
    }

    /**
     * Fit the model
     *
     * @param data   the examples to classify (one example in each row)
     * @param labels the example labels(a binary outcome matrix)
     */
    @Override
    @Deprecated
    public void fit(INDArray data, INDArray labels) {
        fit(data, labels, null, null);
    }

    /**
     * Fit the model
     *
     * @param features   the examples to classify (one example in each row)
     * @param labels the example labels(a binary outcome matrix)
     * @param featuresMask The mask array for the features (used for variable length time series, etc). May be null.
     * @param labelsMask The mask array for the labels (used for variable length time series, etc). May be null.
     */
    public void fit(INDArray features, INDArray labels, INDArray featuresMask, INDArray labelsMask) {

        setInput(features);
        setLabels(labels);
        if (featuresMask != null || labelsMask != null) {
            this.input = ActivationsFactory.getInstance().create(features, featuresMask);
            this.labels = labels;
            this.labelsMask = labelsMask;
        }
        update(TaskUtils.buildTask(features, labels));

        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);

        MemoryWorkspace cache =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        ComputationGraph.workspaceConfigurationCache,
                                                        ComputationGraph.workspaceCache);

        if (layerWiseConfigurations.isBackprop()) {
            if (layerWiseConfigurations.getBackpropType() == BackpropType.TruncatedBPTT) {
                doTruncatedBPTT(features, labels, featuresMask, labelsMask);
            } else {
                if (solver == null) {
                    try (MemoryWorkspace wsO = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                        solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this).build();
                    }
                }

                try (MemoryWorkspace wsCache = cache.notifyScopeEntered()) {
                    try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                        solver.optimize();
                    }
                }
            }
        }

        clearLayersStates();
    }

    @Override
    public void fit(Activations data) {
        throw new UnsupportedOperationException("Use pretrainLayer method instead");
    }


    /**
     * Fit the model
     *
     * @param data the data to train on
     */
    @Override
    public void fit(org.nd4j.linalg.dataset.api.DataSet data) {
        if (layerWiseConfigurations.getBackpropType() == BackpropType.TruncatedBPTT) {

            doTruncatedBPTT(data.getFeatures(), data.getLabels(), data.getFeaturesMaskArray(),
                            data.getLabelsMaskArray());

        } else {
            //Standard training
            fit(data.getFeatures(), data.getLabels(), data.getFeaturesMaskArray(), data.getLabelsMaskArray());
        }
        clear();
    }

    /**
     * Label the probabilities of the input
     *
     * @param input    the input to label
     * @param train whether the output
     *             is test or train. This mainly
     *             affect hyper parameters such as
     *             drop out where certain things should
     *             be applied with activations
     * @return a vector of probabilities
     * given each label.
     * <p>
     * This is typically of the form:
     * [0.5, 0.5] or some other probability distribution summing to one
     */
    public INDArray output(INDArray input, boolean train) {
        return output(ActivationsFactory.getInstance().create(input), train);
    }

    public INDArray output(Activations input){
        return output(input, false);
    }

    public INDArray output(Activations input, boolean train){
        WorkspaceMode cMode = layerWiseConfigurations.getTrainingWorkspaceMode();
        layerWiseConfigurations.setTrainingWorkspaceMode(layerWiseConfigurations.getInferenceWorkspaceMode());
        MemoryWorkspace workspace =
                layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal, workspaceExternal);

        try (MemoryWorkspace wsE = workspace.notifyScopeEntered()) {
            INDArray ret = silentOutput(input.get(0), train, input.getMask(0), null).detach();

            layerWiseConfigurations.setTrainingWorkspaceMode(cMode);
            return ret;
        }
    }

    /** Calculate the output of the network, with masking arrays. The masking arrays are used in situations such
     * as one-to-many and many-to-one recurrent neural network (RNN) designs, as well as for supporting time series
     * of varying lengths within the same minibatch.
     */
    public INDArray output(INDArray input, boolean train, INDArray featuresMask, INDArray labelsMask) {
        WorkspaceMode cMode = layerWiseConfigurations.getTrainingWorkspaceMode();
        layerWiseConfigurations.setTrainingWorkspaceMode(layerWiseConfigurations.getInferenceWorkspaceMode());
        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);

        try (MemoryWorkspace wsE = workspace.notifyScopeEntered()) {
            INDArray ret = silentOutput(input, train, featuresMask, labelsMask).detach();

            layerWiseConfigurations.setTrainingWorkspaceMode(cMode);
            return ret;
        }
    }

    protected INDArray silentOutput(INDArray input, boolean train, INDArray featuresMask, INDArray labelsMask) {
        Activations in = ActivationsFactory.getInstance().create(input, featuresMask);
        List<Activations> activations = feedForward(in, train);

        //last activation is output
        INDArray out = activations.get(activations.size() - 1).get(0);
        ActivationsFactory.getInstance().release(activations);
        return out;
    }



    /**
     * Label the probabilities of the input
     *
     * @param input the input to label
     * @return a vector of probabilities
     * given each label.
     * <p>
     * This is typically of the form:
     * [0.5, 0.5] or some other probability distribution summing to one
     */
    public INDArray output(INDArray input) {
        return output(input, false);
    }

    /**
     * Prints the configuration
     */
    public void printConfiguration() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (NeuralNetConfiguration conf : getLayerWiseConfigurations().getConfs()) {
            sb.append(" Layer " + count++ + " conf " + conf);
        }

        log.info(sb.toString());
    }

    /**Sets the input and labels and returns a score for the prediction with respect to the true labels<br>
     * This is equivalent to {@link #score(DataSet, boolean)} with training==true.
     * @param data the data to score
     * @return the score for the given input,label pairs
     * @see #score(DataSet, boolean)
     */
    public double score(DataSet data) {
        return score(data, false);
    }

    /**Calculate the score (loss function) of the prediction with respect to the true labels<br>
     * @param data data to calculate score for
     * @param training If true: score during training. If false: score at test time. This can affect the application of
     *                 certain features, such as dropout and dropconnect (which are applied at training time only)
     * @return the score (value of the loss function)
     */
    public double score(DataSet data, boolean training) {

        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);

        try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
            // activation for output layer is calculated in computeScore
            Activations a = ActivationsFactory.getInstance().create(data.getFeatures(), data.getFeaturesMaskArray());
            List<Activations> activations = feedForwardToLayer(a, layers.length - 2, training);
            int n = activations.size();
            setLabels(data.getLabels());
            if (getOutputLayer() instanceof IOutputLayer) {
                IOutputLayer ol = (IOutputLayer) getOutputLayer();
                Activations olInput = activations.get(n - 1);
                ol.setInput(olInput); //Feedforward doesn't include output layer for efficiency
                ol.setLabels(data.getLabels(), data.getLabelsMaskArray());
                ol.computeScore(calcL1(true), calcL2(true), training);
                this.score = ol.score();
            } else {
                log.warn("Cannot calculate score wrt labels without an OutputLayer");
                return 0.0;
            }

            ActivationsFactory.getInstance().release(activations);
        }

        clear();
        return score();
    }

    public INDArray scoreExamples(DataSetIterator iter, boolean addRegularizationTerms) {
        List<INDArray> out = new ArrayList<>();

        while (iter.hasNext()) {
            out.add(scoreExamples(iter.next(), addRegularizationTerms));
        }
        return Nd4j.toFlattened('f', out);
    }

    /**Calculate the score for each example in a DataSet individually. Unlike {@link #score(DataSet)} and {@link #score(DataSet, boolean)}
     * this method does not average/sum over examples. This method allows for examples to be scored individually (at test time only), which
     * may be useful for example for autoencoder architectures and the like.<br>
     * Each row of the output (assuming addRegularizationTerms == true) is equivalent to calling score(DataSet) with a single example.
     * @param data The data to score
     * @param addRegularizationTerms If true: add l1/l2 regularization terms (if any) to the score. If false: don't add regularization terms
     * @return An INDArray (column vector) of size input.numRows(); the ith entry is the score (loss value) of the ith example
     */
    public INDArray scoreExamples(DataSet data, boolean addRegularizationTerms) {
        Activations a = ActivationsFactory.getInstance().create(data.getFeatures(), data.getFeaturesMaskArray());
        feedForward(a, false);
        setLabels(data.getLabels());

        INDArray out;
        if (getOutputLayer() instanceof IOutputLayer) {
            IOutputLayer ol = (IOutputLayer) getOutputLayer();
            ol.setLabels(data.getLabels(), data.getLabelsMaskArray());
            double l1 = (addRegularizationTerms ? calcL1(true) : 0.0);
            double l2 = (addRegularizationTerms ? calcL2(true) : 0.0);
            out = ol.computeScoreForExamples(l1, l2);
        } else {
            throw new UnsupportedOperationException(
                            "Cannot calculate score with respect to labels without an OutputLayer");
        }
        clear();
        return out;
    }


    @Override
    public void fit() {
        fit(input.get(0), labels);
    }


    /**
     * Score of the model (relative to the objective function)
     *
     * @return the score of the model (relative to the objective function)
     */
    @Override
    public double score() {
        return score;
    }


    public void setScore(double score) {
        this.score = score;
    }

    @Override
    public void computeGradientAndScore() {
        //Calculate activations (which are stored in each layer, and used in backprop)
        if (layerWiseConfigurations.getBackpropType() == BackpropType.TruncatedBPTT) {
            List<Activations> activations = rnnActivateUsingStoredState(getInput(), true, true);
            if (trainingListeners.size() > 0) {
                for (TrainingListener tl : trainingListeners) {
                    tl.onForwardPass(this, activations);
                }
            }
            truncatedBPTTGradient();
        } else {
            synchronizeIterEpochCounts();

            //First: do a feed-forward through the network
            //Note that we don't actually need to do the full forward pass through the output layer right now; but we do
            // need the input to the output layer to be set (such that backprop can be done)
            List<Activations> activations = feedForwardToLayer(input, layers.length - 2, true);
            if (trainingListeners.size() > 0) {
                //TODO: We possibly do want output layer activations in some cases here...
                for (TrainingListener tl : trainingListeners) {
                    tl.onForwardPass(this, activations);
                }
            }
            Activations actSecondLastLayer = activations.get(activations.size() - 1);
            getOutputLayer().setInput(actSecondLastLayer);
            ((IOutputLayer)getOutputLayer()).setLabels(labels, labelsMask);
            //Then: compute gradients
            backprop();
        }

        //Calculate score
        if (!(getOutputLayer() instanceof IOutputLayer)) {
            throw new DL4JException(
                            "Cannot calculate gradient and score with respect to labels: final layer is not an IOutputLayer");
        }
        score = ((IOutputLayer) getOutputLayer()).computeScore(calcL1(true), calcL2(true), true);

        //Listeners
        if (trainingListeners.size() > 0) {
            try (MemoryWorkspace workspace = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                for (TrainingListener tl : trainingListeners) {
                    tl.onBackwardPass(this);
                }
            }
        }

        //Clear the post noise/dropconnect parameters on the output layer
        getOutputLayer().clearNoiseWeightParams();
    }

    /**
     * Clear the inputs. Clears optimizer state.
     */
    public void clear() {
        for (Layer layer : layers)
            layer.clear();

        input = null;
        labels = null;
        solver = null;
    }

    @Override
    public void applyConstraints(int iteration, int epoch) {
        for(Layer l : layers){
            l.applyConstraints(iteration, epoch);
        }
    }


    /**
     *
     * @param input
     */
    public void setInput(INDArray input) {
        if(input == null){
            if(this.input != null)
                this.input.clear();
        } else if(this.input == null){
            this.input = ActivationsFactory.getInstance().create(input);
        } else {
            this.input.set(0, input);
        }
    }

    public void setInput(Activations input){
        this.input = input;
    }

    @Override
    public Activations getInput() {
        return input;
    }

    public void setInputs(INDArray... inputs){
        if(inputs == null)
            setInput((INDArray)null);
        if(inputs.length != 1)
            throw new IllegalArgumentException("Cannot set more than 1 input in MultiLayerNetwork: got "
                    + inputs.length + " inputs");
        setInput(inputs[0]);
    }


    /**
     * Get the output layer
     *
     * @return
     */
    public Layer getOutputLayer() {
        return getLayers()[getLayers().length - 1];
    }


    /**
     * Sets parameters for the model.
     * This is used to manipulate the weights and biases across
     * all neuralNets (including the output layer)
     *
     * @param params a parameter vector equal 1,numParameters
     */
    public void setParameters(INDArray params) {
        setParams(params);
    }

    public NeuralNetConfiguration getDefaultConfiguration() {
        return defaultConfiguration;
    }

    public INDArray getLabels() {
        return labels;
    }


    /**
     *
     * @param labels
     */
    public void setLabels(INDArray labels) {
        setLabels(labels, null);
    }

    public void setLabels(INDArray labels, INDArray labelsMask){
        this.labels = labels;
        this.labelsMask = labelsMask;
    }

    /**
     * Get the number of layers in the network
     *
     * @return the number of layers in the network
     */
    public int getnLayers() {
        return layerWiseConfigurations.getConfs().size();
    }

    /**
     *
     * @return
     */
    public synchronized Layer[] getLayers() {
        return layers;
    }

    public Layer getLayer(int i) {
        return layers[i];
    }

    public Layer getLayer(String name) {
        return layerMap.get(name);
    }

    public List<String> getLayerNames() {
        return new ArrayList<>(layerMap.keySet());
    }

    public void setLayers(Layer[] layers) {
        this.layers = layers;
    }

    @Deprecated
    public INDArray getMask() {
        if(input == null){
            return null;
        }
        return input.getMask(0);
    }

    @Deprecated
    public void setMask(INDArray mask) {
        if(input == null){
            this.input = ActivationsFactory.getInstance().create(null, mask);
        } else {
            this.input.setMask(0, mask);
            if(mask != null){
                this.input.setMaskState(0, MaskState.Active);
            }
        }
    }

    @Deprecated
    public INDArray getMaskArray() {
        return getMask();
    }

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public void clearNoiseWeightParams() {
        for(Layer l : layers){
            l.clearNoiseWeightParams();
        }
    }

    @Override
    public InputPreProcessor getPreProcessor() {
        throw new UnsupportedOperationException();
    }

    //==========
    //Layer methods

    @Override
    public Gradients backpropGradient(Gradients epsilon) {
        if (getOutputLayer() instanceof IOutputLayer)
            throw new UnsupportedOperationException("Cannot calculate gradients based on epsilon with OutputLayer");

        return calcBackpropGradients(epsilon.get(0), false);
    }

    @Override
    public void setIndex(int index) {
        layerIndex = index;
    }

    @Override
    public int getIndex() {
        return layerIndex;
    }

    @Override
    public int getIterationCount() {
        return getLayerWiseConfigurations().getIterationCount();
    }

    @Override
    public int getEpochCount() {
        return getLayerWiseConfigurations().getEpochCount();
    }

    @Override
    public void setIterationCount(int iterationCount) {
        getLayerWiseConfigurations().setIterationCount(iterationCount);
    }

    @Override
    public void setEpochCount(int epochCount) {
        getLayerWiseConfigurations().setEpochCount(epochCount);
    }

    @Override
    public double calcL2(boolean backpropParamsOnly) {
        double l2 = 0.0;
        for (int i = 0; i < layers.length; i++) {
            l2 += layers[i].calcL2(backpropParamsOnly);
        }
        return l2;
    }

    @Override
    public double calcL1(boolean backpropParamsOnly) {
        double l1 = 0.0;
        for (int i = 0; i < layers.length; i++) {
            l1 += layers[i].calcL1(backpropParamsOnly);
        }
        return l1;
    }

    @Override
    public Activations activate(boolean training) {
        INDArray output = output(input, training);
        return ActivationsFactory.getInstance().create(output);
    }

    @Override
    public void update(Gradient gradient) {
        if (gradient.gradient().length() != numParams(true))
            throw new IllegalArgumentException("Invalid input: expect gradients array of length " + numParams(true));
        Map<Integer,Gradient> temp = new HashMap<>();
        for (Map.Entry<String, INDArray> entry : gradient.gradientForVariable().entrySet()) {
            String key = entry.getKey();
            INDArray val = entry.getValue();
            int idx = key.indexOf('_');
            if (idx == -1)
                throw new IllegalStateException("Invalid param key: not have layer separator: \"" + key + "\"");
            Integer layerId = Integer.parseInt(key.substring(0, idx));
            String paramType = key.substring(idx + 1);

            Gradient g = temp.get(layerId);
            if(g == null){
                g = new DefaultGradient();
                temp.put(layerId, g);
            }
            g.gradientForVariable().put(paramType, val);
        }

        for(Map.Entry<Integer,Gradient> e : temp.entrySet()){
            layers[e.getKey()].update(e.getValue());
        }

        this.flattenedGradients.assign(gradient.gradient());
    }

    @Override
    public String getName() {
        return "MultiLayerNetwork"; //TODO
    }

    @Override
    public Activations activate(Activations input, boolean training) {
        return ActivationsFactory.getInstance().create(output(input.get(0), training), null, null);
    }

    @Override
    public Activations activate(Activations input) {
        return activate(input, false);
    }

    @Override
    public void setInputMiniBatchSize(int size) {
        if (layers != null)
            for (Layer l : layers)
                l.setInputMiniBatchSize(size);
    }

    @Override
    public int getInputMiniBatchSize() {
        if(input == null || input.get(0) == null){
            return -1;
        }
        return input.get(0).size(0);
    }

    /**
     *
     * If this MultiLayerNetwork contains one or more RNN layers: conduct forward pass (prediction)
     * but using previous stored state for any RNN layers. The activations for the final step are
     * also stored in the RNN layers for use next time rnnTimeStep() is called.<br>
     * This method can be used to generate output one or more steps at a time instead of always having to do
     * forward pass from t=0. Example uses are for streaming data, and for generating samples from network output
     * one step at a time (where samples are then fed back into the network as input)<br>
     * If no previous state is present in RNN layers (i.e., initially or after calling rnnClearPreviousState()),
     * the default initialization (usually 0) is used.<br>
     * Supports mini-batch (i.e., multiple predictions/forward pass in parallel) as well as for single examples.<br>
     * @param input Input to network. May be for one or multiple time steps. For single time step:
     *  input has shape [miniBatchSize,inputSize] or [miniBatchSize,inputSize,1]. miniBatchSize=1 for single example.<br>
     *  For multiple time steps: [miniBatchSize,inputSize,inputTimeSeriesLength]
     * @return Output activations. If output is RNN layer (such as RnnOutputLayer): if input has shape [miniBatchSize,inputSize]
     * i.e., is 2d, output has shape [miniBatchSize,outputSize] (i.e., also 2d).<br>
     * Otherwise output is 3d [miniBatchSize,outputSize,inputTimeSeriesLength] when using RnnOutputLayer.
     */
    public INDArray rnnTimeStep(INDArray input) {
        return rnnTimeStep(ActivationsFactory.getInstance().create(input));
    }

    public INDArray rnnTimeStep(Activations input){
        this.setInputMiniBatchSize(input.get(0).size(0)); //Necessary for preprocessors/reshaping
        boolean inputIs2d = input.get(0).rank() == 2;
        for (int i = 0; i < layers.length; i++) {
            if (layers[i] instanceof RecurrentLayer) {
                input = ((RecurrentLayer) layers[i]).rnnTimeStep(input);
            } else if (layers[i] instanceof MultiLayerNetwork) {
                input = ActivationsFactory.getInstance().create(((MultiLayerNetwork) layers[i]).rnnTimeStep(input), null, null);
            } else {
                input = layers[i].activate(input, false);
            }
        }
        if (inputIs2d && input.get(0).rank() == 3 ) {
            //Return 2d output with shape [miniBatchSize,nOut]
            // instead of 3d output with shape [miniBatchSize,nOut,1]
            input.set(0, input.get(0).tensorAlongDimension(0, 1, 0));
        }

        this.input = null;
        return input.get(0);
    }

    /**Get the state of the RNN layer, as used in rnnTimeStep().
     * @param layer Number/index of the layer.
     * @return Hidden state, or null if layer is not an RNN layer
     */
    public Map<String, INDArray> rnnGetPreviousState(int layer) {
        if (layer < 0 || layer >= layers.length)
            throw new IllegalArgumentException("Invalid layer number");
        if (!(layers[layer] instanceof RecurrentLayer))
            throw new IllegalArgumentException("Layer is not an RNN layer");
        return ((RecurrentLayer) layers[layer]).rnnGetPreviousState();
    }

    /**Set the state of the RNN layer.
     * @param layer The number/index of the layer.
     * @param state The state to set the specified layer to
     */
    public void rnnSetPreviousState(int layer, Map<String, INDArray> state) {
        if (layer < 0 || layer >= layers.length)
            throw new IllegalArgumentException("Invalid layer number");
        if (!(layers[layer] instanceof RecurrentLayer))
            throw new IllegalArgumentException("Layer is not an RNN layer");

        RecurrentLayer r = (RecurrentLayer) layers[layer];
        r.rnnSetPreviousState(state);
    }

    /** Clear the previous state of the RNN layers (if any).
     */
    public void rnnClearPreviousState() {
        if (layers == null)
            return;
        for (int i = 0; i < layers.length; i++) {
            if (layers[i] instanceof RecurrentLayer)
                ((RecurrentLayer) layers[i]).rnnClearPreviousState();
            else if (layers[i] instanceof MultiLayerNetwork) {
                ((MultiLayerNetwork) layers[i]).rnnClearPreviousState();
            }
        }
    }

    /** Similar to rnnTimeStep and feedForward() methods. Difference here is that this method:<br>
     * (a) like rnnTimeStep does forward pass using stored state for RNN layers, and<br>
     * (b) unlike rnnTimeStep does not modify the RNN layer state<br>
     * Therefore multiple calls to this method with the same input should have the same output.<br>
     * Typically used during training only. Use rnnTimeStep for prediction/forward pass at test time.
     * @param input Input to network
     * @param training Whether training or not
     * @param storeLastForTBPTT set to true if used as part of truncated BPTT training
     * @return Activations for each layer (including input, as per feedforward() etc)
     */
    public List<INDArray> rnnActivateUsingStoredState(INDArray input, boolean training, boolean storeLastForTBPTT) {
        List<Activations> list = rnnActivateUsingStoredState(ActivationsFactory.getInstance().create(input), training, storeLastForTBPTT);
        return ActivationsFactory.getActivationINDArrays(list);
    }

    public List<Activations> rnnActivateUsingStoredState(Activations input, boolean training, boolean storeLastForTBPTT){
        Activations currInput = input;
        List<Activations> activations = new ArrayList<>();
        activations.add(currInput);

        for (int i = 0; i < layers.length; i++) {
            if (layers[i] instanceof RecurrentLayer) {
                currInput = ((RecurrentLayer) layers[i]).rnnActivateUsingStoredState(currInput, training,
                                storeLastForTBPTT);
            } else if (layers[i] instanceof MultiLayerNetwork) {
                List<Activations> temp = ((MultiLayerNetwork) layers[i]).rnnActivateUsingStoredState(currInput, training,
                                storeLastForTBPTT);
                currInput = temp.get(temp.size() - 1);
            } else {
                currInput = layers[i].activate(currInput, training);
            }
            activations.add(currInput);
        }
        return activations;
    }

    /** Get the updater for this MultiLayerNetwork
     * @return Updater for MultiLayerNetwork
     */
    public synchronized Updater getUpdater() {
        if (solver == null) {
            solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this).build();
            solver.getOptimizer().setUpdater(UpdaterCreator.getUpdater(this));
        }
        return solver.getOptimizer().getUpdater();
    }

    /** Set the updater for the MultiLayerNetwork */
    public void setUpdater(Updater updater) {
        if (solver == null) {
            solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this).build();
        }
        solver.getOptimizer().setUpdater(updater);
    }

    /**
     * Evaluate the network (classification performance)
     *
     * @param iterator Iterator to evaluate on
     * @return Evaluation object; results of evaluation on all examples in the data set
     */
    public Evaluation evaluate(DataSetIterator iterator) {
        return evaluate(iterator, null);
    }

    /**
     * Evaluate the network for regression performance
     * @param iterator Data to evaluate on
     * @return
     */
    public RegressionEvaluation evaluateRegression(DataSetIterator iterator) {
        return doEvaluation(iterator, new RegressionEvaluation(iterator.totalOutcomes()))[0];
    }

    /**
     * Evaluate the network (must be a binary classifier) on the specified data, using the {@link ROC} class
     *
     * @param iterator          Data to evaluate on
     * @param rocThresholdSteps Number of threshold steps to use with {@link ROC}
     * @return ROC evaluation on the given dataset
     */
    public ROC evaluateROC(DataSetIterator iterator, int rocThresholdSteps) {
        return doEvaluation(iterator, new ROC(rocThresholdSteps))[0];
    }

    /**
     * Evaluate the network on the specified data, using the {@link ROCMultiClass} class
     *
     * @param iterator          Data to evaluate on
     * @param rocThresholdSteps Number of threshold steps to use with {@link ROCMultiClass}
     * @return Multi-class ROC evaluation on the given dataset
     */
    public ROCMultiClass evaluateROCMultiClass(DataSetIterator iterator, int rocThresholdSteps) {
        return doEvaluation(iterator, new ROCMultiClass(rocThresholdSteps))[0];
    }

    /**
     * Perform evaluation using an arbitrary IEvaluation instance.
     *
     * @param iterator   data to evaluate on
     */
    public <T extends IEvaluation> T[] doEvaluation(DataSetIterator iterator, T... evaluations) {
        if (!iterator.hasNext() && iterator.resetSupported()) {
            iterator.reset();
        }

        DataSetIterator iter = iterator.asyncSupported() ? new AsyncDataSetIterator(iterator, 2, true) : iterator;

        WorkspaceMode cMode = layerWiseConfigurations.getTrainingWorkspaceMode();
        layerWiseConfigurations.setTrainingWorkspaceMode(layerWiseConfigurations.getInferenceWorkspaceMode());

        MemoryWorkspace workspace =
                        layerWiseConfigurations.getTrainingWorkspaceMode() == WorkspaceMode.NONE ? new DummyWorkspace()
                                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                                                        workspaceConfigurationExternal, workspaceExternal);

        while (iter.hasNext()) {
            DataSet next = iter.next();

            if (next.getFeatureMatrix() == null || next.getLabels() == null)
                break;

            try (MemoryWorkspace wsB = workspace.notifyScopeEntered()) {

                INDArray features = next.getFeatures();
                INDArray labels = next.getLabels();
                INDArray fMask = next.getFeaturesMaskArray();
                INDArray lMask = next.getLabelsMaskArray();

                INDArray out = this.silentOutput(features, false, fMask, lMask);

                try (MemoryWorkspace wsO = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()) {
                    for (T evaluation : evaluations)
                        evaluation.eval(labels, out, lMask);
                }
            }

            clear();
        }

        if (iterator.asyncSupported())
            ((AsyncDataSetIterator) iter).shutdown();

        layerWiseConfigurations.setTrainingWorkspaceMode(cMode);

        return evaluations;
    }

    /**
     * Evaluate the network on the provided data set. Used for evaluating the performance of classifiers
     *
     * @param iterator Data to undertake evaluation on
     * @return Evaluation object, summarizing the results of the evaluation on the provided DataSetIterator
     */
    public Evaluation evaluate(DataSetIterator iterator, List<String> labelsList) {
        return evaluate(iterator, labelsList, 1);
    }

    @Override
    public INDArray updaterState() {
        return getUpdater() != null ? getUpdater().getStateViewArray() : null;
    }

    @Override
    public void fit(MultiDataSet dataSet) {
        if (dataSet.getFeatures().length == 1 && dataSet.getLabels().length == 1) {
            INDArray features = null;
            INDArray labels = null;
            INDArray fMask = null;
            INDArray lMask = null;

            if (dataSet.getFeaturesMaskArrays() != null)
                fMask = dataSet.getFeaturesMaskArrays()[0];

            if (dataSet.getFeaturesMaskArrays() != null)
                lMask = dataSet.getLabelsMaskArrays()[0];

            features = dataSet.getFeatures()[0];
            labels = dataSet.getLabels()[0];

            DataSet ds = new DataSet(features, labels, fMask, lMask);
            fit(ds);
        }
        throw new DL4JInvalidInputException(
                        "MultiLayerNetwork can't handle MultiDataSet. Please consider use of ComputationGraph");
    }

    @Override
    public void fit(MultiDataSetIterator iterator) {
        fit(new MultiDataSetWrapperIterator(iterator));
    }

    @Override
    public <T extends IEvaluation> T[] doEvaluation(MultiDataSetIterator iterator, T[] evaluations) {
        return doEvaluation(new MultiDataSetWrapperIterator(iterator), evaluations);
    }

    /**
     * Evaluate the network (for classification) on the provided data set, with top N accuracy in addition to standard accuracy.
     * For 'standard' accuracy evaluation only, use topN = 1
     *
     * @param iterator   Iterator (data) to evaluate on
     * @param labelsList List of labels. May be null.
     * @param topN       N value for top N accuracy evaluation
     * @return Evaluation object, summarizing the results of the evaluation on the provided DataSetIterator
     */
    public Evaluation evaluate(DataSetIterator iterator, List<String> labelsList, int topN) {
        if (layers == null || !(getOutputLayer() instanceof IOutputLayer)) {
            throw new IllegalStateException("Cannot evaluate network with no output layer");
        }
        if (labelsList == null)
            labelsList = iterator.getLabels();

        Evaluation e = new Evaluation(labelsList, topN);
        doEvaluation(iterator, e);

        return e;
    }

    private void update(Task task) {
        if (!initDone) {
            initDone = true;
            Heartbeat heartbeat = Heartbeat.getInstance();
            task = ModelSerializer.taskByModel(this);
            Environment env = EnvironmentUtils.buildEnvironment();
            heartbeat.reportEvent(Event.STANDALONE, env, task);
        }
    }

    /**
     * String detailing the architecture of the multilayernetwork.
     * Columns are LayerIndex with layer type, nIn, nOut, Total number of parameters and the Shapes of the parameters
     * Will also give information about frozen layers, if any.
     * @return Summary as a string
     */
    public String summary() {
        return summary(null);
    }

    /**
     * String detailing the architecture of the multilayernetwork.
     * Will also display activation size when given an input type.
     * Columns are LayerIndex with layer type, nIn, nOut, Total number of parameters, Shapes of the parameters, Input activation shape, Output activation shape
     * Will also give information about frozen layers, if any.
     * @return Summary as a string
     */
    public String summary(InputType inputType) {
        String ret = "\n";
        ret += StringUtils.repeat("=", 250);
        ret += "\n";
        if (inputType != null) {
            ret += String.format("%-40s%-10s%-12s%-40s%-75s%-75s\n", "LayerName (LayerType)", "nIn,nOut", "TotalParams",
                    "ParamsShape","InputShape", "OutputShape");
        }
        else {
            ret += String.format("%-40s%-10s%-12s%-40s\n", "LayerName (LayerType)", "nIn,nOut", "TotalParams",
                    "ParamsShape");
        }
        ret += StringUtils.repeat("=", 250);
        ret += "\n";
        int frozenParams = 0;
        for (org.deeplearning4j.nn.api.Layer currentLayer : getLayers()) {
            String name = currentLayer.conf().getLayer().getLayerName();
            if (name == null) {
                name = String.valueOf(currentLayer.getIndex());
            }
            String paramShape = "-";
            String in = "-";
            String out = "-";
            String[] classNameArr = currentLayer.getClass().getName().split("\\.");
            String className = classNameArr[classNameArr.length - 1];
            String paramCount = String.valueOf(currentLayer.numParams());
            String inShape = "";
            String outShape = "";
            InputPreProcessor preProcessor;
            InputType outType;
            if (inputType != null) {
                preProcessor = currentLayer.getPreProcessor();
                inShape = inputType.toString();
                if (preProcessor != null) {
                    inShape += "--> " + preProcessor.getOutputType(inputType)[0].toString();
                }
                outType = currentLayer.conf().getLayer().getOutputType(currentLayer.getIndex(), inputType)[0];
                outShape = outType.toString();
                inputType = outType;
            }
            if (currentLayer.numParams() > 0) {
                paramShape = "";
                in = String.valueOf(((FeedForwardLayer) currentLayer.conf().getLayer()).getNIn());
                out = String.valueOf(((FeedForwardLayer) currentLayer.conf().getLayer()).getNOut());
                Set<String> paraNames = currentLayer.paramTable().keySet();
                for (String aP : paraNames) {
                    String paramS = ArrayUtils.toString(currentLayer.paramTable().get(aP).shape());
                    paramShape += aP + ":" + paramS + ", ";
                }
                paramShape = paramShape.subSequence(0, paramShape.lastIndexOf(",")).toString();
            }
            if (currentLayer instanceof FrozenLayer) {
                frozenParams += currentLayer.numParams();
                classNameArr = ((FrozenLayer) currentLayer).getInsideLayer().getClass().getName().split("\\.");
                className = "Frozen " + classNameArr[classNameArr.length - 1];
            }
            if (inputType!= null) {
                ret += String.format("%-40s%-10s%-12s%-40s%-75s%-75s", name + " (" + className + ")", in + "," + out, paramCount,
                        paramShape,inShape,outShape);
            }
            else {
                ret += String.format("%-40s%-12s%-10s%-40s", name + " (" + className + ")", in + "," + out, paramCount,
                        paramShape);
            }
            ret += "\n";
        }
        ret += StringUtils.repeat("-", 250);
        ret += String.format("\n%30s %d", "Total Parameters: ", params().length());
        ret += String.format("\n%30s %d", "Trainable Parameters: ", params().length() - frozenParams);
        ret += String.format("\n%30s %d", "Frozen Parameters: ", frozenParams);
        ret += "\n";
        ret += StringUtils.repeat("=", 250);
        ret += "\n";
        return ret;
    }

    /**
     * This method just makes sure there's no state preserved within layers
     */
    protected void clearLayersStates() {
        for (int f = 0; f < layers.length; f++) {
            layers[f].clear();
        }
    }

    /**
     * Increment the epoch count (in the underlying {@link MultiLayerConfiguration} by 1).
     * Note that this is done <i>automatically</i> when using iterator-based fitting methods, such as
     * {@link #fit(DataSetIterator)}. However, when using non-iterator fit methods (DataSet, INDArray/INDArray etc),
     * the network has no way to know when one epoch ends and another starts. In such situations, this method
     * can be used to increment the epoch counter.<br>
     * Note that the epoch counter is used for situations such as some learning rate schedules, and the like.
     *
     * The current epoch count can be obtained using {@code MultiLayerConfiguration.getLayerwiseConfiguration().getEpochCount()}
     */
    public void incrementEpochCount(){
        layerWiseConfigurations.setEpochCount(layerWiseConfigurations.getEpochCount() + 1);
    }


    protected void synchronizeIterEpochCounts(){
        //TODO: this is necessrry for some schedules - but the redundant values are a little ugly...
        int currIter = getIterationCount();
        int currEpoch = getEpochCount();
        for(Layer l : layers){
            l.setIterationCount(currIter);
            l.setEpochCount(currEpoch);
        }
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * <p>
     * The {@code equals} method implements an equivalence relation
     * on non-null object references:
     * <ul>
     * <li>It is <i>reflexive</i>: for any non-null reference value
     * {@code x}, {@code x.equals(x)} should return
     * {@code true}.
     * <li>It is <i>symmetric</i>: for any non-null reference values
     * {@code x} and {@code y}, {@code x.equals(y)}
     * should return {@code true} if and only if
     * {@code y.equals(x)} returns {@code true}.
     * <li>It is <i>transitive</i>: for any non-null reference values
     * {@code x}, {@code y}, and {@code z}, if
     * {@code x.equals(y)} returns {@code true} and
     * {@code y.equals(z)} returns {@code true}, then
     * {@code x.equals(z)} should return {@code true}.
     * <li>It is <i>consistent</i>: for any non-null reference values
     * {@code x} and {@code y}, multiple invocations of
     * {@code x.equals(y)} consistently return {@code true}
     * or consistently return {@code false}, provided no
     * information used in {@code equals} comparisons on the
     * objects is modified.
     * <li>For any non-null reference value {@code x},
     * {@code x.equals(null)} should return {@code false}.
     * </ul>
     * <p>
     * The {@code equals} method for class {@code Object} implements
     * the most discriminating possible equivalence relation on objects;
     * that is, for any non-null reference values {@code x} and
     * {@code y}, this method returns {@code true} if and only
     * if {@code x} and {@code y} refer to the same object
     * ({@code x == y} has the value {@code true}).
     * <p>
     * Note that it is generally necessary to override the {@code hashCode}
     * method whenever this method is overridden, so as to maintain the
     * general contract for the {@code hashCode} method, which states
     * that equal objects must have equal hash codes.
     *
     * @param obj the reference object with which to compare.
     * @return {@code true} if this object is the same as the obj
     * argument; {@code false} otherwise.
     * @see #hashCode()
     * @see HashMap
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (obj instanceof MultiLayerNetwork) {
            MultiLayerNetwork network = (MultiLayerNetwork) obj;
            boolean paramsEquals = network.params().equals(params());
            boolean confEquals = getLayerWiseConfigurations().equals(network.getLayerWiseConfigurations());
            boolean updaterEquals = getUpdater().equals(network.getUpdater());
            return paramsEquals && confEquals && updaterEquals;
        }
        return false;
    }
}
