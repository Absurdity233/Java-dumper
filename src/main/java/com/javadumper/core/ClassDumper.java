package com.javadumper.core;

import com.sun.tools.attach.VirtualMachine;
import org.objectweb.asm.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class ClassDumper {
    
    private static final Logger logger = LoggerFactory.getLogger(ClassDumper.class);
    
    private final JvmProcessManager processManager;
    
    public ClassDumper() {
        this.processManager = new JvmProcessManager();
    }

    public byte[] extractClassFromJar(String jarPath, String className) throws Exception {
        String classPath = className.replace('.', '/') + ".class";
        
        try (JarFile jarFile = new JarFile(jarPath)) {
            JarEntry entry = jarFile.getJarEntry(classPath);
            if (entry == null) {
                throw new ClassNotFoundException("Class not found in JAR: " + className);
            }
            
            try (InputStream is = jarFile.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    public String decompileClass(byte[] bytecode) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        
        ClassReader reader = new ClassReader(bytecode);
        TraceClassVisitor tcv = new TraceClassVisitor(null, new Textifier(), pw);
        reader.accept(tcv, ClassReader.EXPAND_FRAMES);
        
        return sw.toString();
    }

    public ClassMetadata getClassMetadata(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassMetadataVisitor visitor = new ClassMetadataVisitor();
        reader.accept(visitor, 0);
        return visitor.getMetadata();
    }

    public String saveClassBytecode(byte[] bytecode, String outputPath) throws Exception {
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytecode);
        logger.info("Class bytecode saved to: {}", outputPath);
        return outputPath;
    }

    public String saveDecompiledClass(byte[] bytecode, String outputPath) throws Exception {
        String decompiled = decompileClass(bytecode);
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, decompiled);
        logger.info("Decompiled class saved to: {}", outputPath);
        return outputPath;
    }

    public String compareClasses(byte[] bytecode1, byte[] bytecode2) {
        String decomp1 = decompileClass(bytecode1);
        String decomp2 = decompileClass(bytecode2);
        
        StringBuilder diff = new StringBuilder();
        diff.append("========== Class Comparison ==========\n\n");
        
        String[] lines1 = decomp1.split("\n");
        String[] lines2 = decomp2.split("\n");
        
        int maxLines = Math.max(lines1.length, lines2.length);
        
        for (int i = 0; i < maxLines; i++) {
            String line1 = i < lines1.length ? lines1[i] : "";
            String line2 = i < lines2.length ? lines2[i] : "";
            
            if (!line1.equals(line2)) {
                diff.append(String.format("Line %d:%n", i + 1));
                diff.append("  - ").append(line1).append("\n");
                diff.append("  + ").append(line2).append("\n");
            }
        }
        
        diff.append("\n=======================================\n");
        return diff.toString();
    }

    public List<String> listClassesInJar(String jarPath) throws Exception {
        List<String> classes = new ArrayList<>();
        
        try (JarFile jarFile = new JarFile(jarPath)) {
            jarFile.stream()
                .filter(entry -> entry.getName().endsWith(".class"))
                .forEach(entry -> {
                    String className = entry.getName()
                        .replace('/', '.')
                        .replace(".class", "");
                    classes.add(className);
                });
        }
        
        return classes;
    }

    public String generateDumpDirectory(String pid) {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("dumps/classes_%s_%s", pid, timestamp);
    }

    public static class ClassMetadata {
        private String name;
        private String superName;
        private List<String> interfaces = new ArrayList<>();
        private List<FieldInfo> fields = new ArrayList<>();
        private List<MethodInfo> methods = new ArrayList<>();
        private int access;
        private String sourceFile;
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getSuperName() { return superName; }
        public void setSuperName(String superName) { this.superName = superName; }
        
        public List<String> getInterfaces() { return interfaces; }
        public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }
        
        public List<FieldInfo> getFields() { return fields; }
        public void addField(FieldInfo field) { this.fields.add(field); }
        
        public List<MethodInfo> getMethods() { return methods; }
        public void addMethod(MethodInfo method) { this.methods.add(method); }
        
        public int getAccess() { return access; }
        public void setAccess(int access) { this.access = access; }
        
        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
        
        public boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }
        
        public boolean isAbstract() {
            return (access & Opcodes.ACC_ABSTRACT) != 0;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Class: ").append(name.replace('/', '.')).append("\n");
            sb.append("  Super: ").append(superName != null ? superName.replace('/', '.') : "none").append("\n");
            
            if (!interfaces.isEmpty()) {
                sb.append("  Interfaces: ");
                interfaces.forEach(i -> sb.append(i.replace('/', '.')).append(", "));
                sb.append("\n");
            }
            
            sb.append("  Fields: ").append(fields.size()).append("\n");
            for (FieldInfo field : fields) {
                sb.append("    - ").append(field).append("\n");
            }
            
            sb.append("  Methods: ").append(methods.size()).append("\n");
            for (MethodInfo method : methods) {
                sb.append("    - ").append(method).append("\n");
            }
            
            return sb.toString();
        }
    }

    public static class FieldInfo {
        public final String name;
        public final String descriptor;
        public final int access;
        
        public FieldInfo(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }
        
        @Override
        public String toString() {
            return String.format("%s %s", descriptor, name);
        }
    }

    public static class MethodInfo {
        public final String name;
        public final String descriptor;
        public final int access;
        
        public MethodInfo(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }
        
        @Override
        public String toString() {
            return String.format("%s%s", name, descriptor);
        }
    }

    private static class ClassMetadataVisitor extends ClassVisitor {
        private final ClassMetadata metadata = new ClassMetadata();
        
        public ClassMetadataVisitor() {
            super(Opcodes.ASM9);
        }
        
        @Override
        public void visit(int version, int access, String name, String signature, 
                         String superName, String[] interfaces) {
            metadata.setName(name);
            metadata.setSuperName(superName);
            metadata.setAccess(access);
            
            if (interfaces != null) {
                for (String iface : interfaces) {
                    metadata.getInterfaces().add(iface);
                }
            }
        }
        
        @Override
        public void visitSource(String source, String debug) {
            metadata.setSourceFile(source);
        }
        
        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, 
                                       String signature, Object value) {
            metadata.addField(new FieldInfo(name, descriptor, access));
            return null;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                        String signature, String[] exceptions) {
            metadata.addMethod(new MethodInfo(name, descriptor, access));
            return null;
        }
        
        public ClassMetadata getMetadata() {
            return metadata;
        }
    }
}

