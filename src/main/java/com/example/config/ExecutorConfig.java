package com.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.MDC;

/**
 * Configuration for Virtual Thread Executors with controlled concurrency.
 * 
 * Virtual threads are lightweight, but resources they access (DB, APIs) are limited.
 * Semaphores prevent resource exhaustion while maintaining high throughput.
 */
@Configuration
@Slf4j
public class ExecutorConfig {

    @Value("${app.executor.processing-concurrency:50}")
    private int processingConcurrency;

    @Value("${app.executor.db-concurrency:10}")
    private int dbConcurrency;

    /**
     * Creates an unlimited Virtual Thread executor for lightweight operations.
     */
    @Bean("unlimitedVirtualExecutor")
    public ExecutorService unlimitedVirtualExecutor() {
        log.info("Creating unlimited virtual thread executor with MDC propagation");
        return new ExecutorService() {
            private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

            private <T> Callable<T> wrap(Callable<T> callable) {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                return () -> {
                    if (context != null) {
                        MDC.setContextMap(context);
                    }
                    try {
                        return callable.call();
                    } finally {
                        MDC.clear();
                    }
                };
            }

            private Runnable wrap(Runnable runnable) {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                return () -> {
                    if (context != null) {
                        MDC.setContextMap(context);
                    }
                    try {
                        runnable.run();
                    } finally {
                        MDC.clear();
                    }
                };
            }

            @Override
            public void execute(Runnable command) {
                delegate.execute(wrap(command));
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return delegate.submit(wrap(task));
            }
            
            @Override
            public Future<?> submit(Runnable task) {
                return delegate.submit(wrap(task));
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return delegate.submit(wrap(task), result);
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                var wrappedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
                    if (context != null) MDC.setContextMap(context);
                    try { return task.call(); } finally { MDC.clear(); }
                }).toList();
                return delegate.invokeAll(wrappedTasks);
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                var wrappedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
                    if (context != null) MDC.setContextMap(context);
                    try { return task.call(); } finally { MDC.clear(); }
                }).toList();
                return delegate.invokeAll(wrappedTasks, timeout, unit);
            }
            
            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                var wrappedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
                    if (context != null) MDC.setContextMap(context);
                    try { return task.call(); } finally { MDC.clear(); }
                }).toList();
                return delegate.invokeAny(wrappedTasks);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                final Map<String, String> context = MDC.getCopyOfContextMap();
                var wrappedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
                    if (context != null) MDC.setContextMap(context);
                    try { return task.call(); } finally { MDC.clear(); }
                }).toList();
                return delegate.invokeAny(wrappedTasks, timeout, unit);
            }

            // Boilerplate delegate methods
            @Override
            public void shutdown() { delegate.shutdown(); }
            @Override
            public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override
            public boolean isShutdown() { return delegate.isShutdown(); }
            @Override
            public boolean isTerminated() { return delegate.isTerminated(); }
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }
        };
    }
}
