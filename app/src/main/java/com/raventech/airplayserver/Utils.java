package com.raventech.airplayserver;

import java.util.Collections;
import java.util.Map;

/**
 * Created by liyulong on 15/9/1.
 */
public class Utils
{

    /**
     * Map factory. Creates a Map from a list of keys and values
     *
     * @param keys_values key1, value1, key2, value2, ...
     * @return a map mapping key1 to value1, key2 to value2, ...
     */
    public static Map<String, String> map(final String... keys_values) {
        assert keys_values.length % 2 == 0;
        final Map<String, String> map = new java.util.HashMap<String, String>(keys_values.length / 2);
        for(int i=0; i < keys_values.length; i+=2)
            map.put(keys_values[i], keys_values[i+1]);
        return Collections.unmodifiableMap(map);
    }

    public static double sinc(double x) {
        return Math.sin(x) / x;
    }

    public static float sinc(float x) {
        return (float)Math.sin(x) / x;
    }
}
