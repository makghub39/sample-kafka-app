package com.example.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ContextConfiguration(classes = {ExecutorConfig.class})
class ExecutorConfigTest {

    @Autowired
    @Qualifier("unlimitedVirtualExecutor")
    private ExecutorService unlimitedVirtualExecutor;

    private static final String TRACE_ID = "traceId";

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithExecute() throws Exception {
        testMdcPropagationWithExecute(unlimitedVirtualExecutor);
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithSubmit() throws Exception {
        testMdcPropagationWithSubmit(unlimitedVirtualExecutor);
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithInvokeAll() throws Exception {
        testMdcPropagationWithInvokeAll(unlimitedVirtualExecutor);
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithSubmitRunnable() throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        CompletableFuture<String> mdcValueFuture = new CompletableFuture<>();
        Future<?> future = unlimitedVirtualExecutor.submit(() -> mdcValueFuture.complete(MDC.get(TRACE_ID)));

        future.get(5, TimeUnit.SECONDS); // Wait for runnable to complete
        MDC.remove(TRACE_ID);

        assertThat(mdcValueFuture.get()).isEqualTo(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithSubmitRunnableWithResult() throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        final String result = "done";
        MDC.put(TRACE_ID, traceIdValue);

        CompletableFuture<String> mdcValueFuture = new CompletableFuture<>();
        Future<String> future = unlimitedVirtualExecutor.submit(() -> mdcValueFuture.complete(MDC.get(TRACE_ID)), result);

        String futureResult = future.get(5, TimeUnit.SECONDS);
        MDC.remove(TRACE_ID);

        assertThat(futureResult).isEqualTo(result);
        assertThat(mdcValueFuture.get()).isEqualTo(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithInvokeAllWithTimeout() throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        List<Callable<String>> tasks = IntStream.range(0, 5)
                .mapToObj(i -> (Callable<String>) () -> MDC.get(TRACE_ID))
                .collect(Collectors.toList());

        List<Future<String>> futures = unlimitedVirtualExecutor.invokeAll(tasks, 5, TimeUnit.SECONDS);

        MDC.remove(TRACE_ID);

        for (Future<String> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(traceIdValue);
        }
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithInvokeAny() throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        List<Callable<String>> tasks = IntStream.range(0, 5)
                .mapToObj(i -> (Callable<String>) () -> {
                    // Make one task slower so others can complete
                    if (i == 0) {
                        Thread.sleep(100);
                    }
                    return MDC.get(TRACE_ID);
                })
                .collect(Collectors.toList());

        String result = unlimitedVirtualExecutor.invokeAny(tasks);

        MDC.remove(TRACE_ID);

        assertThat(result).isEqualTo(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    @Test
    void shouldPropagateMdcToUnlimitedExecutorWithInvokeAnyWithTimeout() throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        List<Callable<String>> tasks = IntStream.range(0, 5)
                .mapToObj(i -> (Callable<String>) () -> {
                    if (i == 0) {
                        Thread.sleep(100);
                    }
                    return MDC.get(TRACE_ID);
                })
                .collect(Collectors.toList());

        String result = unlimitedVirtualExecutor.invokeAny(tasks, 5, TimeUnit.SECONDS);

        MDC.remove(TRACE_ID);

        assertThat(result).isEqualTo(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    private void testMdcPropagationWithExecute(ExecutorService executor) throws InterruptedException {
        final String traceIdValue = UUID.randomUUID().toString();
        final CountDownLatch latch = new CountDownLatch(1);
        final CompletableFuture<String> mdcValueFuture = new CompletableFuture<>();

        MDC.put(TRACE_ID, traceIdValue);

        executor.execute(() -> {
            try {
                mdcValueFuture.complete(MDC.get(TRACE_ID));
            } catch (Exception e) {
                mdcValueFuture.completeExceptionally(e);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        MDC.remove(TRACE_ID);

        assertThat(mdcValueFuture).isCompletedWithValue(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    private void testMdcPropagationWithSubmit(ExecutorService executor) throws Exception {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        Future<String> future = executor.submit(() -> MDC.get(TRACE_ID));

        String mdcValue = future.get(5, TimeUnit.SECONDS);
        MDC.remove(TRACE_ID);

        assertThat(mdcValue).isEqualTo(traceIdValue);
        assertThat(MDC.get(TRACE_ID)).isNull();
    }

    private void testMdcPropagationWithInvokeAll(ExecutorService executor) throws InterruptedException {
        final String traceIdValue = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceIdValue);

        List<Callable<String>> tasks = IntStream.range(0, 5)
                .mapToObj(i -> (Callable<String>) () -> MDC.get(TRACE_ID))
                .collect(Collectors.toList());

        List<Future<String>> futures = executor.invokeAll(tasks);

        MDC.remove(TRACE_ID);

        futures.forEach(future -> {
            try {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(traceIdValue);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(MDC.get(TRACE_ID)).isNull();
    }
}
