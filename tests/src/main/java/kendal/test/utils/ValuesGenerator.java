package kendal.test.utils;

import java.util.ArrayList;
import java.util.List;

public class ValuesGenerator {
    /**
     * Returns a number.
     */
    public static int i() {
        return 123;
    }

    /**
     * Returns a string.
     */
    public static String s() {
        return "str#ing%$.";
    }

    /**
     * Returns a list.
     */
    public static List<Integer> l() {
        return new ArrayList<>();
    }
}
