package com.javadumper.core;

import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class HotSwapper {
    
    private static final Logger logger = LoggerFactory.getLogger(HotSwapper.class);
    
    private final Instrumentation instrumentation;
    private final Map<String, byte[]> originalBytecode = new HashMap<>();
    private final RuntimeClassDumper classDumper;

    public HotSwapper(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.classDumper = new RuntimeClassDumper(instrumentation);
    }

    public void redefineClass(String className, byte[] newBytecode) throws Exception {
        Class<?> targetClass = findClass(className);
        if (targetClass == null) {
            throw new ClassNotFoundException("Class not found: " + className);
        }
        
        if (!instrumentation.isRedefineClassesSupported()) {
            throw new UnsupportedOperationException("Class redefinition not supported");
        }
        
        if (!originalBytecode.containsKey(className)) {
            byte[] original = classDumper.captureClassBytecode(className);
            originalBytecode.put(className, original);
            logger.info("Saved original bytecode for: {}", className);
        }
        
        validateBytecode(newBytecode, className);
        
        ClassDefinition definition = new ClassDefinition(targetClass, newBytecode);
        instrumentation.redefineClasses(definition);
        
        logger.info("Class redefined: {}", className);
    }

    public void redefineFromFile(String className, Path classFile) throws Exception {
        byte[] bytecode = Files.readAllBytes(classFile);
        redefineClass(className, bytecode);
    }

    public void restoreOriginal(String className) throws Exception {
        byte[] original = originalBytecode.get(className);
        if (original == null) {
            throw new IllegalStateException("No original bytecode saved for: " + className);
        }
        
        Class<?> targetClass = findClass(className);
        ClassDefinition definition = new ClassDefinition(targetClass, original);
        instrumentation.redefineClasses(definition);
        
        originalBytecode.remove(className);
        logger.info("Restored original class: {}", className);
    }

    public void restoreAll() throws Exception {
        for (String className : originalBytecode.keySet()) {
            try {
                restoreOriginal(className);
            } catch (Exception e) {
                logger.error("Failed to restore {}: {}", className, e.getMessage());
            }
        }
    }

    public byte[] modifyMethod(String className, String methodName, MethodModifier modifier) throws Exception {
        byte[] original = classDumper.captureClassBytecode(className);
        
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(methodName)) {
                    return modifier.modify(mv, access, name, descriptor);
                }
                return mv;
            }
        };
        
        reader.accept(cv, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    public void addMethodTracing(String className, String methodName) throws Exception {
        byte[] modified = modifyMethod(className, methodName, (mv, access, name, desc) -> 
            new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                    mv.visitLdcInsn("[TRACE] Entering: " + className + "." + name);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                        "(Ljava/lang/String;)V", false);
                }
                
                @Override
                public void visitInsn(int opcode) {
                    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                        mv.visitLdcInsn("[TRACE] Exiting: " + className + "." + name);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                            "(Ljava/lang/String;)V", false);
                    }
                    super.visitInsn(opcode);
                }
            }
        );
        
        redefineClass(className, modified);
    }

    public void addMethodTiming(String className, String methodName) throws Exception {
        byte[] original = classDumper.captureClassBytecode(className);
        
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(methodName)) {
                    return new TimingMethodVisitor(mv, access, name, descriptor, className);
                }
                return mv;
            }
        };
        
        reader.accept(cv, ClassReader.EXPAND_FRAMES);
        redefineClass(className, writer.toByteArray());
    }

    private void validateBytecode(byte[] bytecode, String expectedClassName) throws Exception {
        ClassReader reader = new ClassReader(bytecode);
        String actualName = reader.getClassName().replace('/', '.');
        
        if (!actualName.equals(expectedClassName)) {
            throw new IllegalArgumentException(
                "Bytecode class name mismatch: expected " + expectedClassName + ", got " + actualName);
        }
    }

    private Class<?> findClass(String className) {
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    public boolean hasOriginal(String className) {
        return originalBytecode.containsKey(className);
    }

    public interface MethodModifier {
        MethodVisitor modify(MethodVisitor mv, int access, String name, String descriptor);
    }

    private static class TimingMethodVisitor extends MethodVisitor {
        private final String className;
        private final String methodName;
        private int startTimeVar;
        
        public TimingMethodVisitor(MethodVisitor mv, int access, String name, String desc, String className) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.methodName = name;
        }
        
        @Override
        public void visitCode() {
            super.visitCode();
            
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            startTimeVar = 1;
            mv.visitVarInsn(Opcodes.LSTORE, startTimeVar);
        }
        
        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
                mv.visitVarInsn(Opcodes.LLOAD, startTimeVar);
                mv.visitInsn(Opcodes.LSUB);
                mv.visitVarInsn(Opcodes.LSTORE, startTimeVar + 2);
                
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitLdcInsn("[TIMING] " + className + "." + methodName + " took ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(Opcodes.LLOAD, startTimeVar + 2);
                mv.visitInsn(Opcodes.L2D);
                mv.visitLdcInsn(1000000.0);
                mv.visitInsn(Opcodes.DDIV);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(D)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(" ms");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                    "(Ljava/lang/String;)V", false);
            }
            super.visitInsn(opcode);
        }
    }
}

