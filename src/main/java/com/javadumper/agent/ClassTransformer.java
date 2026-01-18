package com.javadumper.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class ClassTransformer implements ClassFileTransformer {
    
    private final Set<String> targetClasses;
    private final Set<String> targetMethods;
    private final TransformMode mode;
    
    public enum TransformMode {
        TRACE,          // 方法追踪
        TIMING,         // 性能计时
        PARAMETER_LOG,  // 参数日志
        RETURN_LOG,     // 返回值日志
        EXCEPTION_LOG   // 异常日志
    }
    
    public ClassTransformer(Set<String> targetClasses, Set<String> targetMethods, TransformMode mode) {
        this.targetClasses = targetClasses;
        this.targetMethods = targetMethods;
        this.mode = mode;
    }

    public static ClassTransformer createTracer(String className, String methodName) {
        Set<String> classes = new HashSet<>();
        classes.add(className.replace('.', '/'));
        
        Set<String> methods = new HashSet<>();
        if (methodName != null && !methodName.isEmpty()) {
            methods.add(methodName);
        }
        
        return new ClassTransformer(classes, methods, TransformMode.TRACE);
    }

    public static ClassTransformer createTimer(String className, String methodName) {
        Set<String> classes = new HashSet<>();
        classes.add(className.replace('.', '/'));
        
        Set<String> methods = new HashSet<>();
        if (methodName != null && !methodName.isEmpty()) {
            methods.add(methodName);
        }
        
        return new ClassTransformer(classes, methods, TransformMode.TIMING);
    }
    
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        
        if (className == null || !targetClasses.contains(className)) {
            return null;
        }
        
        try {
            System.out.println("[ClassTransformer] Transforming: " + className);
            
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new TransformingClassVisitor(writer, targetMethods, mode);
            
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            
            return writer.toByteArray();
            
        } catch (Exception e) {
            System.err.println("[ClassTransformer] Error transforming " + className + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static class TransformingClassVisitor extends ClassVisitor {
        
        private final Set<String> targetMethods;
        private final TransformMode mode;
        private String className;
        
        public TransformingClassVisitor(ClassVisitor cv, Set<String> targetMethods, TransformMode mode) {
            super(Opcodes.ASM9, cv);
            this.targetMethods = targetMethods;
            this.mode = mode;
        }
        
        @Override
        public void visit(int version, int access, String name, String signature, 
                         String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                        String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            if (name.equals("<init>") || name.equals("<clinit>")) {
                return mv;
            }
            
            if (!targetMethods.isEmpty() && !targetMethods.contains(name)) {
                return mv;
            }
            
            return new TransformingMethodVisitor(mv, access, name, descriptor, className, mode);
        }
    }

    private static class TransformingMethodVisitor extends AdviceAdapter {
        
        private final String className;
        private final String methodName;
        private final TransformMode mode;
        private int startTimeLocal = -1;
        
        public TransformingMethodVisitor(MethodVisitor mv, int access, String name, 
                                        String descriptor, String className, TransformMode mode) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.className = className;
            this.methodName = name;
            this.mode = mode;
        }
        
        @Override
        protected void onMethodEnter() {
            switch (mode) {
                case TRACE:
                    addTraceEntry();
                    break;
                case TIMING:
                    addTimingStart();
                    break;
                case PARAMETER_LOG:
                    addParameterLogging();
                    break;
                default:
                    addTraceEntry();
            }
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            switch (mode) {
                case TRACE:
                    addTraceExit(opcode);
                    break;
                case TIMING:
                    addTimingEnd();
                    break;
                case RETURN_LOG:
                    if (opcode != ATHROW) {
                        addReturnLogging(opcode);
                    }
                    break;
                default:
                    addTraceExit(opcode);
            }
        }
        
        private void addTraceEntry() {
            // System.out.println("[TRACE] >> ClassName.methodName")
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[TRACE] >> " + className.replace('/', '.') + "." + methodName);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                "(Ljava/lang/String;)V", false);
        }
        
        private void addTraceExit(int opcode) {
            String exitType = opcode == ATHROW ? "EXCEPTION" : "RETURN";
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[TRACE] << " + className.replace('/', '.') + "." + methodName + " (" + exitType + ")");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                "(Ljava/lang/String;)V", false);
        }
        
        private void addTimingStart() {
            // long startTime = System.nanoTime();
            startTimeLocal = newLocal(Type.LONG_TYPE);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeLocal);
        }
        
        private void addTimingEnd() {
            // long duration = System.nanoTime() - startTime;
            // System.out.println("[TIMING] ClassName.methodName took " + (duration / 1000000.0) + " ms");
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeLocal);
            mv.visitInsn(LSUB);
            
            int durationLocal = newLocal(Type.LONG_TYPE);
            mv.visitVarInsn(LSTORE, durationLocal);
            
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            
            mv.visitLdcInsn("[TIMING] " + className.replace('/', '.') + "." + methodName + " took ");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", 
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            
            mv.visitVarInsn(LLOAD, durationLocal);
            mv.visitInsn(L2D);
            mv.visitLdcInsn(1000000.0);
            mv.visitInsn(DDIV);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", 
                "(D)Ljava/lang/StringBuilder;", false);
            
            mv.visitLdcInsn(" ms");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", 
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", 
                "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                "(Ljava/lang/String;)V", false);
        }
        
        private void addParameterLogging() {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[PARAMS] " + className.replace('/', '.') + "." + methodName + " called");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                "(Ljava/lang/String;)V", false);
        }
        
        private void addReturnLogging(int opcode) {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[RETURN] " + className.replace('/', '.') + "." + methodName);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                "(Ljava/lang/String;)V", false);
        }
    }
}

