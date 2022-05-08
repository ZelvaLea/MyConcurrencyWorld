package bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import tests.AtomicTransferArrayTest;
import zelva.utils.MathUtils;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@State(Scope.Thread)
public class BenchAtomicResizeArray {
    AtomicTransferArrayTest.MyAtomicResizeArrayCopy myArray;
    volatile Integer[] array;


    public static void main7(String[] args) {
        Integer[] array = new Integer[]{1,2,3};
        Integer[] array1 = new Integer[]{1,2,3};
        int s = 0, d = 1;
        inflate(array, s, array, d, array.length-1);
        inflateInvert(array1, s, array1, d, array1.length-1);
        System.out.println(Arrays.toString(array)); // [1, 1, 2]
        System.out.println(Arrays.toString(array1)); // [1, 1, 2]
    }
    public static void inflate(Object[] src, int srcOff, Object[] dst, int dstOff, int len) {
        for (int i = 0; i < len; i++) {
            dst[dstOff++] = src[srcOff++];
        }
    }
    public static void inflateInvert(Object[] src, int srcOff, Object[] dst, int dstOff, int len) {
        srcOff += len-1;
        dstOff += len-1;
        for (int i = 0; i < len; i++) {
            dst[dstOff--] = src[srcOff--];
        }
    }
    public static void arraycopy(Object[] src, int srcPos,
                                 Object[] dest, int destPos,
                                 int length) {
        int size = length + srcPos + destPos;
        for (; srcPos < size; srcPos++, destPos++) {
            dest[destPos] = src[srcPos];
        }

    }

    public static void main02(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BenchAtomicResizeArray.class.getSimpleName())
                .syncIterations(false)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
    final AtomicInteger i1 = new AtomicInteger();
    final AtomicInteger i2 = new AtomicInteger();

    @Setup
    public void prepare() {
        myArray = new AtomicTransferArrayTest.MyAtomicResizeArrayCopy();
        array = new Integer[2];
    }

    @Benchmark
    public Integer growAtomicArray() {
        int i = size(i1);
        myArray.resize(i);
        return i;
    }

    @Benchmark
    public Integer growArrayIntrinsic() {
        int i = size(i2);
        synchronized (this) {
            array = Arrays.copyOf(array, i);
        }
        return i;
    }
    private static int size(AtomicInteger a) {
        int i = a.getAndIncrement();
        return (int) ((MathUtils._cos(i) + 2) * 10);
    }
    /*
BenchAtomicResizeArray.growArrayIntrinsic  thrpt    5  11716659,823 ± 3986127,596  ops/s
BenchAtomicResizeArray.growAtomicArray     thrpt    5    669605,318 ±  115536,898  ops/s
     */
}
