package com.javadumper.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class Breakpoint {
    
    private static final Map<String, Breakpoint> breakpoints = new ConcurrentHashMap<>();
    private static final AtomicLong idGenerator = new AtomicLong(0);
    
    private final long id;
    private final String className;
    private final String methodName;
    private final int lineNumber;
    private String condition;
    private boolean enabled;
    private int hitCount;
    private int ignoreCount;
    private BreakpointAction action;
    
    public enum BreakpointAction {
        SUSPEND,    // 暂停执行（需要调试器支持）
        LOG,        // 记录日志
        TRACE,      // 追踪调用栈
        EVALUATE    // 执行表达式
    }
    
    public Breakpoint(String className, String methodName, int lineNumber) {
        this.id = idGenerator.incrementAndGet();
        this.className = className;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
        this.enabled = true;
        this.hitCount = 0;
        this.ignoreCount = 0;
        this.action = BreakpointAction.LOG;
    }

    public static Breakpoint createMethodEntry(String className, String methodName) {
        Breakpoint bp = new Breakpoint(className, methodName, -1);
        String key = generateKey(className, methodName, -1);
        breakpoints.put(key, bp);
        return bp;
    }

    public static Breakpoint createLineBreakpoint(String className, int lineNumber) {
        Breakpoint bp = new Breakpoint(className, null, lineNumber);
        String key = generateKey(className, null, lineNumber);
        breakpoints.put(key, bp);
        return bp;
    }

    private static String generateKey(String className, String methodName, int lineNumber) {
        if (lineNumber > 0) {
            return className + ":" + lineNumber;
        } else {
            return className + "." + methodName;
        }
    }

    public static Map<String, Breakpoint> getAllBreakpoints() {
        return breakpoints;
    }

    public static boolean removeBreakpoint(long id) {
        return breakpoints.values().removeIf(bp -> bp.getId() == id);
    }

    public static void clearAllBreakpoints() {
        breakpoints.clear();
    }

    public boolean shouldTrigger() {
        if (!enabled) {
            return false;
        }
        
        hitCount++;
        
        if (hitCount <= ignoreCount) {
            return false;
        }
        
        if (condition != null && !condition.isEmpty()) {
        }
        
        return true;
    }

    public void onTrigger(Object context) {
        switch (action) {
            case LOG:
                System.out.printf("[BREAKPOINT #%d] Hit at %s.%s (line %d), hit count: %d%n",
                    id, className, methodName, lineNumber, hitCount);
                break;
            case TRACE:
                System.out.printf("[BREAKPOINT #%d] Stack trace at %s.%s:%n", id, className, methodName);
                Thread.currentThread().getStackTrace();
                for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                    System.out.println("    at " + element);
                }
                break;
            case EVALUATE:
                if (condition != null) {
                    System.out.printf("[BREAKPOINT #%d] Evaluating: %s%n", id, condition);
                }
                break;
            case SUSPEND:
                System.out.printf("[BREAKPOINT #%d] Suspend requested (requires debugger)%n", id);
                break;
        }
    }
    
    // Getters and Setters
    public long getId() { return id; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public int getLineNumber() { return lineNumber; }
    
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public int getHitCount() { return hitCount; }
    
    public int getIgnoreCount() { return ignoreCount; }
    public void setIgnoreCount(int ignoreCount) { this.ignoreCount = ignoreCount; }
    
    public BreakpointAction getAction() { return action; }
    public void setAction(BreakpointAction action) { this.action = action; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Breakpoint #%d [%s]: %s", id, enabled ? "ON" : "OFF", className));
        
        if (methodName != null) {
            sb.append(".").append(methodName);
        }
        if (lineNumber > 0) {
            sb.append(":").append(lineNumber);
        }
        
        sb.append(String.format(" (hits: %d, action: %s)", hitCount, action));
        
        if (condition != null) {
            sb.append(String.format(" [condition: %s]", condition));
        }
        
        return sb.toString();
    }
}

