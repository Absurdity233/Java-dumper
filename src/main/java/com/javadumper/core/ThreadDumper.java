package com.javadumper.core;

import com.sun.tools.attach.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class ThreadDumper {
    
    private static final Logger logger = LoggerFactory.getLogger(ThreadDumper.class);
    
    private final JvmProcessManager processManager;
    
    public ThreadDumper() {
        this.processManager = new JvmProcessManager();
    }

    public String getThreadDump(String pid) throws Exception {
        logger.info("Getting thread dump for PID: {}", pid);
        
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
            
            // 获取ThreadMXBean
            ObjectName threadMXBeanName = new ObjectName("java.lang:type=Threading");
            
            // 获取所有线程ID
            long[] threadIds = (long[]) mbsc.getAttribute(threadMXBeanName, "AllThreadIds");
            
            StringBuilder sb = new StringBuilder();
            sb.append("========== Thread Dump ==========\n");
            sb.append("Time: ").append(LocalDateTime.now()).append("\n");
            sb.append("PID: ").append(pid).append("\n");
            sb.append("Total Threads: ").append(threadIds.length).append("\n\n");
            
            // 获取每个线程的详细信息
            for (long threadId : threadIds) {
                try {
                    javax.management.openmbean.CompositeData threadInfoData = 
                        (javax.management.openmbean.CompositeData) mbsc.invoke(
                            threadMXBeanName,
                            "getThreadInfo",
                            new Object[] { threadId, Integer.MAX_VALUE },
                            new String[] { long.class.getName(), int.class.getName() }
                        );
                    
                    if (threadInfoData != null) {
                        sb.append(formatThreadInfo(threadInfoData));
                        sb.append("\n");
                    }
                } catch (Exception e) {
                    logger.debug("Could not get info for thread {}", threadId);
                }
            }
            
            return sb.toString();
            
        } finally {
            if (connector != null) {
                try { connector.close(); } catch (Exception e) { }
            }
            if (vm != null) {
                processManager.detach(vm);
            }
        }
    }

    public String getCurrentThreadDump() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        
        StringBuilder sb = new StringBuilder();
        sb.append("========== Thread Dump ==========\n");
        sb.append("Time: ").append(LocalDateTime.now()).append("\n");
        sb.append("Total Threads: ").append(threadInfos.length).append("\n\n");
        
        for (ThreadInfo threadInfo : threadInfos) {
            sb.append(formatThreadInfo(threadInfo));
            sb.append("\n");
        }
        
        return sb.toString();
    }

    public String saveThreadDump(String pid, String outputPath) throws Exception {
        String dumpPath = outputPath != null ? outputPath : generateDumpPath(pid);
        
        Path path = Paths.get(dumpPath);
        Files.createDirectories(path.getParent());
        
        String threadDump = getThreadDump(pid);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(dumpPath))) {
            writer.print(threadDump);
        }
        
        logger.info("Thread dump saved to: {}", dumpPath);
        return dumpPath;
    }

    public List<DeadlockInfo> detectDeadlocks(String pid) throws Exception {
        logger.info("Detecting deadlocks for PID: {}", pid);
        
        VirtualMachine vm = null;
        JMXConnector connector = null;
        List<DeadlockInfo> deadlocks = new ArrayList<>();
        
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
            
            ObjectName threadMXBeanName = new ObjectName("java.lang:type=Threading");
            
            long[] deadlockedThreads = (long[]) mbsc.invoke(
                threadMXBeanName,
                "findDeadlockedThreads",
                null,
                null
            );
            
            if (deadlockedThreads != null && deadlockedThreads.length > 0) {
                logger.warn("Found {} deadlocked threads!", deadlockedThreads.length);
                
                for (long threadId : deadlockedThreads) {
                    javax.management.openmbean.CompositeData threadInfoData = 
                        (javax.management.openmbean.CompositeData) mbsc.invoke(
                            threadMXBeanName,
                            "getThreadInfo",
                            new Object[] { threadId, Integer.MAX_VALUE },
                            new String[] { long.class.getName(), int.class.getName() }
                        );
                    
                    if (threadInfoData != null) {
                        String threadName = (String) threadInfoData.get("threadName");
                        String lockName = (String) threadInfoData.get("lockName");
                        String lockOwnerName = (String) threadInfoData.get("lockOwnerName");
                        
                        deadlocks.add(new DeadlockInfo(threadId, threadName, lockName, lockOwnerName));
                    }
                }
            } else {
                logger.info("No deadlocks detected");
            }
            
            return deadlocks;
            
        } finally {
            if (connector != null) {
                try { connector.close(); } catch (Exception e) { }
            }
            if (vm != null) {
                processManager.detach(vm);
            }
        }
    }

    private String formatThreadInfo(javax.management.openmbean.CompositeData threadInfoData) {
        StringBuilder sb = new StringBuilder();
        
        String threadName = (String) threadInfoData.get("threadName");
        Long threadId = (Long) threadInfoData.get("threadId");
        String threadState = (String) threadInfoData.get("threadState");
        String lockName = (String) threadInfoData.get("lockName");
        String lockOwnerName = (String) threadInfoData.get("lockOwnerName");
        
        sb.append(String.format("\"%s\" #%d%n", threadName, threadId));
        sb.append(String.format("   java.lang.Thread.State: %s%n", threadState));
        
        if (lockName != null) {
            sb.append(String.format("   - waiting on <%s>%n", lockName));
        }
        if (lockOwnerName != null) {
            sb.append(String.format("   - owned by \"%s\"%n", lockOwnerName));
        }
        
        javax.management.openmbean.CompositeData[] stackTrace =
            (javax.management.openmbean.CompositeData[]) threadInfoData.get("stackTrace");
        
        if (stackTrace != null) {
            for (javax.management.openmbean.CompositeData frame : stackTrace) {
                String className = (String) frame.get("className");
                String methodName = (String) frame.get("methodName");
                String fileName = (String) frame.get("fileName");
                Integer lineNumber = (Integer) frame.get("lineNumber");
                
                sb.append(String.format("\tat %s.%s(%s:%d)%n", 
                    className, methodName, 
                    fileName != null ? fileName : "Unknown Source",
                    lineNumber != null ? lineNumber : -1));
            }
        }
        
        return sb.toString();
    }

    private String formatThreadInfo(ThreadInfo threadInfo) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(String.format("\"%s\" #%d daemon=%s%n", 
            threadInfo.getThreadName(), 
            threadInfo.getThreadId(),
            threadInfo.isDaemon()));
        sb.append(String.format("   java.lang.Thread.State: %s%n", threadInfo.getThreadState()));
        
        if (threadInfo.getLockName() != null) {
            sb.append(String.format("   - waiting on <%s>%n", threadInfo.getLockName()));
        }
        if (threadInfo.getLockOwnerName() != null) {
            sb.append(String.format("   - owned by \"%s\" #%d%n", 
                threadInfo.getLockOwnerName(), threadInfo.getLockOwnerId()));
        }
        
        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            sb.append("\tat ").append(element).append("\n");
        }
        
        return sb.toString();
    }

    private String generateDumpPath(String pid) {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("dumps/thread_dump_%s_%s.txt", pid, timestamp);
    }

    public static class DeadlockInfo {
        private final long threadId;
        private final String threadName;
        private final String lockName;
        private final String lockOwnerName;
        
        public DeadlockInfo(long threadId, String threadName, String lockName, String lockOwnerName) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.lockName = lockName;
            this.lockOwnerName = lockOwnerName;
        }
        
        public long getThreadId() { return threadId; }
        public String getThreadName() { return threadName; }
        public String getLockName() { return lockName; }
        public String getLockOwnerName() { return lockOwnerName; }
        
        @Override
        public String toString() {
            return String.format("Thread \"%s\" (#%d) is waiting on %s owned by \"%s\"",
                threadName, threadId, lockName, lockOwnerName);
        }
    }
}

