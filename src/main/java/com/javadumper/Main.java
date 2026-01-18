package com.javadumper;

import com.javadumper.core.*;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class Main {
    
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.0.0";
    
    public static void main(String[] args) {
        Options options = createOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        
        try {
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("help") || args.length == 0) {
                printBanner();
                formatter.printHelp("java-dumper", options, true);
                return;
            }
            
            if (cmd.hasOption("version")) {
                System.out.println("Java Dumper v" + VERSION);
                return;
            }
            
            executeCommand(cmd);
            
        } catch (ParseException e) {
            System.err.println("参数解析错误: " + e.getMessage());
            formatter.printHelp("java-dumper", options, true);
            System.exit(1);
        } catch (Exception e) {
            logger.error("执行错误", e);
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Options createOptions() {
        Options options = new Options();
        
        options.addOption(Option.builder("h")
            .longOpt("help")
            .desc("显示帮助信息")
            .build());
        
        options.addOption(Option.builder("v")
            .longOpt("version")
            .desc("显示版本信息")
            .build());
        
        options.addOption(Option.builder("p")
            .longOpt("pid")
            .hasArg()
            .argName("PID")
            .desc("目标JVM进程ID")
            .build());
        
        options.addOption(Option.builder("l")
            .longOpt("list")
            .desc("列出所有运行中的JVM进程")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("heap-dump")
            .desc("生成堆内存转储")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("thread-dump")
            .desc("生成线程转储")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("heap-info")
            .desc("显示堆内存使用信息")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("deadlock")
            .desc("检测死锁")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("list-classes")
            .desc("列出已加载的类")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("dump-class")
            .hasArg()
            .argName("CLASS")
            .desc("导出指定类的字节码")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("attach")
            .desc("附加Agent到目标JVM")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("trace")
            .hasArg()
            .argName("CLASS.METHOD")
            .desc("追踪指定方法的调用")
            .build());
        
        // 输出选项
        options.addOption(Option.builder("o")
            .longOpt("output")
            .hasArg()
            .argName("PATH")
            .desc("输出文件路径")
            .build());
        
        options.addOption(Option.builder("f")
            .longOpt("filter")
            .hasArg()
            .argName("PATTERN")
            .desc("类名过滤模式")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("live")
            .desc("堆dump时只包含存活对象")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("agent")
            .hasArg()
            .argName("PATH")
            .desc("Agent JAR路径")
            .build());
        
        options.addOption(Option.builder()
            .longOpt("agent-args")
            .hasArg()
            .argName("ARGS")
            .desc("Agent参数")
            .build());
        
        return options;
    }

    private static void executeCommand(CommandLine cmd) throws Exception {
        if (cmd.hasOption("list")) {
            listProcesses();
            return;
        }
        
        String pid = cmd.getOptionValue("pid");
        String output = cmd.getOptionValue("output");
        
        if (cmd.hasOption("heap-info")) {
            if (pid == null) {
                System.out.println("请使用 -p/--pid 指定目标进程");
                return;
            }
            showHeapInfo(pid);
            return;
        }
        
        if (pid == null && !cmd.hasOption("list-classes")) {
            System.out.println("请使用 -p/--pid 指定目标JVM进程ID");
            System.out.println("使用 -l/--list 列出所有可用的JVM进程");
            return;
        }
        
        if (cmd.hasOption("heap-dump")) {
            boolean live = cmd.hasOption("live");
            heapDump(pid, output, live);
            return;
        }
        
        if (cmd.hasOption("thread-dump")) {
            threadDump(pid, output);
            return;
        }
        
        if (cmd.hasOption("deadlock")) {
            detectDeadlock(pid);
            return;
        }
        
        if (cmd.hasOption("list-classes")) {
            String filter = cmd.getOptionValue("filter");
            listClasses(pid, filter);
            return;
        }
        
        if (cmd.hasOption("dump-class")) {
            String className = cmd.getOptionValue("dump-class");
            dumpClass(pid, className, output);
            return;
        }
        
        if (cmd.hasOption("attach")) {
            String agentPath = cmd.getOptionValue("agent");
            String agentArgs = cmd.getOptionValue("agent-args");
            attachAgent(pid, agentPath, agentArgs);
            return;
        }
        
        if (cmd.hasOption("trace")) {
            String target = cmd.getOptionValue("trace");
            enableTrace(pid, target);
            return;
        }
        
        System.out.println("请指定操作，使用 -h 查看帮助");
    }

    private static void listProcesses() {
        JvmProcessManager manager = new JvmProcessManager();
        manager.printProcesses();
    }

    private static void showHeapInfo(String pid) throws Exception {
        HeapDumper dumper = new HeapDumper();
        HeapDumper.HeapInfo info = dumper.getHeapInfo(pid);
        System.out.println(info);
    }

    private static void heapDump(String pid, String output, boolean live) throws Exception {
        HeapDumper dumper = new HeapDumper();
        String path = dumper.dumpHeap(pid, output, live);
        System.out.println("堆转储已保存到: " + path);
        
        File file = new File(path);
        System.out.printf("文件大小: %.2f MB%n", file.length() / (1024.0 * 1024));
    }

    private static void threadDump(String pid, String output) throws Exception {
        ThreadDumper dumper = new ThreadDumper();
        
        if (output != null) {
            String path = dumper.saveThreadDump(pid, output);
            System.out.println("线程转储已保存到: " + path);
        } else {
            String dump = dumper.getThreadDump(pid);
            System.out.println(dump);
        }
    }

    private static void detectDeadlock(String pid) throws Exception {
        ThreadDumper dumper = new ThreadDumper();
        List<ThreadDumper.DeadlockInfo> deadlocks = dumper.detectDeadlocks(pid);
        
        if (deadlocks.isEmpty()) {
            System.out.println("未检测到死锁");
        } else {
            System.out.println("\n========== 检测到死锁 ==========");
            for (ThreadDumper.DeadlockInfo info : deadlocks) {
                System.out.println(info);
            }
            System.out.println("=================================\n");
        }
    }

    private static void listClasses(String pid, String filter) throws Exception {
        JvmProcessManager manager = new JvmProcessManager();
        String agentPath = findAgentJar();
        
        if (agentPath == null) {
            System.out.println("未找到Agent JAR文件，请先构建项目: gradle agentJar");
            return;
        }
        
        String agentArgs = "cmd=list-classes";
        if (filter != null) {
            agentArgs += ",filter=" + filter;
        }
        
        manager.loadAgent(pid, agentPath, agentArgs);
    }

    private static void dumpClass(String pid, String className, String output) throws Exception {
        JvmProcessManager manager = new JvmProcessManager();
        String agentPath = findAgentJar();
        
        if (agentPath == null) {
            System.out.println("未找到Agent JAR文件，请先构建项目: gradle agentJar");
            return;
        }
        
        String agentArgs = "cmd=dump-class,class=" + className;
        manager.loadAgent(pid, agentPath, agentArgs);
        
        System.out.println("类字节码将保存到 dumps/ 目录");
    }

    private static void attachAgent(String pid, String agentPath, String agentArgs) throws Exception {
        if (agentPath == null) {
            agentPath = findAgentJar();
        }
        
        if (agentPath == null) {
            System.out.println("请使用 --agent 指定Agent JAR路径");
            return;
        }
        
        JvmProcessManager manager = new JvmProcessManager();
        manager.loadAgent(pid, agentPath, agentArgs);
        System.out.println("Agent已成功附加到进程 " + pid);
    }

    private static void enableTrace(String pid, String target) throws Exception {
        String className;
        String methodName = null;
        
        int dotIndex = target.lastIndexOf('.');
        if (dotIndex > 0) {
            className = target.substring(0, dotIndex);
            methodName = target.substring(dotIndex + 1);
        } else {
            className = target;
        }
        
        JvmProcessManager manager = new JvmProcessManager();
        String agentPath = findAgentJar();
        
        if (agentPath == null) {
            System.out.println("未找到Agent JAR文件，请先构建项目: gradle agentJar");
            return;
        }
        
        String agentArgs = "cmd=trace,class=" + className;
        if (methodName != null) {
            agentArgs += ",method=" + methodName;
        }
        
        manager.loadAgent(pid, agentPath, agentArgs);
        System.out.println("方法追踪已启用: " + target);
    }

    private static String findAgentJar() {
        String[] possiblePaths = {
            "build/libs/dumper-agent.jar",
            "build/libs/java-dumper-all.jar",
            "dumper-agent.jar"
        };
        
        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        }
        
        return null;
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("     ██╗ █████╗ ██╗   ██╗ █████╗     ██████╗ ██╗   ██╗███╗   ███╗██████╗ ███████╗██████╗ ");
        System.out.println("     ██║██╔══██╗██║   ██║██╔══██╗    ██╔══██╗██║   ██║████╗ ████║██╔══██╗██╔════╝██╔══██╗");
        System.out.println("     ██║███████║██║   ██║███████║    ██║  ██║██║   ██║██╔████╔██║██████╔╝█████╗  ██████╔╝");
        System.out.println("██   ██║██╔══██║╚██╗ ██╔╝██╔══██║    ██║  ██║██║   ██║██║╚██╔╝██║██╔═══╝ ██╔══╝  ██╔══██╗");
        System.out.println("╚█████╔╝██║  ██║ ╚████╔╝ ██║  ██║    ██████╔╝╚██████╔╝██║ ╚═╝ ██║██║     ███████╗██║  ██║");
        System.out.println(" ╚════╝ ╚═╝  ╚═╝  ╚═══╝  ╚═╝  ╚═╝    ╚═════╝  ╚═════╝ ╚═╝     ╚═╝╚═╝     ╚══════╝╚═╝  ╚═╝");
        System.out.println("                                                                            v" + VERSION);
        System.out.println();
        System.out.println("  JVM Dump & Dynamic Debug Tool");
        System.out.println();
    }
}

