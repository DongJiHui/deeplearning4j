package org.nd4j.autodiff.opvalidation;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.Test;
import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.validation.OpValidation;
import org.nd4j.autodiff.validation.TestCase;
import org.nd4j.linalg.api.blas.params.MMulTranspose;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.checkutil.NDArrayCreationUtil;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.linalg.primitives.Triple;
import org.nd4j.linalg.util.ArrayUtil;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import static org.nd4j.linalg.indexing.NDArrayIndex.interval;

@Slf4j
public class MiscOpValidation extends BaseOpValidation {

    public MiscOpValidation(Nd4jBackend backend) {
        super(backend);
    }



    @Test
    public void testGradientAutoBroadcast1() {

        Nd4j.getRandom().setSeed(12345);

        List<String> failed = new ArrayList<>();

        for (int dim_sz1 : new int[]{0, 1, 2}) {

            int[] in2Shape = {3, 4, 5};
            in2Shape[dim_sz1] = 1;

            for (int i = 0; i < 8; i++) {

                SameDiff sd = SameDiff.create();

                SDVariable in3 = sd.var("in3", Nd4j.rand(new int[]{3, 4, 5}));
                SDVariable in2 = sd.var("in2", in2Shape);

                SDVariable bcOp;
                String name;
                switch (i) {
                    case 0:
                        bcOp = in3.add(in2);
                        name = "add";
                        break;
                    case 1:
                        bcOp = in3.sub(in2);
                        name = "sub";
                        break;
                    case 2:
                        bcOp = in3.mul(in2);
                        name = "mul";
                        break;
                    case 3:
                        bcOp = in3.div(in2);
                        name = "div";
                        break;
                    case 4:
                        bcOp = in3.rsub(in2);
                        name = "rsub";
                        break;
                    case 5:
                        bcOp = in3.rdiv(in2);
                        name = "rdiv";
                        break;
                    case 6:
                        bcOp = sd.f().floorDiv(in3, in2);
                        name = "floordiv";
                        break;
                    case 7:
                        bcOp = sd.f().floorMod(in3, in2);
                        name = "floormod";
                        break;
                    default:
                        throw new RuntimeException();
                }

                SDVariable outVar = sd.sum(bcOp);

                String msg = "(test " + i + ": " + name + ", dimension=" + dim_sz1 + ")";
                log.info("*** Starting test: " + msg);

                INDArray in3Arr = Nd4j.randn(new int[]{3, 4, 5}).muli(100);
                INDArray in2Arr = Nd4j.randn(in2Shape).muli(100);

                sd.associateArrayWithVariable(in3Arr, in3);
                sd.associateArrayWithVariable(in2Arr, in2);

                TestCase tc = new TestCase(sd);

                String error = OpValidation.validate(tc);
                if(error != null){
                    failed.add(name);
                }
            }
        }

        assertEquals("Failed: " + failed, 0, failed.size());
    }

