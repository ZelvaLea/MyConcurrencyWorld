package zelva.utils.concurrent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Deprecated
public class ConcurrentArrayList<E> {
    private final ConcurrentMap<Integer, E> map
            = new ConcurrentHashMap<>();
    private final ConcurrentBitSet bitSet =
            new ConcurrentBitSet();
    private final AtomicInteger adder =
            new AtomicInteger();

    public void add(E element) {
        int i = adder.getAndIncrement();

        map.put(i, element);

        bitSet.set(i);
    }
    public void remove(int i) {
        i = bitSet.removeNextSetBit(i);
        if (i < 0) return;
        map.remove(i);
    }
    public E get(int i) {
        i = bitSet.nextSetBit(i);
        return map.get(i);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0;;) {
            int t = bitSet.nextSetBit(i);
            if (t < 0) break;
            builder.append(map.get(t)).append(' ');
            i = t + 1;
        }
        return builder.toString();
    }
}
