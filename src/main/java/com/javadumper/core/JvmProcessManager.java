package com.javadumper.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class JvmProcessManager {
    
    private static final Logger logger = LoggerFactory.getLogger(JvmProcessManager.class);

    public static class JvmProcessInfo {
        private final String pid;
        private final String displayName;
        private final String mainClass;
        private final boolean attachable;
        
        public JvmProcessInfo(String pid, String displayName, String mainClass, boolean attachable) {
            this.pid = pid;
            this.displayName = displayName;
            this.mainClass = mainClass;
            this.attachable = attachable;
        }
        
        public String getPid() { return pid; }
        public String getDisplayName() { return displayName; }
        public String getMainClass() { return mainClass; }
        public boolean isAttachable() { return attachable; }
        
        @Override
        public String toString() {
            return String.format("[PID: %s] %s (Attachable: %s)", pid, displayName, attachable);
        }
    }

    public List<JvmProcessInfo> listAllJvmProcesses() {
        List<JvmProcessInfo> processes = new ArrayList<>();
        List<VirtualMachineDescriptor> vmList = VirtualMachine.list();
        
        for (VirtualMachineDescriptor vmd : vmList) {
            String pid = vmd.id();
            String displayName = vmd.displayName();
            String mainClass = extractMainClass(displayName);
            boolean attachable = isAttachable(pid);
            
            processes.add(new JvmProcessInfo(pid, displayName, mainClass, attachable));
        }
        
        logger.info("Found {} JVM processes", processes.size());
        return processes;
    }

    private boolean isAttachable(String pid) {
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.detach();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从displayName中提取主类名
     */
    private String extractMainClass(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "Unknown";
        }
        
        int spaceIndex = displayName.indexOf(' ');
        String className = spaceIndex > 0 ? displayName.substring(0, spaceIndex) : displayName;
        
        if (className.endsWith(".jar")) {
            return className;
        }
        
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(lastDot + 1) : className;
    }

    public VirtualMachine attach(String pid) throws Exception {
        logger.info("Attaching to JVM with PID: {}", pid);
        return VirtualMachine.attach(pid);
    }

    public void detach(VirtualMachine vm) {
        if (vm != null) {
            try {
                vm.detach();
                logger.info("Detached from JVM");
            } catch (Exception e) {
                logger.error("Error detaching from JVM", e);
            }
        }
    }

    public Properties getSystemProperties(String pid) throws Exception {
        VirtualMachine vm = null;
        try {
            vm = attach(pid);
            return vm.getSystemProperties();
        } finally {
            detach(vm);
        }
    }

    public void loadAgent(String pid, String agentPath) throws Exception {
        loadAgent(pid, agentPath, null);
    }

    public void loadAgent(String pid, String agentPath, String agentArgs) throws Exception {
        VirtualMachine vm = null;
        try {
            vm = attach(pid);
            logger.info("Loading agent: {} with args: {}", agentPath, agentArgs);
            vm.loadAgent(agentPath, agentArgs);
            logger.info("Agent loaded successfully");
        } finally {
            detach(vm);
        }
    }

    public void printProcesses() {
        List<JvmProcessInfo> processes = listAllJvmProcesses();
        
        System.out.println("\n========== Running JVM Processes ==========");
        System.out.printf("%-10s %-50s %-12s%n", "PID", "Display Name", "Attachable");
        System.out.println("------------------------------------------------------------");
        
        for (JvmProcessInfo process : processes) {
            String displayName = process.getDisplayName();
            if (displayName.length() > 47) {
                displayName = displayName.substring(0, 47) + "...";
            }
            System.out.printf("%-10s %-50s %-12s%n", 
                process.getPid(), 
                displayName, 
                process.isAttachable() ? "Yes" : "No");
        }
        
        System.out.println("============================================\n");
    }
}

