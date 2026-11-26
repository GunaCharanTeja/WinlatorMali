package com.winlator.cmod.perf;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Pure-reflection driver for Samsung Galaxy Performance SDK (com.samsung.sdk.sperf).
 * Safe on all devices; no compile-time jar requirement.
 */
public class SamsungSPerfDriver {
    private static final String TAG = "SamsungSPerf";
    private static final String SPERF_CLASS = "com.samsung.sdk.sperf.SPerf";
    private static final String PERFORMANCE_MANAGER_CLASS = "com.samsung.sdk.sperf.PerformanceManager";
    private static final String CUSTOM_PARAMS_CLASS = "com.samsung.sdk.sperf.CustomParams";

    private static final int TYPE_CPU_MIN = 0;
    private static final int TYPE_CPU_MAX = 1;
    private static final int TYPE_GPU_MIN = 2;
    private static final int TYPE_GPU_MAX = 3;
    private static final int TYPE_BUS_MIN = 4;
    private static final int TYPE_BUS_MAX = 5;
    private static final int PERSIST = 0;

    private Object performanceManager = null;
    private Constructor<?> customParamsCtor = null;
    private Method addMethod = null;
    private Method startMethod = null;
    private Method stopMethod = null;
    private boolean available = false;

    public SamsungSPerfDriver(Context context) {
        if (!isSamsungDevice() || context == null) {
            return;
        }

        try {
            Context appContext = context.getApplicationContext();
            Class<?> sperfClass = Class.forName(SPERF_CLASS);
            Class<?> pmClass = Class.forName(PERFORMANCE_MANAGER_CLASS);
            Class<?> cpClass = Class.forName(CUSTOM_PARAMS_CLASS);

            try {
                Method debugMethod = sperfClass.getMethod("setDebugModeEnabled", boolean.class);
                debugMethod.invoke(null, false);
            } catch (Throwable ignored) {}

            Method initMethod = sperfClass.getMethod("initialize", Context.class);
            Object initResult = initMethod.invoke(null, appContext);
            if (!(initResult instanceof Boolean) || !((Boolean) initResult)) {
                Log.d(TAG, "Samsung SPerf initialize returned false");
                return;
            }

            Method getInstanceMethod = pmClass.getMethod("getInstance");
            Object pm = getInstanceMethod.invoke(null);
            if (pm == null) {
                Log.d(TAG, "Samsung SPerf PerformanceManager is null");
                return;
            }

            performanceManager = pm;
            customParamsCtor = cpClass.getDeclaredConstructor();
            customParamsCtor.setAccessible(true);
            addMethod = cpClass.getMethod("add", int.class, int.class, int.class);
            startMethod = pmClass.getMethod("start", cpClass);
            stopMethod = pmClass.getMethod("stop");

            available = true;
            Log.i(TAG, "Samsung Galaxy Performance SDK initialized successfully");
        } catch (Throwable e) {
            available = false;
            Log.d(TAG, "Samsung SPerf unavailable: " + e.getMessage());
        }
    }

    public boolean isSupported() {
        return available;
    }

    public boolean startBoost() {
        if (!available || performanceManager == null || customParamsCtor == null || startMethod == null) {
            return false;
        }
        try {
            Object params = customParamsCtor.newInstance();
            // Request high CPU, GPU, Bus performance
            if (addMethod != null) {
                addMethod.invoke(params, TYPE_CPU_MIN, -1, PERSIST);
                addMethod.invoke(params, TYPE_GPU_MIN, -1, PERSIST);
                addMethod.invoke(params, TYPE_BUS_MIN, -1, PERSIST);
            }
            startMethod.invoke(performanceManager, params);
            Log.d(TAG, "Samsung SPerf boost applied");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to apply Samsung SPerf boost: " + e.getMessage());
            return false;
        }
    }

    public void stopBoost() {
        if (!available || performanceManager == null || stopMethod == null) return;
        try {
            stopMethod.invoke(performanceManager);
            Log.d(TAG, "Samsung SPerf boost stopped");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to stop Samsung SPerf boost: " + e.getMessage());
        }
    }

    public static boolean isSamsungDevice() {
        return Build.MANUFACTURER != null && Build.MANUFACTURER.equalsIgnoreCase("samsung");
    }
}
