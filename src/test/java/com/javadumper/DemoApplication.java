package com.javadumper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
/**
 * @author Absurdity 457676887
 * @since 26/01/19
 */
public class DemoApplication {
    
    private static final List<byte[]> memoryHolder = new ArrayList<>();
    private static volatile boolean running = true;
    
    public static void main(String[] args) throws Exception {
        System.out.println("Demo Application Started");
        System.out.println("PID: " + ProcessHandle.current().pid());
        System.out.println("Press Ctrl+C to stop\n");
        
        Thread worker1 = new Thread(() -> doWork("Worker-1"), "Worker-1");
        Thread worker2 = new Thread(() -> doWork("Worker-2"), "Worker-2");
        Thread memoryThread = new Thread(DemoApplication::consumeMemory, "MemoryConsumer");
        
        worker1.start();
        worker2.start();
        memoryThread.start();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            running = false;
        }));
        
        while (running) {
            Thread.sleep(1000);
        }
        
        worker1.join();
        worker2.join();
        memoryThread.join();
        
        System.out.println("Demo Application Stopped");
    }

    private static void doWork(String name) {
        int counter = 0;
        while (running) {
            try {
                calculateSomething(counter++);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println(name + " finished with count: " + counter);
    }

    private static long calculateSomething(int input) {
        long result = 0;
        for (int i = 0; i < 10000; i++) {
            result += Math.sqrt(i * input);
        }
        return result;
    }

    private static void consumeMemory() {
        while (running) {
            try {
                if (memoryHolder.size() < 100) {
                    memoryHolder.add(new byte[1024 * 100]); // 100KB
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("Memory consumer finished, held " + memoryHolder.size() + " blocks");
    }

    public void testMethod(String param1, int param2) {
        System.out.println("testMethod called with: " + param1 + ", " + param2);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void createDeadlock() {
        final Object lock1 = new Object();
        final Object lock2 = new Object();
        
        Thread t1 = new Thread(() -> {
            synchronized (lock1) {
                System.out.println("Thread 1: Holding lock 1...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                System.out.println("Thread 1: Waiting for lock 2...");
                synchronized (lock2) {
                    System.out.println("Thread 1: Holding lock 1 & 2...");
                }
            }
        }, "DeadlockThread-1");
        
        Thread t2 = new Thread(() -> {
            synchronized (lock2) {
                System.out.println("Thread 2: Holding lock 2...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                System.out.println("Thread 2: Waiting for lock 1...");
                synchronized (lock1) {
                    System.out.println("Thread 2: Holding lock 2 & 1...");
                }
            }
        }, "DeadlockThread-2");
        
        t1.start();
        t2.start();
    }
}

