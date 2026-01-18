package com.javadumper.agent;

import com.javadumper.core.HotSwapper;
import com.javadumper.core.RuntimeClassDumper;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class AgentServer {
    
    private final int port;
    private final Instrumentation instrumentation;
    private final RuntimeClassDumper classDumper;
    private final HotSwapper hotSwapper;
    
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AgentServer(int port, Instrumentation instrumentation) {
        this.port = port;
        this.instrumentation = instrumentation;
        this.classDumper = new RuntimeClassDumper(instrumentation);
        this.hotSwapper = new HotSwapper(instrumentation);
    }

    public void start() throws IOException {
        if (running.get()) return;
        
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running.set(true);
        
        System.out.println("[AgentServer] Started on port " + port);
        
        new Thread(() -> {
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[AgentServer] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "AgentServer-Acceptor").start();
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
            if (executor != null) executor.shutdownNow();
        } catch (IOException e) {
            System.err.println("[AgentServer] Stop error: " + e.getMessage());
        }
    }

    private void handleClient(Socket client) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                String response = processCommand(line.trim());
                writer.println(response);
                writer.println("__END__");
            }
            
        } catch (Exception e) {
            System.err.println("[AgentServer] Client error: " + e.getMessage());
        }
    }

    private String processCommand(String command) {
        if (command.isEmpty()) return "OK";
        
        String[] parts = command.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        try {
            switch (cmd) {
                case "list-classes":
                    return listClasses(args);
                case "dump-class":
                    return dumpClass(args);
                case "decompile":
                    return decompileClass(args);
                case "add-trace":
                    return addTrace(args);
                case "add-timing":
                    return addTiming(args);
                case "restore":
                    return restoreClass(args);
                case "restore-all":
                    hotSwapper.restoreAll();
                    return "All classes restored";
                case "info":
                    return getInfo();
                case "gc":
                    System.gc();
                    return "GC triggered";
                case "exit":
                    return "BYE";
                default:
                    return "Unknown command: " + cmd;
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String listClasses(String filter) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (filter.isEmpty() || name.contains(filter)) {
                boolean modifiable = instrumentation.isModifiableClass(clazz);
                sb.append(String.format("[%s] %s%n", modifiable ? "M" : " ", name));
                count++;
                if (count >= 500) {
                    sb.append("... (truncated, use more specific filter)\n");
                    break;
                }
            }
        }
        
        sb.append(String.format("Total: %d classes", count));
        return sb.toString();
    }

    private String dumpClass(String className) throws Exception {
        if (className.isEmpty()) return "Usage: dump-class <className>";
        
        String outputDir = RuntimeClassDumper.generateOutputDir();
        String path = classDumper.dumpClassToFile(className, outputDir);
        return "Dumped to: " + path;
    }

    private String decompileClass(String className) throws Exception {
        if (className.isEmpty()) return "Usage: decompile <className>";
        return classDumper.decompileToBytecodeText(className);
    }

    private String addTrace(String target) throws Exception {
        if (target.isEmpty()) return "Usage: add-trace <className.methodName>";
        
        int dotIdx = target.lastIndexOf('.');
        if (dotIdx <= 0) return "Invalid format. Use: className.methodName";
        
        String className = target.substring(0, dotIdx);
        String methodName = target.substring(dotIdx + 1);
        
        hotSwapper.addMethodTracing(className, methodName);
        return "Tracing added to " + target;
    }

    private String addTiming(String target) throws Exception {
        if (target.isEmpty()) return "Usage: add-timing <className.methodName>";
        
        int dotIdx = target.lastIndexOf('.');
        if (dotIdx <= 0) return "Invalid format. Use: className.methodName";
        
        String className = target.substring(0, dotIdx);
        String methodName = target.substring(dotIdx + 1);
        
        hotSwapper.addMethodTiming(className, methodName);
        return "Timing added to " + target;
    }

    private String restoreClass(String className) throws Exception {
        if (className.isEmpty()) return "Usage: restore <className>";
        
        if (!hotSwapper.hasOriginal(className)) {
            return "No modifications to restore for: " + className;
        }
        
        hotSwapper.restoreOriginal(className);
        return "Restored: " + className;
    }

    private String getInfo() {
        Runtime rt = Runtime.getRuntime();
        return String.format(
            "Loaded Classes: %d%n" +
            "Heap Used: %d MB%n" +
            "Heap Max: %d MB%n" +
            "Available Processors: %d%n" +
            "Redefine Supported: %s%n" +
            "Retransform Supported: %s",
            instrumentation.getAllLoadedClasses().length,
            (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
            rt.maxMemory() / (1024 * 1024),
            rt.availableProcessors(),
            instrumentation.isRedefineClassesSupported(),
            instrumentation.isRetransformClassesSupported()
        );
    }
}

