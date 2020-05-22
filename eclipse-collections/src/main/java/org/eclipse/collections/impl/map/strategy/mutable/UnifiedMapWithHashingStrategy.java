/*
 * Copyright (c) 2018 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.map.strategy.mutable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.collections.api.block.HashingStrategy;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.api.block.procedure.primitive.ObjectIntProcedure;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.UnsortedMapIterable;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.block.factory.Predicates;
import org.eclipse.collections.impl.block.procedure.MapCollectProcedure;
import org.eclipse.collections.impl.factory.HashingStrategyMaps;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.AbstractMutableMap;
import org.eclipse.collections.impl.map.mutable.AbstractUnifiedMap;
import org.eclipse.collections.impl.parallel.BatchIterable;
import org.eclipse.collections.impl.set.strategy.mutable.UnifiedSetWithHashingStrategy;
import org.eclipse.collections.impl.tuple.ImmutableEntry;
import org.eclipse.collections.impl.utility.ArrayIterate;
import org.eclipse.collections.impl.utility.Iterate;

/**
 * UnifiedMapWithHashingStrategy stores key/value pairs in a single array, where alternate slots are keys and values.
 * This is nicer to CPU caches as consecutive memory addresses are very cheap to access. Entry objects are not stored in the
 * table like in java.util.HashMap. Instead of trying to deal with collisions in the main array using Entry objects,
 * we put a special object in the key slot and put a regular Object[] in the value slot. The array contains the key value
 * pairs in consecutive slots, just like the main array, but it's a linear list with no hashing.
 * <p>
 * The difference between UnifiedMap and UnifiedMapWithHashingStrategy is that a HashingStrategy based UnifiedMap
 * does not rely on the hashCode or equality of the object at the key, but instead relies on a HashingStrategy
 * implementation provided by a developer to compute the hashCode and equals for the objects stored in the map.
 */

