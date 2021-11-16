package zelva.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A specialized implementation of the map for use with enumeration type keys.
 * All keys in the enumeration map must belong to the same enumeration type,
 * which is explicitly or implicitly specified when creating the map.
 * Enumeration maps are internally represented as arrays.
 * This view is extremely compact and efficient.
 * Enumeration maps are maintained in the natural order of their keys
 * (the order in which enumeration constants are declared).
 * This is reflected in the iterators returned by the collection views
 * (keySet (), entrySet() and values ()).
 * The iterators returned by the collection views are poorly consistent:
 * they will never throw a ConcurrentModificationException
 * and may or may not show the effects of any map changes,
 * which occur during the execution of the iteration.
 * Null keys are not allowed.
 * Attempts to insert a null key will cause a NullPointerException.
 * However, attempts to check for the presence of a null key or delete it will work properly.
 * Zero values are allowed.
 * This map differs from EnumMap in that it is thread-safe
 * and scales well
 *
 * @since 9
 * @author JDIALIA
 *
 * Type parameters:
 * <K> – the type of keys maintained by this map
 * <V> – the type of mapped values
 */
public class ConcurrentEnumMap<K extends Enum<K>,V> extends AbstractMap<K,V>
        implements ConcurrentMap<K,V> {
    // An object of the class for the enumeration type of all the keys of this map
    private final Class<K> keyType;
    // element count
    private final LongAdder counter;
    // All the values comprising K
    private final K[] keys;
    // Array representation of this map. The ith element is the value to which universe[i]
    private final V[] table;

    // views
    private KeySetView<K,V> keySet;
    private ValuesView<K,V> values;
    private EntrySetView<K,V> entrySet;

    public ConcurrentEnumMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = keyType.getEnumConstants();
        this.counter = new LongAdder();
        this.table = (V[]) new Object[keys.length];
    }
    public ConcurrentEnumMap(Map<K, ? extends V> m) {
        if (m instanceof ConcurrentEnumMap) {
            ConcurrentEnumMap<K,V> em = (ConcurrentEnumMap<K,V>)m;
            this.keys = em.keys;
            this.keyType = em.keyType;
            this.table = em.table.clone();
            this.counter = em.counter;
        } else {
            if (m.isEmpty())
                throw new IllegalArgumentException("Specified map is empty");
            this.keys = (K[]) m.keySet().toArray(new Enum[0]);
            this.keyType = keys[0].getDeclaringClass();
            this.table = (V[]) new Object[keyType.getEnumConstants().length];
            this.counter = new LongAdder();
            int delta = 0;
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                Enum<?> key = e.getKey(); Object val = e.getValue();
                if (key.getDeclaringClass() != keyType)
                    throw new IllegalArgumentException("unsuitable type");
                if (AA.getAndSet(table, key.ordinal(), val) == null)
                    ++delta;
            }
            addCount(delta);
        }
    }

    private void addCount(long c) {
        if (c == 0L) return;
        counter.add(c);
    }

    private long getAdderCount() {
        return Math.max(counter.sum(), 0L);
    }

    @Override
    public V get(Object key) {
        return isValidKey(key)
                ? (V) AA.getAcquire(table, ((Enum<?>)key).ordinal())
                : null;
    }
    @Override
    public V put(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        Object prev = AA.getAndSet(table, key.ordinal(), value);
        if (prev == null)
            addCount(1L);
        return (V)prev;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (m.isEmpty())
            return;
        int delta = 0;
        if (m instanceof ConcurrentEnumMap) {
            ConcurrentEnumMap<?,?> em = (ConcurrentEnumMap<?,?>) m;
            if (em.keyType != keyType)
                throw new ClassCastException(em.keyType + " not equal to " + keyType);
            V[] tab = table;
            for (int i = 0, len = tab.length; i < len; ++i) {
                Object val = AA.getAcquire(em.table, i);
                if (val != null && AA.getAndSet(tab, i, val) == null) {
                    ++delta;
                }
            }
        } else {
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                Enum<?> key = e.getKey(); Object val = e.getValue();
                if (key.getDeclaringClass() != keyType)
                    continue;
                if (AA.getAndSet(table, key.ordinal(), val) == null)
                    ++delta;
            }
        }
        addCount(delta);
    }
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null)
            throw new NullPointerException();
        V[] tab = table;
        for (int i = 0, len = tab.length; i < len; ++i) {
            V v = (V) AA.getAcquire(tab, i);
            if (v == null)
                continue;
            action.accept(keys[i], v);
        }
    }

    @Override
    public void clear() {
        int delta = 0;
        V[] tab = table;
        for (int i = 0, len = tab.length; i < len; ++i) {
            if (AA.getAndSet(tab, i, null) != null) {
                --delta;
            }
        }
        addCount(delta);
    }

    @Override
    public V remove(Object key) {
        if (isValidKey(key)) {
            Object prev = AA.getAndSet(table, ((Enum<?>) key).ordinal(), null);
            if (prev != null)
                addCount(-1L);
            return (V) prev;
        }
        return null;
    }

    @Override
    public int size() {
        // let's handle the overflow
        long n = getAdderCount();
        return n >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;

    }

    @Override
    public boolean isEmpty() {
        return getAdderCount() == 0L;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (value == null)
            throw new NullPointerException();
        V[] tab = table;
        for (int i = 0, len = tab.length; i < len; ++i) {
            if (Objects.equals(AA.getAcquire(tab, i), value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        Object prev = AA.compareAndExchange(table, key.ordinal(), null, value);
        if (prev == null)
            addCount(1L);
        return (V)prev;
    }

    @Override
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int i = key.ordinal();
        V[] tab = table;
        for (V prev; ;) {
            prev = (V) AA.getAcquire(tab, i);
            V newVal = remappingFunction.apply(key, prev);
            if (prev == null && newVal == null) {
                return null;
                // strong CAS to minimize function call
            } else if (AA.compareAndSet(tab, i, prev, newVal)) {
                addCount(prev == null ? 1L : newVal == null ? -1L : 0);
                return newVal;
            }
        }
    }
    @Override
    public V computeIfAbsent(K key,
                             Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null)
            throw new NullPointerException();
        int i = key.ordinal();
        V[] tab = table;
        for (V prev, newVal; ;) {
            prev = (V) AA.getAcquire(tab, i);
            if (prev != null || (newVal = mappingFunction.apply(key)) == null) {
                return prev;
                // strong CAS to minimize function call
            } else if (AA.compareAndSet(tab, i, null, newVal)) {
                addCount(1L);
                return newVal;
            }
        }
    }
    @Override
    public V computeIfPresent(K key,
                              BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int i = key.ordinal();
        V[] tab = table;
        for (V prev; ;) {
            prev = (V) AA.getAcquire(tab, i);
            if (prev == null)
                return null;
            V newVal = remappingFunction.apply(key, prev);
            // strong CAS to minimize function call
            if (AA.compareAndSet(tab, i, prev, newVal)) {
                if (newVal == null)
                    addCount(-1L);
                return newVal;
            }
        }
    }
    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int i = key.ordinal();
        V[] tab = table;
        for (V prev; ;) {
            prev = (V) AA.getAcquire(tab, i);
            if (prev == null) {
                if (AA.weakCompareAndSet(tab, i, null, value)) {
                    addCount(1L);
                    return value;
                } else {
                    continue;
                }
            }
            V newVal = remappingFunction.apply(prev, value);
            if (AA.compareAndSet(tab, i, prev, newVal)) {
                if (newVal == null)
                    addCount(-1L);
                return newVal;
            }
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (value == null)
            throw new NullPointerException();
        if (isValidKey(key)) {
            int i = ((Enum<?>) key).ordinal();
            return AA.compareAndSet(table, i, value, null);
        }
        return false;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return AA.compareAndSet(table, key.ordinal(), oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        return put(key, value);
    }

    // the race that is here will not destroy anything for us
    @Override
    public Set<K> keySet() {
        KeySetView<K,V> ks;
        if ((ks = keySet) != null) return ks;
        return keySet = new KeySetView<>(this);
    }

    @Override
    public Collection<V> values() {
        ValuesView<K,V> vs;
        if ((vs = values) != null) return vs;
        return values = new ValuesView<>(this);
    }

    @Override
    public Set<Entry<K,V>> entrySet() {
        EntrySetView<K,V> es;
        if ((es = entrySet) != null) return es;
        return entrySet = new EntrySetView<>(this);
    }

    /* --------------------- Views --------------------- */

    private static class KeySetView<K extends Enum<K>,V>
            extends AbstractSet<K> {
        private final ConcurrentEnumMap<K,V> map;

        KeySetView(ConcurrentEnumMap<K,V> map) {
            this.map = map;
        }
        @Override
        public Iterator<K> iterator() {
            return new KeyIterator<>(map);
        }
        @Override
        public boolean contains(Object o) {
            return map.containsKey(o);
        }
        @Override
        public void clear() {
            map.clear();
        }
        @Override
        public int size() {
            return map.size();
        }
        @Override
        public boolean remove(Object o) {
            return map.remove(o) != null;
        }
    }

    private static class ValuesView<K extends Enum<K>,V>
            extends AbstractCollection<V> {
        private final ConcurrentEnumMap<? super K, V> map;
        ValuesView(ConcurrentEnumMap<? super K, V> map) {
            this.map = map;
        }
        @Override
        public Iterator<V> iterator() {
            return new ValueIterator<>(map);
        }
        @Override
        public int size() {
            return map.size();
        }
        @Override
        public boolean contains(Object o) {
            return map.containsValue(o);
        }
        @Override
        public boolean remove(Object o) {
            if (o == null)
                throw new NullPointerException();
            V[] tab = map.table;
            for (int i = 0, len = tab.length; i < len; ++i) {
                Object e = AA.getAcquire(tab, i);
                if (o.equals(e) && AA.weakCompareAndSet(tab, i, e, null)) {
                    map.addCount(-1L);
                    return true;
                }
            }
            return false;
        }
        @Override
        public void clear() {
            map.clear();
        }
    }
    private static class EntrySetView<K extends Enum<K>,V>
            extends AbstractSet<Map.Entry<K,V>> {
        private final ConcurrentEnumMap<K,V> map;

        EntrySetView(ConcurrentEnumMap<K,V> map) {
            this.map = map;
        }
        @Override
        public Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator<>(map);
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> entry = (Map.Entry<?,?>) o;
                return map.containsKey(entry.getKey());
            }
            return false;
        }
        @Override
        public boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> entry = (Map.Entry<?,?>) o;
                return map.remove(entry.getKey(), entry.getValue());
            }
            return false;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public void clear() {
            map.clear();
        }
    }
    private static class KeyIterator<K extends Enum<K>,V>
            extends EnumMapIterator<K,V,K> {
        KeyIterator(ConcurrentEnumMap<K,V> map) {
            super(map);
        }
        @Override
        public K next() {
            if (!hasNext())
                throw new NoSuchElementException();
            return map.keys[index];
        }
    }

    private static class ValueIterator<K extends Enum<K>,V>
            extends EnumMapIterator<K,V,V> {
        ValueIterator(ConcurrentEnumMap<K,V> map) {
            super(map);
        }

        @Override
        public V next() {
            if (!hasNext())
                throw new NoSuchElementException();
            return (V) AA.getAcquire(map.table, index++);
        }
    }

    private static class EntryIterator<K extends Enum<K>,V>
            extends EnumMapIterator<K,V,Map.Entry<K,V>> {
        EntryIterator(ConcurrentEnumMap<K,V> map) {
            super(map);
        }
        @Override
        public Map.Entry<K,V> next() {
            if (!hasNext())
                throw new NoSuchElementException();
            int c = index;
            V e = (V) AA.getAcquire(map.table, c);
            index = c + 1;
            return new MapEntry<>(map.keys[c], e, map);
        }
    }

    private abstract static class EnumMapIterator<K extends Enum<K>,V,E>
            implements Iterator<E> {
        final ConcurrentEnumMap<K,V> map;
        int index;

        EnumMapIterator(ConcurrentEnumMap<K,V> map) {
            this.map = map;
        }

        @Override
        public boolean hasNext() {
            V[] tab = map.table;
            int len = tab.length;
            for (int i = index; i < len; ++i) {
                if ((V) AA.getAcquire(tab, index) == null) {
                    index++;
                }
            }
            return index != len;
        }

        @Override
        public void remove() {
            if (AA.getAndSet(map.table, index, null) != null)
                map.addCount(-1L);
        }
    }

    private static final class MapEntry<K extends Enum<K>,V>
            implements Map.Entry<K,V> {
        final K key; // non-null
        V val;       // non-null
        final ConcurrentEnumMap<? super K, ? super V> map;
        MapEntry(K key, V val, ConcurrentEnumMap<? super K, ? super V> map) {
            this.key = key;
            this.val = val;
            this.map = map;
        }
        @Override
        public K getKey() {
            return key;
        }
        @Override
        public V getValue() {
            return val;
        }
        @Override
        public int hashCode() {
            return key.hashCode() ^ val.hashCode();
        }
        @Override
        public String toString() {
            return key.toString() + ' ' + val.toString();
        }
        @Override
        public boolean equals(Object o) {
            Object k, v, v1; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (v1 = val) || v.equals(v1)));
        }

        @Override
        public V setValue(V value) {
            if (value == null) throw new NullPointerException();
            V v = val;
            val = value;
            map.put(key, value);
            return v;
        }
    }

    @Override
    public int hashCode() {
        int h = 0;
        V[] tab = table;
        for (int i = 0, len = tab.length; i < len; i++) {
            V e = (V) AA.getAcquire(tab, i);
            if (e == null)
                continue;
            h += keys[i].hashCode() ^ e.hashCode();
        }
        return h;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o instanceof ConcurrentEnumMap)  {
            ConcurrentEnumMap<?,?> em = (ConcurrentEnumMap<?,?>) o;
            if (keyType != em.keyType || em.size() != size())
                return false;
            V[] tab = table;
            for (int i = 0, len = tab.length; i < len; i++) {
                V e1 = (V) AA.getAcquire(tab, i),
                        e2 = (V) AA.getAcquire(em.table, i);
                if (!Objects.equals(e1, e2)) {
                    return false;
                }
            }
            return true;
        } else if (!(o instanceof Map)) {
            return false;
        }
        Map<?,?> m = (Map<?,?>)o;
        if (size() != m.size())
            return false;
        for (Map.Entry<?,?> e : m.entrySet()) {
            Object mv = e.getValue();
            V v = get(e.getKey());
            if (v == null || !v.equals(mv)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidKey(Object key) {
        if (key == null)
            throw new NullPointerException();
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    // VarHandle mechanics
    private static final VarHandle AA
            = MethodHandles.arrayElementVarHandle(Object[].class);
}
