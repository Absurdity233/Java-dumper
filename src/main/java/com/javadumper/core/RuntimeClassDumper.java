package com.javadumper.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class RuntimeClassDumper {
    
    private static final Logger logger = LoggerFactory.getLogger(RuntimeClassDumper.class);
    private static final Map<String, byte[]> capturedClasses = new ConcurrentHashMap<>();
    
    private final Instrumentation instrumentation;
    
    public RuntimeClassDumper(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public byte[] captureClassBytecode(String className) throws Exception {
        String internalName = className.replace('.', '/');
        CountDownLatch latch = new CountDownLatch(1);
        
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                  ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (name != null && name.equals(internalName)) {
                    capturedClasses.put(className, classfileBuffer.clone());
                    latch.countDown();
                }
                return null;
            }
        };
        
        instrumentation.addTransformer(transformer, true);
        
        try {
            Class<?> targetClass = findLoadedClass(className);
            if (targetClass == null) {
                throw new ClassNotFoundException("Class not loaded: " + className);
            }
            
            if (!instrumentation.isModifiableClass(targetClass)) {
                throw new IllegalStateException("Class is not modifiable: " + className);
            }
            
            instrumentation.retransformClasses(targetClass);
            
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for class bytecode capture");
            }
            
            return capturedClasses.remove(className);
            
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    public String dumpClassToFile(String className, String outputDir) throws Exception {
        byte[] bytecode = captureClassBytecode(className);
        
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        
        String fileName = className.replace('.', '_') + ".class";
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, bytecode);
        
        logger.info("Class dumped to: {}", filePath);
        return filePath.toString();
    }

    public String decompileToBytecodeText(String className) throws Exception {
        byte[] bytecode = captureClassBytecode(className);
        return decompile(bytecode);
    }

    public String decompile(byte[] bytecode) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ClassReader reader = new ClassReader(bytecode);
        TraceClassVisitor tcv = new TraceClassVisitor(null, new Textifier(), pw);
        reader.accept(tcv, ClassReader.EXPAND_FRAMES);
        return sw.toString();
    }

    public void dumpAllClassesMatching(String pattern, String outputDir) throws Exception {
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        
        int count = 0;
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String className = clazz.getName();
            if (className.contains(pattern) && instrumentation.isModifiableClass(clazz)) {
                try {
                    byte[] bytecode = captureClassBytecode(className);
                    String fileName = className.replace('.', '_') + ".class";
                    Files.write(dir.resolve(fileName), bytecode);
                    count++;
                } catch (Exception e) {
                    logger.debug("Failed to dump {}: {}", className, e.getMessage());
                }
            }
        }
        
        logger.info("Dumped {} classes matching '{}' to {}", count, pattern, outputDir);
    }

    private Class<?> findLoadedClass(String className) {
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    public static String generateOutputDir() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "dumps/runtime_" + timestamp;
    }
}

