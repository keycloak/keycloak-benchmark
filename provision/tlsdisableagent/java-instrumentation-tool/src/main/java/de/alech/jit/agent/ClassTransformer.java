package de.alech.jit.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import javassist.*;

import java.util.LinkedList;

public class ClassTransformer implements ClassFileTransformer {
    /** The internal form class name of the class to transform */
    private String targetClassName;
    /** The class loader of the class we want to transform */
    private ClassLoader targetClassLoader;
    private LinkedList<Hook> hooks;
    private CtClass[] params;

    ClassTransformer(
            String targetClassName,
            ClassLoader targetClassLoader,
            LinkedList<Hook> hooks) {
        System.out.println("[Agent] ClassTransformer constructor, " + targetClassName + ", " + targetClassLoader);
        this.targetClassName = targetClassName;
        this.targetClassLoader = targetClassLoader;
        this.hooks = hooks;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        //System.out.println("[Agent] ClassTransformer.transform(), " + className + ", " + loader);
        byte[] byteCode = classfileBuffer;
        String finalTargetClassName = this.targetClassName
                .replaceAll("\\.", "/");
        if (! className.equals(finalTargetClassName)) {
            // not what we are looking for, let's just return the unmodified byte code.
            return byteCode;
        }

        if (loader == null || loader.equals(targetClassLoader)) {
            // modify the class by adding all the hooks in the corresponding places
            try {
                ClassPool cp = ClassPool.getDefault();
                cp.insertClassPath(new ClassClassPath(this.getClass()));
                CtClass cc = cp.get(targetClassName);
                String shortClassName = targetClassName.substring(targetClassName.lastIndexOf(".") + 1);
                CtBehavior m;
                for (Hook h : this.hooks) {
                    System.out.println("[Agent] Transforming class " + this.targetClassName + ", method " + h.method + ", param types " + String.join(";", h.strParams));
                    params = strParamsToCtClassParams(h.strParams);
                    if (shortClassName.equals(h.method)) {
                        m = cc.getDeclaredConstructor(params);
                    } else {
                        m = cc.getDeclaredMethod(
                                h.method,
                                params
                        );
                    }
                    if (h.where == WhereToPatch.INSERTBEFORE) {
                        System.out.println("[Agent] adding code before " + h.method);
                        m.insertBefore(h.codePatch);
                    }
                }
                byteCode = cc.toBytecode();
                cc.detach();
            } catch (Exception e) {
                System.err.println("[Agent] error while transforming: " + e);
            }
        }
        return byteCode;
    }

    /**
     * Convert an array of parameter type class strings (e.g. ["java.lang.String", "java.lang.String"]) into
     * the corresponding CtClass objects, so it can be used with jassist's CtClass.getDeclaredMethod() method
     * @param strParams an array of strings representing types for the parameters for a method
     * @return an array of CtClass objects corresponding to those strings
     */
    private static CtClass[] strParamsToCtClassParams(String[] strParams) {
        ClassPool cp = ClassPool.getDefault();
        CtClass[] params = new CtClass[strParams.length];
        for (int i = 0; i < strParams.length; i++) {
            try {
                params[i] = cp.getCtClass(strParams[i]);
            } catch (NotFoundException e) {
                System.err.println("[Agent] parameter class not found: " + e);
            }
        }
        return params;
    }
}
