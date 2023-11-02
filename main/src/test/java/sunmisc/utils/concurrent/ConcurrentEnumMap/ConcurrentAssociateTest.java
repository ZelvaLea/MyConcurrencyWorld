package sunmisc.utils.concurrent.ConcurrentEnumMap;


import org.testng.annotations.Test;
import sunmisc.utils.concurrent.maps.ConcurrentEnumMap;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Test
public class ConcurrentAssociateTest {

    /** Maximum time (in seconds) to wait for a test method to complete. */
    private static final int TIMEOUT = Integer.getInteger("timeout", 200);

    /** The number of entries for each thread to place in a map. */
    private static final int N = Integer.getInteger("n", 128);

    /** The number of iterations of the test. */
    private static final int I = Integer.getInteger("i", 64);


    @Test
    public void testPut() throws Throwable {
        test("CEM.put", (m, o) -> m.put(o, o));
    }

    @Test
    public void testCompute() throws Throwable {
        test("CEM.compute", (m, o) -> m.compute(o, (k, v) -> o));
    }

    @Test
    public void testComputeIfAbsent() throws Throwable {
        test("CEM.computeIfAbsent", (m, o) -> m.computeIfAbsent(o, (k) -> o));
    }

    @Test
    public void testMerge() throws Throwable {
        test("CEM.merge", (m, o) -> m.merge(o, o, (v1, v2) -> v1));
    }

    @Test
    public void testPutAll() throws Throwable {
        test("CEM.putAll", (m, o) -> {
            Map<TestEnum, Object> hm = new HashMap<>();
            hm.put(o, o);
            m.putAll(hm);
        });
    }

    private static void
    test(String desc, BiConsumer<ConcurrentMap<TestEnum, Object>, TestEnum> associator) throws Throwable {
        for (int i = 0; i < I; i++) {
            testOnce(desc, associator);
        }
    }

    static class AssociationFailure extends RuntimeException {
        AssociationFailure(String message) {
            super(message);
        }
    }

    private static void
    testOnce(String desc, BiConsumer<ConcurrentMap<TestEnum, Object>, TestEnum> associator) throws Throwable {
        ConcurrentMap<TestEnum, Object> m
                = new ConcurrentEnumMap<>(TestEnum.class);
        CountDownLatch s = new CountDownLatch(1);

        Supplier<Runnable> sr = () -> () -> {
            try {
                if (!s.await(TIMEOUT, TimeUnit.SECONDS)) {
                    dumpTestThreads();
                    throw new AssertionError("timed out");
                }
            }
            catch (InterruptedException e) {
            }

            for (int i = 0; i < N; i++) {
                TestEnum o = TestEnum.rand();

                associator.accept(m, o);
                if (!m.containsKey(o)) {
                    throw new AssociationFailure(desc + " failed: entry does not exist");
                }
            }
        };

        // Bound concurrency to avoid degenerate performance
        int ps = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        Stream<CompletableFuture<Void>> runners = IntStream.range(0, ps)
                .mapToObj(_ -> sr.get())
                .map(CompletableFuture::runAsync);

        CompletableFuture<Void> all = CompletableFuture.allOf(
                runners.toArray(CompletableFuture[]::new));

        // Trigger the runners to start associating
        s.countDown();

        try {
            all.get(TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            dumpTestThreads();
            throw e;
        } catch (Throwable e) {
            dumpTestThreads();
            Throwable cause = e.getCause();
            if (cause instanceof AssociationFailure) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * A debugging tool to print stack traces of most threads, as jstack does.
     * Uninteresting threads are filtered out.
     */
    static void dumpTestThreads() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        System.err.println("------ stacktrace dump start ------");
        for (ThreadInfo info : threadMXBean.dumpAllThreads(true, true)) {
            final String name = info.getThreadName();
            String lockName;
            if ("Signal Dispatcher".equals(name))
                continue;
            if ("Reference Handler".equals(name)
                    && (lockName = info.getLockName()) != null
                    && lockName.startsWith("java.lang.ref.Reference$Lock"))
                continue;
            if ("Finalizer".equals(name)
                    && (lockName = info.getLockName()) != null
                    && lockName.startsWith("java.lang.ref.ReferenceQueue$Lock"))
                continue;
            System.err.print(info);
        }
        System.err.println("------ stacktrace dump end ------");
    }
}