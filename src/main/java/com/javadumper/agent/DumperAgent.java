package com.javadumper.agent;

import com.javadumper.core.HotSwapper;
import com.javadumper.core.RuntimeClassDumper;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class DumperAgent {
    
    private static Instrumentation instrumentation;
    private static final Map<String, byte[]> originalClasses = new ConcurrentHashMap<>();
    private static final List<ClassFileTransformer> transformers = new ArrayList<>();
    private static AgentServer server;
    private static HotSwapper hotSwapper;
    private static RuntimeClassDumper runtimeDumper;

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[DumperAgent] Premain loaded with args: " + agentArgs);
        initialize(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[DumperAgent] Agentmain loaded with args: " + agentArgs);
        initialize(agentArgs, inst);
    }

    private static void initialize(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        hotSwapper = new HotSwapper(inst);
        runtimeDumper = new RuntimeClassDumper(inst);
        
        Map<String, String> args = parseArgs(agentArgs);
        
        String command = args.get("cmd");
        if (command != null) {
            executeCommand(command, args);
        }
        
        String serverPort = args.get("server");
        if (serverPort != null) {
            startServer(Integer.parseInt(serverPort));
        }
        
        System.out.println("[DumperAgent] Agent initialized successfully");
        System.out.println("[DumperAgent] Can redefine classes: " + inst.isRedefineClassesSupported());
        System.out.println("[DumperAgent] Can retransform classes: " + inst.isRetransformClassesSupported());
    }

    private static void startServer(int port) {
        try {
            server = new AgentServer(port, instrumentation);
            server.start();
        } catch (Exception e) {
            System.err.println("[DumperAgent] Failed to start server: " + e.getMessage());
        }
    }

    private static void executeCommand(String command, Map<String, String> args) {
        switch (command.toLowerCase()) {
            case "list-classes":
                listLoadedClasses(args.get("filter"));
                break;
            case "dump-class":
                dumpClass(args.get("class"));
                break;
            case "dump-runtime":
                dumpRuntimeClass(args.get("class"), args.get("output"));
                break;
            case "trace":
                enableTracing(args.get("class"), args.get("method"));
                break;
            case "add-timing":
                addTiming(args.get("class"), args.get("method"));
                break;
            case "info":
                printAgentInfo();
                break;
            default:
                System.out.println("[DumperAgent] Unknown command: " + command);
        }
    }

    private static void dumpRuntimeClass(String className, String outputDir) {
        if (className == null) {
            System.out.println("[DumperAgent] Class name required");
            return;
        }
        try {
            String dir = outputDir != null ? outputDir : RuntimeClassDumper.generateOutputDir();
            String path = runtimeDumper.dumpClassToFile(className, dir);
            System.out.println("[DumperAgent] Runtime class dumped to: " + path);
        } catch (Exception e) {
            System.err.println("[DumperAgent] Dump failed: " + e.getMessage());
        }
    }

    private static void addTiming(String className, String methodName) {
        if (className == null || methodName == null) {
            System.out.println("[DumperAgent] Class and method name required");
            return;
        }
        try {
            hotSwapper.addMethodTiming(className, methodName);
            System.out.println("[DumperAgent] Timing added to: " + className + "." + methodName);
        } catch (Exception e) {
            System.err.println("[DumperAgent] Add timing failed: " + e.getMessage());
        }
    }

    private static Map<String, String> parseArgs(String agentArgs) {
        Map<String, String> args = new HashMap<>();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return args;
        }
        
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex).trim();
                String value = pair.substring(eqIndex + 1).trim();
                args.put(key, value);
            } else {
                args.put(pair.trim(), "true");
            }
        }
        
        return args;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static void listLoadedClasses(String filter) {
        if (instrumentation == null) {
            System.out.println("[DumperAgent] Agent not initialized");
            return;
        }
        
        Class<?>[] classes = instrumentation.getAllLoadedClasses();
        System.out.println("\n========== Loaded Classes ==========");
        System.out.println("Total loaded classes: " + classes.length);
        
        int count = 0;
        for (Class<?> clazz : classes) {
            String className = clazz.getName();
            if (filter == null || className.contains(filter)) {
                System.out.printf("  [%s] %s (modifiable: %s)%n", 
                    clazz.isInterface() ? "I" : "C",
                    className,
                    instrumentation.isModifiableClass(clazz));
                count++;
            }
        }
        
        System.out.println("Matching classes: " + count);
        System.out.println("=====================================\n");
    }

    public static List<ClassInfo> getLoadedClasses(String filter) {
        List<ClassInfo> result = new ArrayList<>();
        
        if (instrumentation == null) {
            return result;
        }
        
        Class<?>[] classes = instrumentation.getAllLoadedClasses();
        for (Class<?> clazz : classes) {
            String className = clazz.getName();
            if (filter == null || className.contains(filter)) {
                result.add(new ClassInfo(
                    className,
                    clazz.isInterface(),
                    instrumentation.isModifiableClass(clazz),
                    clazz.getClassLoader() != null ? clazz.getClassLoader().toString() : "Bootstrap"
                ));
            }
        }
        
        return result;
    }

    public static void dumpClass(String className) {
        if (className == null || className.isEmpty()) {
            System.out.println("[DumperAgent] Class name required");
            return;
        }
        
        System.out.println("[DumperAgent] Dumping class: " + className);
        
        ClassDumpTransformer transformer = new ClassDumpTransformer(className);
        instrumentation.addTransformer(transformer, true);
        
        try {
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    instrumentation.retransformClasses(clazz);
                    break;
                }
            }
        } catch (UnmodifiableClassException e) {
            System.out.println("[DumperAgent] Cannot retransform class: " + e.getMessage());
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    public static void enableTracing(String className, String methodName) {
        if (className == null) {
            System.out.println("[DumperAgent] Class name required for tracing");
            return;
        }
        
        System.out.println("[DumperAgent] Enabling tracing for: " + className + 
            (methodName != null ? "." + methodName : ".*"));
        
        MethodTraceTransformer transformer = new MethodTraceTransformer(className, methodName);
        instrumentation.addTransformer(transformer, true);
        transformers.add(transformer);
        
        try {
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    instrumentation.retransformClasses(clazz);
                    break;
                }
            }
        } catch (UnmodifiableClassException e) {
            System.out.println("[DumperAgent] Cannot retransform class: " + e.getMessage());
        }
    }

    public static void disableAllTracing() {
        for (ClassFileTransformer transformer : transformers) {
            instrumentation.removeTransformer(transformer);
        }
        transformers.clear();
        System.out.println("[DumperAgent] All tracing disabled");
    }

    public static void redefineClass(String className, byte[] bytecode) throws Exception {
        if (instrumentation == null) {
            throw new IllegalStateException("Agent not initialized");
        }
        
        Class<?> targetClass = null;
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                targetClass = clazz;
                break;
            }
        }
        
        if (targetClass == null) {
            throw new ClassNotFoundException("Class not found: " + className);
        }
        
        if (!originalClasses.containsKey(className)) {
            System.out.println("[DumperAgent] Saving original bytecode for: " + className);
        }
        
        java.lang.instrument.ClassDefinition definition = 
            new java.lang.instrument.ClassDefinition(targetClass, bytecode);
        
        instrumentation.redefineClasses(definition);
        System.out.println("[DumperAgent] Class redefined successfully: " + className);
    }

    public static void printAgentInfo() {
        System.out.println("\n========== Dumper Agent Info ==========");
        System.out.println("Status: " + (instrumentation != null ? "Active" : "Not Initialized"));
        
        if (instrumentation != null) {
            System.out.println("Capabilities:");
            System.out.println("  - Redefine Classes: " + instrumentation.isRedefineClassesSupported());
            System.out.println("  - Retransform Classes: " + instrumentation.isRetransformClassesSupported());
            System.out.println("  - Native Method Prefix: " + instrumentation.isNativeMethodPrefixSupported());
            System.out.println("Active Transformers: " + transformers.size());
            System.out.println("Loaded Classes: " + instrumentation.getAllLoadedClasses().length);
        }
        
        System.out.println("=========================================\n");
    }

    public static class ClassInfo {
        public final String name;
        public final boolean isInterface;
        public final boolean isModifiable;
        public final String classLoader;
        
        public ClassInfo(String name, boolean isInterface, boolean isModifiable, String classLoader) {
            this.name = name;
            this.isInterface = isInterface;
            this.isModifiable = isModifiable;
            this.classLoader = classLoader;
        }
        
        @Override
        public String toString() {
            return String.format("%s [%s, modifiable=%s, loader=%s]",
                name, isInterface ? "interface" : "class", isModifiable, classLoader);
        }
    }

    private static class ClassDumpTransformer implements ClassFileTransformer {
        private final String targetClassName;
        
        public ClassDumpTransformer(String className) {
            this.targetClassName = className.replace('.', '/');
        }
        
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className != null && className.equals(targetClassName)) {
                try {
                    String fileName = className.replace('/', '_') + ".class";
                    java.nio.file.Files.write(
                        java.nio.file.Paths.get("dumps", fileName),
                        classfileBuffer);
                    System.out.println("[DumperAgent] Class dumped to: dumps/" + fileName);
                } catch (Exception e) {
                    System.out.println("[DumperAgent] Failed to dump class: " + e.getMessage());
                }
            }
            return null;
        }
    }

    private static class MethodTraceTransformer implements ClassFileTransformer {
        private final String targetClassName;
        private final String targetMethodName;
        
        public MethodTraceTransformer(String className, String methodName) {
            this.targetClassName = className.replace('.', '/');
            this.targetMethodName = methodName;
        }
        
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className != null && className.equals(targetClassName)) {
                System.out.println("[DumperAgent] Transforming class for tracing: " + className);
            }
            return null;
        }
    }
}