@SuppressWarnings("ObjectEquality")
public class UnifiedMapWithHashingStrategy<K, V>
        extends AbstractUnifiedMap<K, V>
{
    private static final long serialVersionUID = 1L;

    protected HashingStrategy<? super K> hashingStrategy;

    /**
     * @deprecated No argument default constructor used for serialization. Instantiating an UnifiedMapWithHashingStrategyMultimap with
     * this constructor will have a null hashingStrategy and throw NullPointerException when used.
     */
    @Deprecated
    public UnifiedMapWithHashingStrategy()
    {
    }

    public UnifiedMapWithHashingStrategy(HashingStrategy<? super K> hashingStrategy)
    {
        this.hashingStrategy = hashingStrategy;
        this.allocate(DEFAULT_INITIAL_CAPACITY << 1);
    }

    public UnifiedMapWithHashingStrategy(HashingStrategy<? super K> hashingStrategy, int initialCapacity)
    {
        this(hashingStrategy, initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public UnifiedMapWithHashingStrategy(HashingStrategy<? super K> hashingStrategy, int initialCapacity, float loadFactor)
    {
        this.validatePrameters(initialCapacity, loadFactor);

        this.hashingStrategy = hashingStrategy;
        this.loadFactor = loadFactor;
        this.init(this.fastCeil(initialCapacity / loadFactor));
    }

    public UnifiedMapWithHashingStrategy(HashingStrategy<? super K> hashingStrategy, Map<? extends K, ? extends V> map)
    {
        this(hashingStrategy, Math.max(map.size(), DEFAULT_INITIAL_CAPACITY), DEFAULT_LOAD_FACTOR);

        this.putAll(map);
    }

    public UnifiedMapWithHashingStrategy(HashingStrategy<? super K> hashingStrategy, Pair<K, V>... pairs)
    {
        this(hashingStrategy, Math.max(pairs.length, DEFAULT_INITIAL_CAPACITY), DEFAULT_LOAD_FACTOR);
        ArrayIterate.forEach(pairs, new MapCollectProcedure<Pair<K, V>, K, V>(
                this,
                Functions.firstOfPair(),
                Functions.secondOfPair()));
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMap(HashingStrategy<? super K> hashingStrategy)
    {
        return new UnifiedMapWithHashingStrategy<>(hashingStrategy);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMap(
            HashingStrategy<? super K> hashingStrategy,
            int size)
    {
        return new UnifiedMapWithHashingStrategy<>(hashingStrategy, size);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMap(
            HashingStrategy<? super K> hashingStrategy,
            int size,
            float loadFactor)
    {
        return new UnifiedMapWithHashingStrategy<>(hashingStrategy, size, loadFactor);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMap(
            HashingStrategy<? super K> hashingStrategy,
            Map<? extends K, ? extends V> map)
    {
        return new UnifiedMapWithHashingStrategy<>(hashingStrategy, map);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMapWith(HashingStrategy<? super K> hashingStrategy, Iterable<Pair<K, V>> inputIterable)
    {
        UnifiedMapWithHashingStrategy<K, V> outputMap = UnifiedMapWithHashingStrategy.newMap(hashingStrategy);

        for (Pair<K, V> single : inputIterable)
        {
            outputMap.add(single);
        }
        return outputMap;
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMap(UnifiedMapWithHashingStrategy<K, V> map)
    {
        return new UnifiedMapWithHashingStrategy<>(map.hashingStrategy, map);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newMapWith(
            HashingStrategy<? super K> hashingStrategy,
            Pair<K, V>... pairs)
    {
        return new UnifiedMapWithHashingStrategy<>(hashingStrategy, pairs);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newWithKeysValues(
            HashingStrategy<? super K> hashingStrategy,
            K key, V value)
    {
        return new UnifiedMapWithHashingStrategy<K, V>(hashingStrategy, 1).withKeysValues(key, value);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newWithKeysValues(
            HashingStrategy<? super K> hashingStrategy,
            K key1, V value1,
            K key2, V value2)
    {
        return new UnifiedMapWithHashingStrategy<K, V>(hashingStrategy, 2).withKeysValues(key1, value1, key2, value2);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newWithKeysValues(
            HashingStrategy<? super K> hashingStrategy,
            K key1, V value1,
            K key2, V value2,
            K key3, V value3)
    {
        return new UnifiedMapWithHashingStrategy<K, V>(hashingStrategy, 3).withKeysValues(key1, value1, key2, value2, key3, value3);
    }

    public static <K, V> UnifiedMapWithHashingStrategy<K, V> newWithKeysValues(
            HashingStrategy<? super K> hashingStrategy,
            K key1, V value1,
            K key2, V value2,
            K key3, V value3,
            K key4, V value4)
    {
        return new UnifiedMapWithHashingStrategy<K, V>(hashingStrategy, 4).withKeysValues(key1, value1, key2, value2, key3, value3, key4, value4);
    }

    public UnifiedMapWithHashingStrategy<K, V> withKeysValues(K key, V value)
    {
        this.put(key, value);
        return this;
    }

    public UnifiedMapWithHashingStrategy<K, V> withKeysValues(K key1, V value1, K key2, V value2)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        return this;
    }

    public UnifiedMapWithHashingStrategy<K, V> withKeysValues(K key1, V value1, K key2, V value2, K key3, V value3)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        this.put(key3, value3);
        return this;
    }

    public UnifiedMapWithHashingStrategy<K, V> withKeysValues(K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        this.put(key3, value3);
        this.put(key4, value4);
        return this;
    }

    public HashingStrategy<? super K> hashingStrategy()
    {
        return this.hashingStrategy;
    }

    @Override
    public UnifiedMapWithHashingStrategy<K, V> clone()
    {
        return new UnifiedMapWithHashingStrategy<>(this.hashingStrategy, this);
    }

    @Override
    public MutableMap<K, V> newEmpty()
    {
        return new UnifiedMapWithHashingStrategy<>(this.hashingStrategy);
    }

    @Override
    public MutableMap<K, V> newEmpty(int capacity)
    {
        return new UnifiedMapWithHashingStrategy<>(this.hashingStrategy, capacity, this.loadFactor);
    }

    @Override
    protected int index(K key)
    {
        // This function ensures that hashCodes that differ only by
        // constant multiples at each bit position have a bounded
        // number of collisions (approximately 8 at default load factor).
        int h = this.hashingStrategy.computeHashCode(key);
        h ^= h >>> 20 ^ h >>> 12;
        h ^= h >>> 7 ^ h >>> 4;
        return (h & (this.table.length >> 1) - 1) << 1;
    }

    @Override
    public Set<Entry<K, V>> entrySet()
    {
        return new EntrySet();
    }

    @Override
    protected int computeHashCodeOfKey(K object)
    {
        return this.hashingStrategy.computeHashCode(this.nonSentinel(object));
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
    {
        this.hashingStrategy = (HashingStrategy<? super K>) in.readObject();
        super.readExternal(in);
    }

    //@Override
    //public void writeExternal(ObjectOutput out) throws IOException
    //{
    //    out.writeObject(this.hashingStrategy);
    //    super.writeExternal(out);
    //}

    @Override
    public void writeExternal(ObjectOutput out) throws IOException
    {
        out.writeObject(this.hashingStrategy);
        out.writeInt(this.size());
        out.writeFloat(this.loadFactor);
        for (int i = 0; i < this.table.length; i += 2)
        {
            Object o = this.table[i];
            if (o != null)
            {
                if (o == CHAINED_KEY)
                {
                    this.writeExternalChain(out, (Object[]) this.table[i + 1]);
                }
                else
                {
                    out.writeObject(this.nonSentinel(o));
                    out.writeObject(this.table[i + 1]);
                }
            }
        }
    }

    private void writeExternalChain(ObjectOutput out, Object[] chain) throws IOException
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object cur = chain[i];
            if (cur == null)
            {
                return;
            }
            out.writeObject(this.nonSentinel(cur));
            out.writeObject(chain[i + 1]);
        }
    }

    @Override
    protected MutableSet<K> newSetWithMatchingHashingStrategy()
    {
        return UnifiedSetWithHashingStrategy.newSet(this.hashingStrategy, this.size());
    }

    protected class EntrySet implements Set<Entry<K, V>>, Serializable, BatchIterable<Entry<K, V>>
    {
        private static final long serialVersionUID = 1L;
        private transient WeakReference<UnifiedMapWithHashingStrategy<K, V>> holder = new WeakReference<>(UnifiedMapWithHashingStrategy.this);

        @Override
        public boolean add(Entry<K, V> entry)
        {
            throw new UnsupportedOperationException("Cannot call add() on " + this.getClass().getSimpleName());
        }

        @Override
        public boolean addAll(Collection<? extends Entry<K, V>> collection)
        {
            throw new UnsupportedOperationException("Cannot call addAll() on " + this.getClass().getSimpleName());
        }

        @Override
        public void clear()
        {
            UnifiedMapWithHashingStrategy.this.clear();
        }

        public boolean containsEntry(Entry<?, ?> entry)
        {
            return this.getEntry(entry) != null;
        }

        private Entry<K, V> getEntry(Entry<?, ?> entry)
        {
            K key = (K) entry.getKey();
            V value = (V) entry.getValue();
            int index = UnifiedMapWithHashingStrategy.this.index(key);

            Object cur = UnifiedMapWithHashingStrategy.this.table[index];
            Object curValue = UnifiedMapWithHashingStrategy.this.table[index + 1];
            if (cur == CHAINED_KEY)
            {
                return this.chainGetEntry((Object[]) curValue, key, value);
            }
            if (cur == null)
            {
                return null;
            }
            if (UnifiedMapWithHashingStrategy.this.nonNullTableObjectEquals(cur, key))
            {
                if (UnifiedMapWithHashingStrategy.nullSafeEquals(value, curValue))
                {
                    return ImmutableEntry.of(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) curValue);
                }
            }
            return null;
        }

        private Entry<K, V> chainGetEntry(Object[] chain, K key, V value)
        {
            for (int i = 0; i < chain.length; i += 2)
            {
                Object cur = chain[i];
                if (cur == null)
                {
                    return null;
                }
                if (UnifiedMapWithHashingStrategy.this.nonNullTableObjectEquals(cur, key))
                {
                    Object curValue = chain[i + 1];
                    if (UnifiedMapWithHashingStrategy.nullSafeEquals(value, curValue))
                    {
                        return ImmutableEntry.of(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) curValue);
                    }
                }
            }
            return null;
        }

        @Override
        public boolean contains(Object o)
        {
            return o instanceof Entry && this.containsEntry((Entry<?, ?>) o);
        }

        @Override
        public boolean containsAll(Collection<?> collection)
        {
            for (Object obj : collection)
            {
                if (!this.contains(obj))
                {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isEmpty()
        {
            return UnifiedMapWithHashingStrategy.this.isEmpty();
        }

        @Override
        public Iterator<Entry<K, V>> iterator()
        {
            return new EntrySetIterator(this.holder);
        }

        @Override
        public boolean remove(Object e)
        {
            if (!(e instanceof Entry))
            {
                return false;
            }
            Entry<?, ?> entry = (Entry<?, ?>) e;
            K key = (K) entry.getKey();
            V value = (V) entry.getValue();

            int index = UnifiedMapWithHashingStrategy.this.index(key);

            Object cur = UnifiedMapWithHashingStrategy.this.table[index];
            if (cur != null)
            {
                Object val = UnifiedMapWithHashingStrategy.this.table[index + 1];
                if (cur == CHAINED_KEY)
                {
                    return this.removeFromChain((Object[]) val, key, value, index);
                }
                if (UnifiedMapWithHashingStrategy.this.nonNullTableObjectEquals(cur, key) && UnifiedMapWithHashingStrategy.nullSafeEquals(value, val))
                {
                    UnifiedMapWithHashingStrategy.this.table[index] = null;
                    UnifiedMapWithHashingStrategy.this.table[index + 1] = null;
                    UnifiedMapWithHashingStrategy.this.occupied--;
                    return true;
                }
            }
            return false;
        }

        private boolean removeFromChain(Object[] chain, K key, V value, int index)
        {
            for (int i = 0; i < chain.length; i += 2)
            {
                Object k = chain[i];
                if (k == null)
                {
                    return false;
                }
                if (UnifiedMapWithHashingStrategy.this.nonNullTableObjectEquals(k, key))
                {
                    V val = (V) chain[i + 1];
                    if (UnifiedMapWithHashingStrategy.nullSafeEquals(val, value))
                    {
                        UnifiedMapWithHashingStrategy.this.overwriteWithLastElementFromChain(chain, index, i);
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean removeAll(Collection<?> collection)
        {
            boolean changed = false;
            for (Object obj : collection)
            {
                if (this.remove(obj))
                {
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> collection)
        {
            int retainedSize = collection.size();
            UnifiedMapWithHashingStrategy<K, V> retainedCopy = (UnifiedMapWithHashingStrategy<K, V>)
                    UnifiedMapWithHashingStrategy.this.newEmpty(retainedSize);
            for (Object obj : collection)
            {
                if (obj instanceof Entry)
                {
                    Entry<?, ?> otherEntry = (Entry<?, ?>) obj;
                    Entry<K, V> thisEntry = this.getEntry(otherEntry);
                    if (thisEntry != null)
                    {
                        retainedCopy.put(thisEntry.getKey(), thisEntry.getValue());
                    }
                }
            }
            if (retainedCopy.size() < this.size())
            {
                UnifiedMapWithHashingStrategy.this.maxSize = retainedCopy.maxSize;
                UnifiedMapWithHashingStrategy.this.occupied = retainedCopy.occupied;
                UnifiedMapWithHashingStrategy.this.table = retainedCopy.table;
                return true;
            }
            return false;
        }

        @Override
        public int size()
        {
            return UnifiedMapWithHashingStrategy.this.size();
        }

        @Override
        public void forEach(Procedure<? super Entry<K, V>> procedure)
        {
            for (int i = 0; i < UnifiedMapWithHashingStrategy.this.table.length; i += 2)
            {
                Object cur = UnifiedMapWithHashingStrategy.this.table[i];
                if (cur == CHAINED_KEY)
                {
                    this.chainedForEachEntry((Object[]) UnifiedMapWithHashingStrategy.this.table[i + 1], procedure);
                }
                else if (cur != null)
                {
                    procedure.value(ImmutableEntry.of(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) UnifiedMapWithHashingStrategy.this.table[i + 1]));
                }
            }
        }

        private void chainedForEachEntry(Object[] chain, Procedure<? super Entry<K, V>> procedure)
        {
            for (int i = 0; i < chain.length; i += 2)
            {
                Object cur = chain[i];
                if (cur == null)
                {
                    return;
                }
                procedure.value(ImmutableEntry.of(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) chain[i + 1]));
            }
        }

        @Override
        public int getBatchCount(int batchSize)
        {
            return UnifiedMapWithHashingStrategy.this.getBatchCount(batchSize);
        }

        @Override
        public void batchForEach(Procedure<? super Entry<K, V>> procedure, int sectionIndex, int sectionCount)
        {
            Object[] map = UnifiedMapWithHashingStrategy.this.table;
            int sectionSize = map.length / sectionCount;
            int start = sectionIndex * sectionSize;
            int end = sectionIndex == sectionCount - 1 ? map.length : start + sectionSize;
            if (start % 2 != 0)
            {
                start++;
            }
            for (int i = start; i < end; i += 2)
            {
                Object cur = map[i];
                if (cur == CHAINED_KEY)
                {
                    this.chainedForEachEntry((Object[]) map[i + 1], procedure);
                }
                else if (cur != null)
                {
                    procedure.value(ImmutableEntry.of(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) map[i + 1]));
                }
            }
        }

        protected void copyEntries(Object[] result)
        {
            Object[] table = UnifiedMapWithHashingStrategy.this.table;
            int count = 0;
            for (int i = 0; i < table.length; i += 2)
            {
                Object x = table[i];
                if (x != null)
                {
                    if (x == CHAINED_KEY)
                    {
                        Object[] chain = (Object[]) table[i + 1];
                        for (int j = 0; j < chain.length; j += 2)
                        {
                            Object cur = chain[j];
                            if (cur == null)
                            {
                                break;
                            }
                            result[count++] =
                                    new WeakBoundEntry<>(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) chain[j + 1], this.holder,
                                            UnifiedMapWithHashingStrategy.this.hashingStrategy);
                        }
                    }
                    else
                    {
                        result[count++] = new WeakBoundEntry<>(UnifiedMapWithHashingStrategy.this.nonSentinel(x), (V) table[i + 1], this.holder,
                                UnifiedMapWithHashingStrategy.this.hashingStrategy);
                    }
                }
            }
        }

        @Override
        public Object[] toArray()
        {
            Object[] result = new Object[UnifiedMapWithHashingStrategy.this.size()];
            this.copyEntries(result);
            return result;
        }

        @Override
        public <T> T[] toArray(T[] result)
        {
            int size = UnifiedMapWithHashingStrategy.this.size();
            if (result.length < size)
            {
                result = (T[]) Array.newInstance(result.getClass().getComponentType(), size);
            }
            this.copyEntries(result);
            if (size < result.length)
            {
                result[size] = null;
            }
            return result;
        }

        private void readObject(ObjectInputStream in)
                throws IOException, ClassNotFoundException
        {
            in.defaultReadObject();
            this.holder = new WeakReference<>(UnifiedMapWithHashingStrategy.this);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof Set)
            {
                Set<?> other = (Set<?>) obj;
                if (other.size() == this.size())
                {
                    return this.containsAll(other);
                }
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return UnifiedMapWithHashingStrategy.this.hashCode();
        }
    }

    protected class EntrySetIterator extends PositionalIterator<Entry<K, V>>
    {
        private final WeakReference<UnifiedMapWithHashingStrategy<K, V>> holder;

        protected EntrySetIterator(WeakReference<UnifiedMapWithHashingStrategy<K, V>> holder)
        {
            this.holder = holder;
        }

        protected Entry<K, V> nextFromChain()
        {
            Object[] chain = (Object[]) UnifiedMapWithHashingStrategy.this.table[this.position + 1];
            Object cur = chain[this.chainPosition];
            Object value = chain[this.chainPosition + 1];
            this.chainPosition += 2;
            if (this.chainPosition >= chain.length
                    || chain[this.chainPosition] == null)
            {
                this.chainPosition = 0;
                this.position += 2;
            }
            this.lastReturned = true;
            return new WeakBoundEntry<>(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) value, this.holder,
                    UnifiedMapWithHashingStrategy.this.hashingStrategy);
        }

        @Override
        public Entry<K, V> next()
        {
            if (!this.hasNext())
            {
                throw new NoSuchElementException("next() called, but the iterator is exhausted");
            }
            this.count++;
            Object[] table = UnifiedMapWithHashingStrategy.this.table;
            if (this.chainPosition != 0)
            {
                return this.nextFromChain();
            }
            while (table[this.position] == null)
            {
                this.position += 2;
            }
            Object cur = table[this.position];
            Object value = table[this.position + 1];
            if (cur == CHAINED_KEY)
            {
                return this.nextFromChain();
            }
            this.position += 2;
            this.lastReturned = true;
            return new WeakBoundEntry<>(UnifiedMapWithHashingStrategy.this.nonSentinel(cur), (V) value, this.holder,
                    UnifiedMapWithHashingStrategy.this.hashingStrategy);
        }
    }

    protected static class WeakBoundEntry<K, V> implements Map.Entry<K, V>
    {
        protected final K key;
        protected V value;
        protected final WeakReference<UnifiedMapWithHashingStrategy<K, V>> holder;
        protected final HashingStrategy<? super K> hashingStrategy;

        protected WeakBoundEntry(
                K key,
                V value,
                WeakReference<UnifiedMapWithHashingStrategy<K, V>> holder,
                HashingStrategy<? super K> hashingStrategy)
        {
            this.key = key;
            this.value = value;
            this.holder = holder;
            this.hashingStrategy = hashingStrategy;
        }

        @Override
        public K getKey()
        {
            return this.key;
        }

        @Override
        public V getValue()
        {
            return this.value;
        }

        @Override
        public V setValue(V value)
        {
            this.value = value;
            UnifiedMapWithHashingStrategy<K, V> map = this.holder.get();
            if (map != null && map.containsKey(this.key))
            {
                return map.put(this.key, value);
            }
            return null;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof Entry)
            {
                Entry<?, ?> other = (Entry<?, ?>) obj;
                K otherKey = (K) other.getKey();
                V otherValue = (V) other.getValue();
                return this.hashingStrategy.equals(this.key, otherKey)
                        && UnifiedMapWithHashingStrategy.nullSafeEquals(this.value, otherValue);
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return this.hashingStrategy.computeHashCode(this.key)
                    ^ (this.value == null ? 0 : this.value.hashCode());
        }

        @Override
        public String toString()
        {
            return this.key + "=" + this.value;
        }
    }

    @Override
    protected boolean nonNullTableObjectEquals(Object cur, K key)
    {
        return cur == key || (cur == NULL_KEY ? key == null : this.hashingStrategy.equals(this.nonSentinel(cur), key));
    }

    @Override
    public ImmutableMap<K, V> toImmutable()
    {
        return HashingStrategyMaps.immutable.withAll(this);
    }
}