    @Test
    public void testGradientAutoBroadcast2() {

        Nd4j.getRandom().setSeed(12345);

        List<String> failed = new ArrayList<>();

        for (int[] dim_sz1s : new int[][]{{0, 1}, {0, 2}, {1, 2}, {0, 1, 2}}) {

            int[] otherShape = {3, 4, 5};
            otherShape[dim_sz1s[0]] = 1;
            otherShape[dim_sz1s[1]] = 1;
            if (dim_sz1s.length == 3) {
                otherShape[dim_sz1s[2]] = 1;
            }

            for (int i = 0; i < 8; i++) {

                SameDiff sd = SameDiff.create();

                SDVariable in3 = sd.var("in3", new int[]{3, 4, 5});
                SDVariable in2 = sd.var("inToBc", otherShape);

                String name;
                SDVariable bcOp;
                switch (i) {
                    case 0:
                        bcOp = in3.add(in2);
                        name = "add";
                        break;
                    case 1:
                        bcOp = in3.sub(in2);
                        name = "sub";
                        break;
                    case 2:
                        bcOp = in3.mul(in2);
                        name = "mul";
                        break;
                    case 3:
                        bcOp = in3.div(in2);
                        name = "div";
                        break;
                    case 4:
                        bcOp = in3.rsub(in2);
                        name = "rsub";
                        break;
                    case 5:
                        bcOp = in3.rdiv(in2);
                        name = "rdiv";
                        break;
                    case 6:
                        bcOp = sd.f().floorDiv(in3, in2);
                        name = "floordiv";
                        break;
                    case 7:
                        bcOp = sd.f().floorMod(in3, in2);
                        name = "floormod";
                        break;
                    default:
                        throw new RuntimeException();
                }

                SDVariable outVar = sd.sum(bcOp);

                String msg = "(test " + i + ": " + name + ", dimensions=" + Arrays.toString(dim_sz1s) + ")";
                log.info("*** Starting test: " + msg);

                INDArray in3Arr = Nd4j.randn(new int[]{3, 4, 5}).muli(100);
                INDArray in2Arr = Nd4j.randn(otherShape).muli(100);

                sd.associateArrayWithVariable(in3Arr, in3);
                sd.associateArrayWithVariable(in2Arr, in2);

                TestCase tc = new TestCase(sd);
                String error = OpValidation.validate(tc);
                if(error != null){
                    failed.add(name);
                }
            }
        }

        assertEquals("Failed: " + failed, 0, failed.size());
    }

    @Test
    public void testGradientAutoBroadcast3() {
        //These tests: output size > input sizes

        fail("TEST CRASHES JVM");

        Nd4j.getRandom().setSeed(12345);

        List<String> failed = new ArrayList<>();

        //Test cases: in1Shape, in2Shape, shapeOf(op(in1,in2))
        List<Triple<long[], long[], long[]>> testCases = new ArrayList<>();
        testCases.add(new Triple<>(new long[]{3, 1}, new long[]{1, 4}, new long[]{3, 4}));
        testCases.add(new Triple<>(new long[]{3, 1}, new long[]{3, 4}, new long[]{3, 4}));
        testCases.add(new Triple<>(new long[]{3, 4}, new long[]{1, 4}, new long[]{3, 4}));
        testCases.add(new Triple<>(new long[]{3, 4, 1}, new long[]{1, 1, 5}, new long[]{3, 4, 5}));
        testCases.add(new Triple<>(new long[]{3, 4, 1}, new long[]{3, 1, 5}, new long[]{3, 4, 5}));
        testCases.add(new Triple<>(new long[]{3, 1, 5}, new long[]{1, 4, 1}, new long[]{3, 4, 5}));
        testCases.add(new Triple<>(new long[]{3, 1, 5}, new long[]{1, 4, 5}, new long[]{3, 4, 5}));
        testCases.add(new Triple<>(new long[]{3, 1, 5}, new long[]{3, 4, 5}, new long[]{3, 4, 5}));
        testCases.add(new Triple<>(new long[]{3, 1, 1, 1}, new long[]{1, 4, 5, 6}, new long[]{3, 4, 5, 6}));
        testCases.add(new Triple<>(new long[]{1, 1, 1, 6}, new long[]{3, 4, 5, 6}, new long[]{3, 4, 5, 6}));
        testCases.add(new Triple<>(new long[]{1, 4, 5, 1}, new long[]{3, 1, 1, 6}, new long[]{3, 4, 5, 6}));
        testCases.add(new Triple<>(new long[]{1, 6}, new long[]{3, 4, 5, 1}, new long[]{3, 4, 5, 6}));

        for (val p : testCases) {

            for (int i = 0; i < 8; i++) {

                SameDiff sd = SameDiff.create();

                SDVariable in3 = sd.var("in1", p.getFirst());
                SDVariable in2 = sd.var("in2", p.getSecond());

                String name;
                SDVariable bcOp;
                switch (i) {
                    case 0:
                        bcOp = in3.add(in2);
                        name = "add";
                        break;
                    case 1:
                        bcOp = in3.sub(in2);
                        name = "sub";
                        break;
                    case 2:
                        bcOp = in3.mul(in2);
                        name = "mul";
                        break;
                    case 3:
                        bcOp = in3.div(in2);
                        name = "div";
                        break;
                    case 4:
                        bcOp = in3.rsub(in2);
                        name = "rsub";
                        break;
                    case 5:
                        bcOp = in3.rdiv(in2);
                        name = "rdiv";
                        break;
                    case 6:
                        bcOp = sd.f().floorDiv(in3, in2);
                        name = "floordiv";
                        break;
                    case 7:
                        bcOp = sd.f().floorMod(in3, in2);
                        name = "floormod";
                        break;
                    default:
                        throw new RuntimeException();
                }

                SDVariable outVar = sd.sum(bcOp);

                String msg = "(test " + i + ": " + name + ", array 1 size =" + Arrays.toString(p.getFirst())
                        + ", array 2 size = " + Arrays.toString(p.getSecond()) + ")";
                log.info("*** Starting test: " + msg);

                INDArray in3Arr = Nd4j.randn(p.getFirst()).muli(100);
                INDArray in2Arr = Nd4j.randn(p.getSecond()).muli(100);

                sd.associateArrayWithVariable(in3Arr, in3);
                sd.associateArrayWithVariable(in2Arr, in2);

                TestCase tc = new TestCase(sd);
                String error = OpValidation.validate(tc);
                if(error != null){
                    failed.add(name);
                }
            }
        }

        assertEquals("Failed: " + failed, 0, failed.size());
    }



