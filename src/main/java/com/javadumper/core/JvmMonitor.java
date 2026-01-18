package com.javadumper.core;

import com.sun.tools.attach.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class JvmMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(JvmMonitor.class);
    
    private final String pid;
    private VirtualMachine vm;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbsc;
    private ScheduledExecutorService scheduler;
    private volatile boolean monitoring = false;

    public JvmMonitor(String pid) {
        this.pid = pid;
    }

    public void connect() throws Exception {
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
        
        logger.info("Connected to JVM {}", pid);
    }

    public void disconnect() {
        stopMonitoring();
        
        try {
            if (jmxConnector != null) jmxConnector.close();
            if (vm != null) vm.detach();
        } catch (Exception e) {
            logger.warn("Error disconnecting: {}", e.getMessage());
        }
    }

    public MemoryStats getMemoryStats() throws Exception {
        ObjectName memoryMXBean = new ObjectName("java.lang:type=Memory");
        
        CompositeData heapUsage = (CompositeData) mbsc.getAttribute(memoryMXBean, "HeapMemoryUsage");
        CompositeData nonHeapUsage = (CompositeData) mbsc.getAttribute(memoryMXBean, "NonHeapMemoryUsage");
        
        return new MemoryStats(
            (Long) heapUsage.get("used"),
            (Long) heapUsage.get("max"),
            (Long) heapUsage.get("committed"),
            (Long) nonHeapUsage.get("used"),
            (Long) nonHeapUsage.get("committed")
        );
    }

    public List<GcStats> getGcStats() throws Exception {
        List<GcStats> stats = new ArrayList<>();
        
        Set<ObjectName> gcBeans = mbsc.queryNames(
            new ObjectName("java.lang:type=GarbageCollector,*"), null);
        
        for (ObjectName gcBean : gcBeans) {
            String name = (String) mbsc.getAttribute(gcBean, "Name");
            long count = (Long) mbsc.getAttribute(gcBean, "CollectionCount");
            long time = (Long) mbsc.getAttribute(gcBean, "CollectionTime");
            
            stats.add(new GcStats(name, count, time));
        }
        
        return stats;
    }

    public ThreadStats getThreadStats() throws Exception {
        ObjectName threadMXBean = new ObjectName("java.lang:type=Threading");
        
        int threadCount = (Integer) mbsc.getAttribute(threadMXBean, "ThreadCount");
        int peakCount = (Integer) mbsc.getAttribute(threadMXBean, "PeakThreadCount");
        int daemonCount = (Integer) mbsc.getAttribute(threadMXBean, "DaemonThreadCount");
        long totalStarted = (Long) mbsc.getAttribute(threadMXBean, "TotalStartedThreadCount");
        
        return new ThreadStats(threadCount, peakCount, daemonCount, totalStarted);
    }

    public CpuStats getCpuStats() throws Exception {
        ObjectName osMXBean = new ObjectName("java.lang:type=OperatingSystem");
        
        double processCpuLoad = -1;
        double systemCpuLoad = -1;
        int availableProcessors = (Integer) mbsc.getAttribute(osMXBean, "AvailableProcessors");
        
        try {
            processCpuLoad = (Double) mbsc.getAttribute(osMXBean, "ProcessCpuLoad");
            systemCpuLoad = (Double) mbsc.getAttribute(osMXBean, "SystemCpuLoad");
        } catch (Exception e) {
            logger.debug("CPU load metrics not available");
        }
        
        return new CpuStats(processCpuLoad, systemCpuLoad, availableProcessors);
    }

    public ClassLoadingStats getClassLoadingStats() throws Exception {
        ObjectName classLoadingMXBean = new ObjectName("java.lang:type=ClassLoading");
        
        int loaded = (Integer) mbsc.getAttribute(classLoadingMXBean, "LoadedClassCount");
        long totalLoaded = (Long) mbsc.getAttribute(classLoadingMXBean, "TotalLoadedClassCount");
        long unloaded = (Long) mbsc.getAttribute(classLoadingMXBean, "UnloadedClassCount");
        
        return new ClassLoadingStats(loaded, totalLoaded, unloaded);
    }

    public void startMonitoring(int intervalSeconds, MonitorCallback callback) {
        if (monitoring) return;
        
        monitoring = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                MonitorSnapshot snapshot = new MonitorSnapshot(
                    System.currentTimeMillis(),
                    getMemoryStats(),
                    getGcStats(),
                    getThreadStats(),
                    getCpuStats(),
                    getClassLoadingStats()
                );
                callback.onSnapshot(snapshot);
            } catch (Exception e) {
                logger.error("Monitoring error", e);
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stopMonitoring() {
        monitoring = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void printCurrentStats() throws Exception {
        MemoryStats mem = getMemoryStats();
        ThreadStats thread = getThreadStats();
        CpuStats cpu = getCpuStats();
        List<GcStats> gc = getGcStats();
        ClassLoadingStats cls = getClassLoadingStats();
        
        System.out.println("\n==================== JVM Stats ====================");
        System.out.printf("Memory: Heap %s / %s (%.1f%%), Non-Heap %s%n",
            formatBytes(mem.heapUsed), formatBytes(mem.heapMax),
            mem.heapMax > 0 ? mem.heapUsed * 100.0 / mem.heapMax : 0,
            formatBytes(mem.nonHeapUsed));
        
        System.out.printf("Threads: %d active, %d peak, %d daemon%n",
            thread.threadCount, thread.peakCount, thread.daemonCount);
        
        if (cpu.processCpuLoad >= 0) {
            System.out.printf("CPU: Process %.1f%%, System %.1f%%, Processors %d%n",
                cpu.processCpuLoad * 100, cpu.systemCpuLoad * 100, cpu.availableProcessors);
        }
        
        System.out.printf("Classes: %d loaded, %d total, %d unloaded%n",
            cls.loadedCount, cls.totalLoadedCount, cls.unloadedCount);
        
        System.out.println("GC:");
        for (GcStats g : gc) {
            System.out.printf("  %s: %d collections, %d ms%n", g.name, g.collectionCount, g.collectionTime);
        }
        System.out.println("===================================================\n");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }

    public static class MemoryStats {
        public final long heapUsed, heapMax, heapCommitted;
        public final long nonHeapUsed, nonHeapCommitted;
        
        public MemoryStats(long heapUsed, long heapMax, long heapCommitted, 
                          long nonHeapUsed, long nonHeapCommitted) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.heapCommitted = heapCommitted;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapCommitted = nonHeapCommitted;
        }
    }

    public static class GcStats {
        public final String name;
        public final long collectionCount;
        public final long collectionTime;
        
        public GcStats(String name, long collectionCount, long collectionTime) {
            this.name = name;
            this.collectionCount = collectionCount;
            this.collectionTime = collectionTime;
        }
    }

    public static class ThreadStats {
        public final int threadCount, peakCount, daemonCount;
        public final long totalStartedCount;
        
        public ThreadStats(int threadCount, int peakCount, int daemonCount, long totalStartedCount) {
            this.threadCount = threadCount;
            this.peakCount = peakCount;
            this.daemonCount = daemonCount;
            this.totalStartedCount = totalStartedCount;
        }
    }

    public static class CpuStats {
        public final double processCpuLoad, systemCpuLoad;
        public final int availableProcessors;
        
        public CpuStats(double processCpuLoad, double systemCpuLoad, int availableProcessors) {
            this.processCpuLoad = processCpuLoad;
            this.systemCpuLoad = systemCpuLoad;
            this.availableProcessors = availableProcessors;
        }
    }

    public static class ClassLoadingStats {
        public final int loadedCount;
        public final long totalLoadedCount, unloadedCount;
        
        public ClassLoadingStats(int loadedCount, long totalLoadedCount, long unloadedCount) {
            this.loadedCount = loadedCount;
            this.totalLoadedCount = totalLoadedCount;
            this.unloadedCount = unloadedCount;
        }
    }

    public static class MonitorSnapshot {
        public final long timestamp;
        public final MemoryStats memory;
        public final List<GcStats> gc;
        public final ThreadStats threads;
        public final CpuStats cpu;
        public final ClassLoadingStats classLoading;
        
        public MonitorSnapshot(long timestamp, MemoryStats memory, List<GcStats> gc,
                              ThreadStats threads, CpuStats cpu, ClassLoadingStats classLoading) {
            this.timestamp = timestamp;
            this.memory = memory;
            this.gc = gc;
            this.threads = threads;
            this.cpu = cpu;
            this.classLoading = classLoading;
        }
    }

    public interface MonitorCallback {
        void onSnapshot(MonitorSnapshot snapshot);
    }
}

