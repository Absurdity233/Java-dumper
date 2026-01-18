package com.javadumper.core;

import com.sun.tools.attach.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class HeapDumper {
    
    private static final Logger logger = LoggerFactory.getLogger(HeapDumper.class);
    private static final String HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";
    
    private final JvmProcessManager processManager;
    
    public HeapDumper() {
        this.processManager = new JvmProcessManager();
    }
    
    /**
     * 对指定PID的JVM生成堆转储
     * @param pid 目标JVM的进程ID
     * @param outputPath 输出文件路径，如果为null则自动生成
     * @param live 是否只dump存活对象
     * @return 生成的dump文件路径
     */
    public String dumpHeap(String pid, String outputPath, boolean live) throws Exception {
        String dumpPath = outputPath != null ? outputPath : generateDumpPath(pid, "heap");
        
        Path path = Paths.get(dumpPath);
        Files.createDirectories(path.getParent());
        
        logger.info("Starting heap dump for PID: {} to {}", pid, dumpPath);
        
        VirtualMachine vm = null;
        JMXConnector connector = null;
        
        try {
            vm = VirtualMachine.attach(pid);
            
            String connectorAddress = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            
            if (connectorAddress == null) {
                String javaHome = vm.getSystemProperties().getProperty("java.home");
                String agentPath = javaHome + File.separator + "lib" + File.separator + 
                                   "management-agent.jar";
                
                File agentFile = new File(agentPath);
                if (agentFile.exists()) {
                    vm.loadAgent(agentPath);
                } else {
                    vm.startLocalManagementAgent();
                }
                
                connectorAddress = vm.getAgentProperties()
                    .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            connector = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            
            ObjectName hotspotDiagnostic = new ObjectName(HOTSPOT_BEAN_NAME);
            mbsc.invoke(hotspotDiagnostic, "dumpHeap",
                new Object[] { dumpPath, live },
                new String[] { String.class.getName(), boolean.class.getName() });
            
            logger.info("Heap dump completed successfully: {}", dumpPath);
            return dumpPath;
            
        } finally {
            if (connector != null) {
                try {
                    connector.close();
                } catch (Exception e) {
                    logger.warn("Error closing JMX connector", e);
                }
            }
            if (vm != null) {
                processManager.detach(vm);
            }
        }
    }
    
    /**
     * 对当前JVM生成堆转储
     */
    public String dumpCurrentHeap(String outputPath, boolean live) throws Exception {
        String dumpPath = outputPath != null ? outputPath : 
            generateDumpPath(getCurrentPid(), "heap");
        
        Path path = Paths.get(dumpPath);
        Files.createDirectories(path.getParent());
        
        logger.info("Starting heap dump for current JVM to {}", dumpPath);
        
        try {
            javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName hotspotDiagnostic = new ObjectName(HOTSPOT_BEAN_NAME);
            
            server.invoke(hotspotDiagnostic, "dumpHeap",
                new Object[] { dumpPath, live },
                new String[] { String.class.getName(), boolean.class.getName() });
            
            logger.info("Heap dump completed successfully: {}", dumpPath);
            return dumpPath;
            
        } catch (Exception e) {
            logger.error("Failed to dump heap", e);
            throw e;
        }
    }
    
    /**
     * 获取堆内存使用信息
     */
    public HeapInfo getHeapInfo(String pid) throws Exception {
        VirtualMachine vm = null;
        JMXConnector connector = null;
        
        try {
            vm = VirtualMachine.attach(pid);
            
            String connectorAddress = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            
            if (connectorAddress == null) {
                vm.startLocalManagementAgent();
                connectorAddress = vm.getAgentProperties()
                    .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            connector = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            
            ObjectName memoryMXBean = new ObjectName("java.lang:type=Memory");
            
            javax.management.openmbean.CompositeData heapUsage =
                (javax.management.openmbean.CompositeData) mbsc.getAttribute(memoryMXBean, "HeapMemoryUsage");
            
            long used = (Long) heapUsage.get("used");
            long committed = (Long) heapUsage.get("committed");
            long max = (Long) heapUsage.get("max");
            long init = (Long) heapUsage.get("init");
            
            return new HeapInfo(init, used, committed, max);
            
        } finally {
            if (connector != null) {
                try { connector.close(); } catch (Exception e) { }
            }
            if (vm != null) {
                processManager.detach(vm);
            }
        }
    }

    private String generateDumpPath(String pid, String type) {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("dumps/%s_dump_%s_%s.hprof", type, pid, timestamp);
    }

    private String getCurrentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }

    public static class HeapInfo {
        private final long init;
        private final long used;
        private final long committed;
        private final long max;
        
        public HeapInfo(long init, long used, long committed, long max) {
            this.init = init;
            this.used = used;
            this.committed = committed;
            this.max = max;
        }
        
        public long getInit() { return init; }
        public long getUsed() { return used; }
        public long getCommitted() { return committed; }
        public long getMax() { return max; }
        
        public String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        
        @Override
        public String toString() {
            return String.format(
                "Heap Memory:\n" +
                "  Init:      %s\n" +
                "  Used:      %s\n" +
                "  Committed: %s\n" +
                "  Max:       %s\n" +
                "  Usage:     %.2f%%",
                formatSize(init), formatSize(used), 
                formatSize(committed), formatSize(max),
                max > 0 ? (used * 100.0 / max) : 0);
        }
    }
}