    @Test
    public void testScatterOpGradients() {


        List<String> failed = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Nd4j.getRandom().setSeed(12345);

            SameDiff sd = SameDiff.create();

            SDVariable in = sd.var("in", new int[]{20, 10});
            SDVariable indices = sd.var("indices", new long[]{5});
            SDVariable updates = sd.var("updates", new int[]{5, 10});


            in.setArray(Nd4j.rand(20, 10));
            indices.setArray(Nd4j.create(new double[]{3, 4, 5, 10, 18}));
            updates.setArray(Nd4j.rand(5, 10).muli(2).subi(1));

            SDVariable scatter;
            String name;
            switch (i) {
                case 0:
                    scatter = sd.scatterAdd("s", in, indices, updates);
                    name = "scatterAdd";
                    break;
                case 1:
                    scatter = sd.scatterSub("s", in, indices, updates);
                    name = "scatterSub";
                    break;
                case 2:
                    scatter = sd.scatterMul("s", in, indices, updates);
                    name = "scatterMul";
                    break;
                case 3:
                    scatter = sd.scatterDiv("s", in, indices, updates);
                    name = "scatterDiv";
                    break;
                case 4:
                    scatter = sd.scatterUpdate("s", in, indices, updates);
                    name = "scatterUpdate";
                    break;
                default:
                    throw new RuntimeException();
            }

            SDVariable loss = sd.sum(scatter);  //.standardDeviation(scatter, true);  //.sum(scatter);  //TODO stdev might be better here as gradients are non-symmetrical...
            sd.execAndEndResult();

            TestCase tc = new TestCase(sd);
            String error = OpValidation.validate(tc);
            if(error != null){
                failed.add(name);
            }
        }

