package kendal.test.positive.clone.transformerWithDependencies;

import static kendal.test.positive.utils.ValuesGenerator.i;
import static kendal.test.positive.utils.ValuesGenerator.s;

import java.util.List;

import kendal.annotations.Clone;

/*
 * @test
 * @library /positive/utils/
 * @build ValuesGenerator
 * @build AbstractClassWithTransformMethod
 * @build TransformerWithDependencies
 * @compile TestClone.java
 */
@SuppressWarnings("unused")
public class TestClone {

    @Clone(transformer = TransformerWithDependencies.class, methodName = "newMethod")
    int method(int param1, String param2) {
        return param1 + param2.length();
    }

    // ### Test cases ###

    List<Integer> shouldBeAbleToUseGeneratedMethod() {
        return newMethod(i(), s());
    }

}