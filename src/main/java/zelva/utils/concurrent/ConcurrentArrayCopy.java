package zelva.utils.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class ConcurrentArrayCopy<E> {
    private static final int MIN_CAPACITY = 0;

    volatile Object[] array;

    public ConcurrentArrayCopy(int size) {
        this.array = prepareArray(size);
    }

    public E get(int i) {
        for (Object[] arr = array;;) {
            Object f = arrayAt(arr, i);
            if (f instanceof TransferNode t) {
                arr = t.source.next;
            } else {
                return (E) f;
            }
        }
    }

    public E set(int i, E element) {
        Object[] arr = array;
        for (Object f;;) {
            if ((f = arrayAt(arr, i)) == element) {
                return element;
            } else if (f instanceof TransferNode t) {
                arr = helpTransfer(t);
            } else if (weakCasArrayAt(arr, i, f, element)) {
                return (E) f;
            }
        }
    }

    public E cae(int i, E c, E v) {
        Object[] arr = array;
        for (Object f;;) {
            if ((f = arrayAt(arr, i)) == c) {
                if (weakCasArrayAt(arr, i, c, v))
                    return c;
            } else if (f instanceof TransferNode t) {
                arr = helpTransfer(t);
            } else {
                return (E) f;
            }
        }
    }
    public boolean cas(int i, E c, E v) {
        return cae(i,c,v) == c;
    }


    public void resize(int length) {
        resize(0, 0, length);
    }

    public void resize(int srcOff, int dstOff, int length) {
        Object[] prepare = prepareArray(length);

        TransferSourceNode src = new TransferSourceNode(
                array, srcOff,
                prepare, dstOff);
        src.left.transfer();
        array = prepare;
    }

    private static Object[] helpTransfer(TransferNode t) {
        TransferSourceNode src = t.source;
        if (src.rightHelper == null) {
            (src.rightHelper = new RightTransferNode(src))
                    .transfer();
        }
        return t.source.next;
    }

    private static Object[] prepareArray(int size) {
        return new Object[Math.max(MIN_CAPACITY, size)];
    }

    public int size() {
        return array.length;
    }

    abstract static class TransferNode {

        final TransferSourceNode source;

        TransferNode(TransferSourceNode source) {
            this.source = source;
        }

        abstract void transfer();
    }

    static class TransferSourceNode {
        final int srcPos, destPos;
        final Object[] next;
        Object[] prev;

        final LeftTransferNode left; // main
        RightTransferNode rightHelper;

        TransferSourceNode(Object[] prev, int srcPos,
                           Object[] next, int destPos) {
            this.prev = prev;
            this.srcPos = srcPos;
            this.next = next;
            this.destPos = destPos;
            this.left = new LeftTransferNode(this);
        }

        boolean isDone() {
            return prev == null;
        }
        void postCompleted() {
            prev = null;
        }

        int transferBound(int size) {
            return Math.min(next.length, size);
        }
    }

    // helper
    static final class RightTransferNode extends TransferNode {

        RightTransferNode(TransferSourceNode source) {
            super(source);
        }

        @Override
        void transfer() {
            TransferSourceNode src = source;
            Object[] prev = src.prev, next = src.next;
            if (prev != null) {
                outer:
                for (int i = src.transferBound(prev.length) - 1,
                     srcPos = src.srcPos + i, destPos = src.destPos + i;
                     i >= 0; --i, --srcPos, --destPos) {

                    for (Object f; ; ) {
                        if (src.isDone()) {
                            return;
                        } else if ((f = arrayAt(prev, srcPos)) == null) {
                            if (weakCasArrayAt(prev, srcPos,
                                    null, this)) {
                                break;
                            }
                        } else if (f instanceof TransferNode t) {
                            if (t.source == src) {
                                if (f instanceof LeftTransferNode) { // finished
                                    break outer;
                                }
                                Thread.yield();
                                break;
                            }
                        } else {
                            setAt(next, destPos, f);
                            if (casArrayAt(prev, srcPos, f, this))
                                break;
                        }
                    }
                }
                src.postCompleted();
            }
        }
    }
    static final class LeftTransferNode extends TransferNode {

        LeftTransferNode(TransferSourceNode source) {
            super(source);
        }
        @Override
        public void transfer() {
            final TransferSourceNode src = source;

            Object[] next = src.next, shared = src.prev;

            if (shared != null) {
                outer:
                for (int i = 0, nz = next.length,
                     len = src.transferBound(shared.length),
                     srcPos = src.srcPos, destPos = src.destPos;
                     i < len; ++i, ++srcPos, ++destPos) {
                    for (Object f; ; ) {
                        if (src.isDone()) {
                            return;
                        } else if ((f = arrayAt(shared, srcPos))
                                == null) {
                            if (weakCasArrayAt(shared, srcPos,
                                    null, this)) {
                                break;
                            }
                        } else if (f instanceof TransferNode t) {
                            TransferSourceNode t_src = t.source;
                            if (t_src == src) {
                                if (f instanceof RightTransferNode) { // finished
                                    break outer;
                                }
                                break;
                            } else {
                                if ((shared = t_src.prev) == null) {
                                    shared = t_src.next;
                                }
                                len = t_src.transferBound(nz);
                            }
                        } else {
                            setAt(next, destPos, f); // check null
                            if (casArrayAt(shared, srcPos, f, this))
                                break;
                        }
                    }
                }
                src.postCompleted();
            }
        }
    }

    @Override
    public String toString() {
        Object[] arr = array;
        if (arr.length == 0)
            return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0;;) {
            for (Object f = arrayAt(arr, i);;) {
                if (f instanceof RightTransferNode t) {
                    arr = t.source.next; // filled
                    break;
                } else if (f instanceof TransferNode t) { // left
                    f = arrayAt(t.source.next, i);
                } else {
                    sb.append(f);
                    if (++i == arr.length) // last
                        return sb.append(']').toString();
                    sb.append(", ");
                    break;
                }
            }
        }
    }

    static Object arrayAt(Object[] arr, int i) {
        return AA.getAcquire(arr, i);
    }
    static void setAt(Object[] arr, int i, Object v) {
        AA.setRelease(arr, i, v);
    }
    static boolean weakCasArrayAt(Object[] arr, int i, Object c, Object v) {
        return AA.weakCompareAndSet(arr, i, c, v);
    }
    static boolean casArrayAt(Object[] arr, int i, Object c, Object v) {
        return AA.compareAndSet(arr, i, c, v);
    }

    private static final VarHandle AA
            = MethodHandles.arrayElementVarHandle(Object[].class);
}