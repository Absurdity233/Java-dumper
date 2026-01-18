package com.javadumper.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class AgentClient implements AutoCloseable {
    
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public AgentClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public String sendCommand(String command) throws IOException {
        writer.println(command);
        
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if ("__END__".equals(line)) break;
            response.append(line).append("\n");
        }
        
        return response.toString().trim();
    }

    public void listClasses(String filter) throws IOException {
        String response = sendCommand("list-classes " + (filter != null ? filter : ""));
        System.out.println(response);
    }

    public void dumpClass(String className) throws IOException {
        String response = sendCommand("dump-class " + className);
        System.out.println(response);
    }

    public void decompileClass(String className) throws IOException {
        String response = sendCommand("decompile " + className);
        System.out.println(response);
    }

    public void addMethodTrace(String classMethod) throws IOException {
        String response = sendCommand("add-trace " + classMethod);
        System.out.println(response);
    }

    public void addMethodTiming(String classMethod) throws IOException {
        String response = sendCommand("add-timing " + classMethod);
        System.out.println(response);
    }

    public void restoreClass(String className) throws IOException {
        String response = sendCommand("restore " + className);
        System.out.println(response);
    }

    public void restoreAll() throws IOException {
        String response = sendCommand("restore-all");
        System.out.println(response);
    }

    public void getInfo() throws IOException {
        String response = sendCommand("info");
        System.out.println(response);
    }

    public void triggerGc() throws IOException {
        String response = sendCommand("gc");
        System.out.println(response);
    }

    @Override
    public void close() {
        try {
            if (writer != null) writer.println("exit");
            if (socket != null) socket.close();
        } catch (IOException e) {
            // ignore
        }
    }

    public void startInteractiveSession() throws IOException {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        
        System.out.println("Connected to Agent at " + host + ":" + port);
        System.out.println("Type 'help' for available commands, 'quit' to exit");
        System.out.println();
        
        while (true) {
            System.out.print("agent> ");
            String line = console.readLine();
            
            if (line == null || "quit".equalsIgnoreCase(line.trim())) {
                break;
            }
            
            if ("help".equalsIgnoreCase(line.trim())) {
                printHelp();
                continue;
            }
            
            if (line.trim().isEmpty()) continue;
            
            try {
                String response = sendCommand(line.trim());
                System.out.println(response);
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
            }
            
            System.out.println();
        }
    }

    private void printHelp() {
        System.out.println("\nAvailable commands:");
        System.out.println("  list-classes [filter]     - List loaded classes");
        System.out.println("  dump-class <class>        - Dump class bytecode to file");
        System.out.println("  decompile <class>         - Show bytecode text");
        System.out.println("  add-trace <class.method>  - Add trace logging to method");
        System.out.println("  add-timing <class.method> - Add timing to method");
        System.out.println("  restore <class>           - Restore modified class");
        System.out.println("  restore-all               - Restore all modified classes");
        System.out.println("  info                      - Show JVM info");
        System.out.println("  gc                        - Trigger garbage collection");
        System.out.println("  quit                      - Exit client");
        System.out.println();
    }
}

