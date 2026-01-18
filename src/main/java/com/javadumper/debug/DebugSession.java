package com.javadumper.debug;

import com.javadumper.core.JvmProcessManager;
import com.sun.tools.attach.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class DebugSession {
    
    private static final Logger logger = LoggerFactory.getLogger(DebugSession.class);
    
    private final String pid;
    private final JvmProcessManager processManager;
    private VirtualMachine vm;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbsc;
    private boolean connected = false;
    
    public DebugSession(String pid) {
        this.pid = pid;
        this.processManager = new JvmProcessManager();
    }
    public void connect() throws Exception {
        logger.info("Connecting to JVM with PID: {}", pid);
        
        vm = VirtualMachine.attach(pid);
        
        String connectorAddress = vm.getAgentProperties()
            .getProperty("com.sun.management.jmxremote.localConnectorAddress");
        
        if (connectorAddress == null) {
            vm.startLocalManagementAgent();
            connectorAddress = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
        }
        
        JMXServiceURL url = new JMXServiceURL(connectorAddress);
        jmxConnector = JMXConnectorFactory.connect(url);
        mbsc = jmxConnector.getMBeanServerConnection();
        
        connected = true;
        logger.info("Successfully connected to JVM");
        
        printJvmInfo();
    }

    public void disconnect() {
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing JMX connector", e);
            }
        }
        
        if (vm != null) {
            try {
                vm.detach();
            } catch (Exception e) {
                logger.warn("Error detaching from VM", e);
            }
        }
        
        connected = false;
        logger.info("Disconnected from JVM");
    }

    private void printJvmInfo() throws Exception {
        Properties sysProps = vm.getSystemProperties();
        
        System.out.println("\n========== JVM Information ==========");
        System.out.println("Java Version: " + sysProps.getProperty("java.version"));
        System.out.println("Java Vendor: " + sysProps.getProperty("java.vendor"));
        System.out.println("Java Home: " + sysProps.getProperty("java.home"));
        System.out.println("OS: " + sysProps.getProperty("os.name") + " " + sysProps.getProperty("os.version"));
        System.out.println("PID: " + pid);
        System.out.println("======================================\n");
    }

    public void startInteractiveSession() {
        if (!connected) {
            System.out.println("未连接到JVM，请先调用connect()");
            return;
        }
        
        System.out.println("进入交互式调试模式，输入 'help' 查看可用命令，'quit' 退出");
        System.out.println();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        while (connected) {
            try {
                System.out.print("dumper> ");
                String line = reader.readLine();
                
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                
                String[] parts = line.trim().split("\\s+");
                String command = parts[0].toLowerCase();
                
                switch (command) {
                    case "help":
                        printHelp();
                        break;
                    case "quit":
                    case "exit":
                        System.out.println("退出调试会话...");
                        return;
                    case "info":
                        printJvmInfo();
                        break;
                    case "threads":
                        listThreads();
                        break;
                    case "heap":
                        showHeapUsage();
                        break;
                    case "gc":
                        runGC();
                        break;
                    case "classes":
                        String filter = parts.length > 1 ? parts[1] : null;
                        listLoadedClasses(filter);
                        break;
                    case "props":
                        showSystemProperties();
                        break;
                    default:
                        System.out.println("未知命令: " + command);
                        System.out.println("输入 'help' 查看可用命令");
                }
                
            } catch (Exception e) {
                System.out.println("错误: " + e.getMessage());
                logger.error("Command execution error", e);
            }
        }
    }

    private void printHelp() {
        System.out.println("\n可用命令:");
        System.out.println("  help              - 显示帮助信息");
        System.out.println("  info              - 显示JVM信息");
        System.out.println("  threads           - 列出所有线程");
        System.out.println("  heap              - 显示堆内存使用情况");
        System.out.println("  gc                - 触发垃圾回收");
        System.out.println("  classes [filter]  - 列出已加载的类");
        System.out.println("  props             - 显示系统属性");
        System.out.println("  quit/exit         - 退出调试会话");
        System.out.println();
    }

    private void listThreads() throws Exception {
        javax.management.ObjectName threadMXBeanName = 
            new javax.management.ObjectName("java.lang:type=Threading");
        
        long[] threadIds = (long[]) mbsc.getAttribute(threadMXBeanName, "AllThreadIds");
        int threadCount = (Integer) mbsc.getAttribute(threadMXBeanName, "ThreadCount");
        int peakThreadCount = (Integer) mbsc.getAttribute(threadMXBeanName, "PeakThreadCount");
        
        System.out.println("\n========== Threads ==========");
        System.out.println("Current Thread Count: " + threadCount);
        System.out.println("Peak Thread Count: " + peakThreadCount);
        System.out.println("Thread IDs: " + threadIds.length);
        System.out.println("=============================\n");
    }

    private void showHeapUsage() throws Exception {
        javax.management.ObjectName memoryMXBean = 
            new javax.management.ObjectName("java.lang:type=Memory");
        
        javax.management.openmbean.CompositeData heapUsage = 
            (javax.management.openmbean.CompositeData) mbsc.getAttribute(memoryMXBean, "HeapMemoryUsage");
        
        long used = (Long) heapUsage.get("used");
        long committed = (Long) heapUsage.get("committed");
        long max = (Long) heapUsage.get("max");
        
        System.out.println("\n========== Heap Usage ==========");
        System.out.printf("Used:      %s%n", formatSize(used));
        System.out.printf("Committed: %s%n", formatSize(committed));
        System.out.printf("Max:       %s%n", formatSize(max));
        System.out.printf("Usage:     %.2f%%%n", max > 0 ? (used * 100.0 / max) : 0);
        System.out.println("=================================\n");
    }

    private void runGC() throws Exception {
        javax.management.ObjectName memoryMXBean = 
            new javax.management.ObjectName("java.lang:type=Memory");
        
        mbsc.invoke(memoryMXBean, "gc", null, null);
        System.out.println("已触发垃圾回收");
    }

    private void listLoadedClasses(String filter) throws Exception {
        javax.management.ObjectName classLoadingMXBean = 
            new javax.management.ObjectName("java.lang:type=ClassLoading");
        
        int loadedClassCount = (Integer) mbsc.getAttribute(classLoadingMXBean, "LoadedClassCount");
        long totalLoadedClassCount = (Long) mbsc.getAttribute(classLoadingMXBean, "TotalLoadedClassCount");
        long unloadedClassCount = (Long) mbsc.getAttribute(classLoadingMXBean, "UnloadedClassCount");
        
        System.out.println("\n========== Class Loading ==========");
        System.out.println("Currently Loaded: " + loadedClassCount);
        System.out.println("Total Loaded: " + totalLoadedClassCount);
        System.out.println("Unloaded: " + unloadedClassCount);
        
        if (filter != null) {
            System.out.println("\n(要列出详细类信息，请使用Agent功能)");
        }
        
        System.out.println("====================================\n");
    }

    private void showSystemProperties() throws Exception {
        Properties props = vm.getSystemProperties();
        
        System.out.println("\n========== System Properties ==========");
        props.stringPropertyNames().stream()
            .sorted()
            .limit(30)  // 只显示前30个
            .forEach(key -> {
                String value = props.getProperty(key);
                if (value.length() > 60) {
                    value = value.substring(0, 57) + "...";
                }
                System.out.printf("  %s = %s%n", key, value);
            });
        System.out.println("  ... and more");
        System.out.println("========================================\n");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    

    public boolean isConnected() {
        return connected;
    }
    

    public MBeanServerConnection getMBeanConnection() {
        return mbsc;
    }
    

    public VirtualMachine getVirtualMachine() {
        return vm;
    }
}