        assertEquals(failed.toString(), 0, failed.size());
    }

    @Test
    public void testGatherGradient() {
        Nd4j.getRandom().setSeed(12345);

        List<String> failed = new ArrayList<>();

        for (int rank = 2; rank <= 3; rank++) {
            for (int dim = 0; dim < rank; dim++) {
                SameDiff sd = SameDiff.create();

                int[] inShape;
                if (rank == 2) {
                    inShape = new int[]{10, 10};
                } else {
                    inShape = new int[]{10, 10, 10};
                }

                SDVariable in = sd.var("in", Nd4j.rand(inShape));
                SDVariable indices = sd.var("indices", Nd4j.create(new double[]{0, 3, 7}));

                SDVariable gather = sd.gather(in, indices, dim);
                sd.execAndEndResult();  //TODO REMOVE THIS

                SDVariable loss = sd.standardDeviation("loss", gather, true, Integer.MAX_VALUE);

                String msg = "rank=" + rank + ", dim=" + dim;

                TestCase tc = new TestCase(sd).testName(msg);
                String error = OpValidation.validate(tc);
                if(error != null){
                    failed.add(msg);
                }
            }
        }

        assertEquals(failed.toString(), 0, failed.size());
    }


    @Test
    public void testTensorGradTensorMmul() {
        Nd4j.getRandom().setSeed(12345);
        SameDiff sameDiff = SameDiff.create();
        INDArray arr = Nd4j.rand(new long[]{2, 2, 2});
        INDArray arr2 = Nd4j.rand(new long[]{2, 2, 2});
        SDVariable x = sameDiff.var("x", arr);
        SDVariable y = sameDiff.var("y", arr2);
        SDVariable result = sameDiff.tensorMmul(x, y, new int[][]{{0}, {1}});
        assertArrayEquals(ArrayUtil.getTensorMmulShape(new long[]{2, 2, 2}, new long[]{2, 2, 2}, new int[][]{{0}, {1}}), result.getShape());
        assertEquals(32, sameDiff.numElements());

        SDVariable loss = sameDiff.standardDeviation(result, true);

        String err = OpValidation.validate(new TestCase(sameDiff));
    }

    @Test
    public void testMulGradient() {
        INDArray arr1 = Nd4j.linspace(1, 4, 4).reshape(2, 2);
        INDArray arr2 = Nd4j.linspace(1, 4, 4).reshape(2, 2);

        INDArray gradAssertion = Nd4j.ones(arr1.shape());
        INDArray scalar = Nd4j.scalar(1.0);
        INDArray aGradAssertion = Nd4j.create(new double[][]{
                {1, 4},
                {9, 16}
        });

        INDArray cGradAssertion = Nd4j.create(new double[][]{
                {1, 2},
                {3, 4}
        });

        INDArray wGradAssertion = Nd4j.create(new double[][]{
                {2, 8},
                {18, 32}
        });

        INDArray dGradAssertion = Nd4j.ones(2, 2);

        SameDiff sameDiff = SameDiff.create();

        SDVariable sdVariable = sameDiff.var("a", arr1);
        SDVariable sdVariable1 = sameDiff.var("w", arr2);
        SDVariable varMulPre = sdVariable.mul("c", sdVariable1);
        SDVariable varMul = varMulPre.mul("d", sdVariable1);
        SDVariable sum = sameDiff.sum("ret", varMul, Integer.MAX_VALUE);

        Pair<Map<SDVariable, DifferentialFunction>, List<DifferentialFunction>> mapListPair = sameDiff.execBackwards();

        SDVariable finalResult = sameDiff.grad(sum.getVarName());

        SDVariable cGrad = sameDiff.grad(varMulPre.getVarName());

        SDVariable mulGradResult = sameDiff.grad(varMul.getVarName());
        SDVariable aGrad = sameDiff.grad(sdVariable.getVarName());
        SDVariable wGrad = sameDiff.grad(sdVariable1.getVarName());
        SDVariable dGrad = sameDiff.grad(varMul.getVarName());

        INDArray scalarGradTest = finalResult.getArr();
        assertEquals(scalar, scalarGradTest);


        INDArray gradTest = mulGradResult.getArr();
        assertEquals(gradAssertion, gradTest);

        INDArray aGradTest = aGrad.getArr();
        assertEquals(aGradAssertion, aGradTest);

        INDArray cGradTest = cGrad.getArr();
        assertEquals(cGradAssertion, cGradTest);

        INDArray wGradTest = wGrad.getArr();
        assertEquals(wGradAssertion, wGradTest);

        INDArray dGradTest = dGrad.getArr();
        assertEquals(dGradAssertion, dGradTest);
    }


    @Test
    public void testMmulGradient() {
        SameDiff sameDiff = SameDiff.create();
        INDArray sumInput = Nd4j.linspace(1, 4, 4).reshape(2, 2);
        Map<String, INDArray> inputs = new HashMap<>();
        inputs.put("x", sumInput);
        inputs.put("y", sumInput.dup());

        sameDiff.defineFunction("mmulGradient", new SameDiff.SameDiffFunctionDefinition() {
            @Override
            public SDVariable[] define(SameDiff sameDiff, Map<String, INDArray> inputs, SDVariable[] variableInputs) {
                SDVariable input = sameDiff.var("x", inputs.get("x"));
                SDVariable input2 = sameDiff.var("y", inputs.get("y"));
                SDVariable exp = sameDiff.mmul(input, input2);
                SDVariable sum = sameDiff.sum(exp, Integer.MAX_VALUE);
                return new SDVariable[]{sum};
            }
        }, inputs);

        List<DifferentialFunction> ops = sameDiff.getFunction("mmulGradient").execBackwards().getRight();
        String print = sameDiff.asFlatPrint();


        assumeNotNull(sameDiff.getFunction("mmulGradient").getFunction("grad"));
        assumeNotNull(sameDiff.getFunction("mmulGradient").grad("x"));
        assumeNotNull(sameDiff.getFunction("mmulGradient").grad("y"));

        SDVariable gradWrtX = sameDiff.getFunction("mmulGradient").grad("x");
        SDVariable gradWrtY = sameDiff.getFunction("mmulGradient").grad("y");
        assumeNotNull(gradWrtX.getArr());
        assumeNotNull(gradWrtY.getArr());


        INDArray xGradAssertion = Nd4j.create(new double[][]{
                {3, 7},
                {3, 7}
        });

        INDArray yGradAssertion = Nd4j.create(new double[][]{
                {4, 4},
                {6, 6}
        });

        assertEquals(xGradAssertion, gradWrtX.getArr());
        assertEquals(yGradAssertion, gradWrtY.getArr());
    }


    @Test
    public void testMmulWithTranspose() {
        //Here: [x,3]^T * [x,4] = [3,4]

        for (int i : new int[]{2, 1}) {
            System.out.println("i = " + i);
            INDArray first = Nd4j.linspace(1, 3 * i, 3 * i).reshape('c', i, 3);      //To [1,3] or [2,3]
            INDArray second = Nd4j.linspace(4, 4 + 4 * i, 4 * i).reshape('c', i, 4);  //To [1,4] or [2,4]

            System.out.println("Shapes: " + Arrays.toString(first.shape()) + "\t" + Arrays.toString(second.shape()));

            SameDiff sd = SameDiff.create();
            SDVariable f = sd.var("in1", first);
            SDVariable s = sd.var("in2", second);

            MMulTranspose mt = MMulTranspose.builder()
                    .transposeA(true)
                    .transposeB(false)
                    .transposeResult(false)
                    .a(first)
                    .b(second)
                    .build();
            SDVariable mmul = sd.f().mmul(f, s, mt);
            sd.updateVariableNameAndReference(mmul, "mmul");

            INDArray out = sd.execAndEndResult();

            INDArray exp = first.transpose().mmul(second);
            assertEquals(exp, out);

            SDVariable loss = sd.standardDeviation(mmul, true);
            String err = OpValidation.validate(new TestCase(sd)
                    .expected(mmul.getVarName(), exp));

            assertNull(err);
        }
    }
}
