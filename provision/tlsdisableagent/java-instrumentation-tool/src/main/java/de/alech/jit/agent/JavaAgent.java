package de.alech.jit.agent;

import java.lang.instrument.Instrumentation;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class JavaAgent {
    public static void agentmain(String agentArgs, Instrumentation inst) {
        readHooksAndTransform(agentArgs, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        readHooksAndTransform(agentArgs, inst);
    }

    private static void readHooksAndTransform(String agentArgs, Instrumentation inst) {
        if (agentArgs == null) {
            agentArgs = System.getProperty("agentargs");
        }
        if ((agentArgs != null) && agentArgs.equals("showloadedclasses")) {
            System.out.println("[Agent] loaded classes:");
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                System.out.println("[Agent]   - " + clazz.getName());
            }
        }

        // a map to map class name to a list of hooks that will be applied to it
        Map<String, LinkedList<Hook>> hooksByClass = new HashMap();
        try {
            InputStream hookStream = JavaAgent.class.getResourceAsStream("/hooks.txt");
            BufferedReader hookReader = new BufferedReader(new InputStreamReader(hookStream));
            while (hookReader.ready()) {
                String line = hookReader.readLine();
                Hook hook = new Hook(line);
                LinkedList hooksToApply = hooksByClass.getOrDefault(hook.classToTransform, new LinkedList());
                hooksToApply.add(hook);
                hooksByClass.put(hook.classToTransform, hooksToApply);
            }
        } catch (IOException e) {
            System.err.println("Problem reading hook definition: " + e);
        }
        for (Map.Entry<String, LinkedList<Hook>> entry : hooksByClass.entrySet()) {
            String classToTransform = entry.getKey();
            LinkedList<Hook> hooks  = entry.getValue();
            transformClass(classToTransform, hooks, inst);
        }
    }

    private static void transformClass(
            String className,
            LinkedList<Hook> hooks,
            Instrumentation instrumentation) {
        Class<?> targetCls;
        ClassLoader targetClassLoader;
        // see if we can get the class using forName
        try {
            targetCls = Class.forName(className);
            targetClassLoader = targetCls.getClassLoader();
            transform(targetCls, targetClassLoader, hooks, instrumentation);
            return;
        } catch (Exception ex) {
            System.err.println("Class not found with Class.forName");
        }
        // otherwise iterate over all loaded classes and find what we want
        for(Class<?> clazz: instrumentation.getAllLoadedClasses()) {
            if(clazz.getName().equals(className)) {
                targetCls = clazz;
                targetClassLoader = targetCls.getClassLoader();
                transform(targetCls, targetClassLoader, hooks, instrumentation);
                return;
            }
        }
        throw new RuntimeException("Failed to find class [" + className + "]");
    }

    private static void transform(
            Class<?> clazz,
            ClassLoader classLoader,
            LinkedList<Hook> hooks,
            Instrumentation instrumentation) {
        for (Hook h : hooks) {
            System.out.println("[Agent] transforming " + clazz.getName() + "." + h.method);
        }
        ClassTransformer dt = new ClassTransformer(
                clazz.getName(),
                classLoader,
                hooks);
        instrumentation.addTransformer(dt, true);
        try {
            instrumentation.retransformClasses(clazz);
        } catch (Exception ex) {
            throw new RuntimeException(
                    "Transform failed for: [" + clazz.getName() + "]", ex);
        }
    }
}
