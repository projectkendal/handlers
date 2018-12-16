package kendal.experiments;

import javax.annotation.Generated;

import kendal.annotations.Clone;
import kendal.experiments.subpackage.TestClassTransformer;

public class CloneTest {
    public static final String CLONE_METHOD_NAME = "transformedMethod";

    @Clone(transformer = TestClassTransformer.class, methodName=CLONE_METHOD_NAME, onMethod={@Generated("whatever")})
    public <T> String aMethod(T param1, int param2) throws Exception {
        if (param1.equals("abc")) throw new Exception();
        return param1.toString() + param2;
    }

}
