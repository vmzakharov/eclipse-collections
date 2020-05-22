/*
 * Copyright (c) 2018 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.map.mutable;

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
import java.util.Optional;
import java.util.Set;

import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.predicate.Predicate;
import org.eclipse.collections.api.block.predicate.Predicate2;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.api.block.procedure.primitive.ObjectIntProcedure;
import org.eclipse.collections.api.factory.Maps;
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
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.strategy.mutable.UnifiedMapWithHashingStrategy;
import org.eclipse.collections.impl.parallel.BatchIterable;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.tuple.ImmutableEntry;
import org.eclipse.collections.impl.tuple.Tuples;
import org.eclipse.collections.impl.utility.ArrayIterate;
import org.eclipse.collections.impl.utility.Iterate;

/**
 * UnifiedMap stores key/value pairs in a single array, where alternate slots are keys and values. This is nicer to CPU caches as
 * consecutive memory addresses are very cheap to access. Entry objects are not stored in the table like in java.util.HashMap.
 * Instead of trying to deal with collisions in the main array using Entry objects, we put a special object in
 * the key slot and put a regular Object[] in the value slot. The array contains the key value pairs in consecutive slots,
 * just like the main array, but it's a linear list with no hashing.
 * <p>
 * The final result is a Map implementation that's leaner than java.util.HashMap and faster than Trove's THashMap.
 * The best of both approaches unified together, and thus the name UnifiedMap.
 */

@SuppressWarnings("ObjectEquality")
public class UnifiedMap<K, V>
        extends AbstractUnifiedMap<K, V>
{
    private static final long serialVersionUID = 1L;

    public UnifiedMap()
    {
        this.allocate(DEFAULT_INITIAL_CAPACITY << 1);
    }

    public UnifiedMap(int initialCapacity)
    {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public UnifiedMap(int initialCapacity, float loadFactor)
    {
        this.validatePrameters(initialCapacity, loadFactor);

        this.loadFactor = loadFactor;
        this.init(this.fastCeil(initialCapacity / loadFactor));
    }

    public UnifiedMap(Map<? extends K, ? extends V> map)
    {
        this(Math.max(map.size(), DEFAULT_INITIAL_CAPACITY), DEFAULT_LOAD_FACTOR);

        this.putAll(map);
    }

    public UnifiedMap(Pair<K, V>... pairs)
    {
        this(Math.max(pairs.length, DEFAULT_INITIAL_CAPACITY), DEFAULT_LOAD_FACTOR);
        ArrayIterate.forEach(pairs, new MapCollectProcedure<Pair<K, V>, K, V>(
                this,
                Functions.firstOfPair(),
                Functions.secondOfPair()));
    }

    public static <K, V> UnifiedMap<K, V> newMap()
    {
        return new UnifiedMap<>();
    }

    public static <K, V> UnifiedMap<K, V> newMap(int size)
    {
        return new UnifiedMap<>(size);
    }

    public static <K, V> UnifiedMap<K, V> newMap(int size, float loadFactor)
    {
        return new UnifiedMap<>(size, loadFactor);
    }

    public static <K, V> UnifiedMap<K, V> newMap(Map<? extends K, ? extends V> map)
    {
        return new UnifiedMap<>(map);
    }

    public static <K, V> UnifiedMap<K, V> newMapWith(Pair<K, V>... pairs)
    {
        return new UnifiedMap<>(pairs);
    }

    public static <K, V> UnifiedMap<K, V> newMapWith(Iterable<Pair<K, V>> inputIterable)
    {
        UnifiedMap<K, V> outputMap = UnifiedMap.newMap();
        for (Pair<K, V> single : inputIterable)
        {
            outputMap.add(single);
        }
        return outputMap;
    }

    public static <K, V> UnifiedMap<K, V> newWithKeysValues(K key, V value)
    {
        return new UnifiedMap<K, V>(1).withKeysValues(key, value);
    }

    public static <K, V> UnifiedMap<K, V> newWithKeysValues(K key1, V value1, K key2, V value2)
    {
        return new UnifiedMap<K, V>(2).withKeysValues(key1, value1, key2, value2);
    }

    public static <K, V> UnifiedMap<K, V> newWithKeysValues(K key1, V value1, K key2, V value2, K key3, V value3)
    {
        return new UnifiedMap<K, V>(3).withKeysValues(key1, value1, key2, value2, key3, value3);
    }

    public static <K, V> UnifiedMap<K, V> newWithKeysValues(
            K key1, V value1,
            K key2, V value2,
            K key3, V value3,
            K key4, V value4)
    {
        return new UnifiedMap<K, V>(4).withKeysValues(key1, value1, key2, value2, key3, value3, key4, value4);
    }

    public UnifiedMap<K, V> withKeysValues(K key, V value)
    {
        this.put(key, value);
        return this;
    }

    public UnifiedMap<K, V> withKeysValues(K key1, V value1, K key2, V value2)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        return this;
    }

    public UnifiedMap<K, V> withKeysValues(K key1, V value1, K key2, V value2, K key3, V value3)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        this.put(key3, value3);
        return this;
    }

    public UnifiedMap<K, V> withKeysValues(K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4)
    {
        this.put(key1, value1);
        this.put(key2, value2);
        this.put(key3, value3);
        this.put(key4, value4);
        return this;
    }

    @Override
    public UnifiedMap<K, V> clone()
    {
        return new UnifiedMap<>(this);
    }

    @Override
    public MutableMap<K, V> newEmpty()
    {
        return new UnifiedMap<>();
    }

    @Override
    public MutableMap<K, V> newEmpty(int capacity)
    {
        return new UnifiedMap<>(capacity, this.loadFactor);
    }

    @Override
    protected int index(Object key)
    {
        // This function ensures that hashCodes that differ only by
        // constant multiples at each bit position have a bounded
        // number of collisions (approximately 8 at default load factor).
        int h = key == null ? 0 : key.hashCode();
        h ^= h >>> 20 ^ h >>> 12;
        h ^= h >>> 7 ^ h >>> 4;
        return (h & (this.table.length >> 1) - 1) << 1;
    }

    @Override
    public boolean removeIf(Predicate2<? super K, ? super V> predicate)
    {
        int previousOccupied = this.occupied;
        for (int index = 0; index < this.table.length; index += 2)
        {
            Object cur = this.table[index];
            if (cur == null)
            {
                continue;
            }
            if (cur == CHAINED_KEY)
            {
                Object[] chain = (Object[]) this.table[index + 1];
                for (int chIndex = 0; chIndex < chain.length; )
                {
                    if (chain[chIndex] == null)
                    {
                        break;
                    }
                    K key = this.nonSentinel(chain[chIndex]);
                    V value = (V) chain[chIndex + 1];
                    if (predicate.accept(key, value))
                    {
                        this.overwriteWithLastElementFromChain(chain, index, chIndex);
                    }
                    else
                    {
                        chIndex += 2;
                    }
                }
            }
            else
            {
                K key = this.nonSentinel(cur);
                V value = (V) this.table[index + 1];
                if (predicate.accept(key, value))
                {
                    this.table[index] = null;
                    this.table[index + 1] = null;
                    this.occupied--;
                }
            }
        }
        return previousOccupied > this.occupied;
    }

    @Override
    public Set<Entry<K, V>> entrySet()
    {
        return new EntrySet();
    }

    @Override
    public Collection<V> values()
    {
        return new ValuesCollection();
    }

    @Override
    protected int computeHashCodeOfKey(K object)
    {
        return object == NULL_KEY ? 0 : object.hashCode();
    }

    @Override
    public Pair<K, V> detect(Predicate2<? super K, ? super V> predicate)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        K key = this.nonSentinel(chainedTable[j]);
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(key, value))
                        {
                            return Tuples.pair(key, value);
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                K key = this.nonSentinel(this.table[i]);
                V value = (V) this.table[i + 1];

                if (predicate.accept(key, value))
                {
                    return Tuples.pair(key, value);
                }
            }
        }

        return null;
    }

    @Override
    public V detect(Predicate<? super V> predicate)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value))
                        {
                            return value;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value))
                {
                    return value;
                }
            }
        }

        return null;
    }

    @Override
    public <P> V detectWith(Predicate2<? super V, ? super P> predicate, P parameter)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value, parameter))
                        {
                            return value;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value, parameter))
                {
                    return value;
                }
            }
        }

        return null;
    }

    @Override
    public Optional<Pair<K, V>> detectOptional(Predicate2<? super K, ? super V> predicate)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        K key = this.nonSentinel(chainedTable[j]);
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(key, value))
                        {
                            return Optional.of(Tuples.pair(key, value));
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                K key = this.nonSentinel(this.table[i]);
                V value = (V) this.table[i + 1];

                if (predicate.accept(key, value))
                {
                    return Optional.of(Tuples.pair(key, value));
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<V> detectOptional(Predicate<? super V> predicate)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value))
                        {
                            return Optional.of(value);
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value))
                {
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public <P> Optional<V> detectWithOptional(Predicate2<? super V, ? super P> predicate, P parameter)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value, parameter))
                        {
                            return Optional.of(value);
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value, parameter))
                {
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public V detectIfNone(Predicate<? super V> predicate, Function0<? extends V> function)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value))
                        {
                            return value;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value))
                {
                    return value;
                }
            }
        }

        return function.value();
    }

    @Override
    public <P> V detectWithIfNone(
            Predicate2<? super V, ? super P> predicate,
            P parameter,
            Function0<? extends V> function)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value, parameter))
                        {
                            return value;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value, parameter))
                {
                    return value;
                }
            }
        }

        return function.value();
    }

    private boolean shortCircuit(
            Predicate<? super V> predicate,
            boolean expected,
            boolean onShortCircuit,
            boolean atEnd)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value) == expected)
                        {
                            return onShortCircuit;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value) == expected)
                {
                    return onShortCircuit;
                }
            }
        }

        return atEnd;
    }

    private <P> boolean shortCircuitWith(
            Predicate2<? super V, ? super P> predicate,
            P parameter,
            boolean expected,
            boolean onShortCircuit,
            boolean atEnd)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                Object[] chainedTable = (Object[]) this.table[i + 1];
                for (int j = 0; j < chainedTable.length; j += 2)
                {
                    if (chainedTable[j] != null)
                    {
                        V value = (V) chainedTable[j + 1];
                        if (predicate.accept(value, parameter) == expected)
                        {
                            return onShortCircuit;
                        }
                    }
                }
            }
            else if (this.table[i] != null)
            {
                V value = (V) this.table[i + 1];

                if (predicate.accept(value, parameter) == expected)
                {
                    return onShortCircuit;
                }
            }
        }

        return atEnd;
    }

    @Override
    public boolean anySatisfy(Predicate<? super V> predicate)
    {
        return this.shortCircuit(predicate, true, true, false);
    }

    @Override
    public <P> boolean anySatisfyWith(Predicate2<? super V, ? super P> predicate, P parameter)
    {
        return this.shortCircuitWith(predicate, parameter, true, true, false);
    }

    @Override
    public boolean allSatisfy(Predicate<? super V> predicate)
    {
        return this.shortCircuit(predicate, false, false, true);
    }

    @Override
    public <P> boolean allSatisfyWith(Predicate2<? super V, ? super P> predicate, P parameter)
    {
        return this.shortCircuitWith(predicate, parameter, false, false, true);
    }

    @Override
    public boolean noneSatisfy(Predicate<? super V> predicate)
    {
        return this.shortCircuit(predicate, true, false, true);
    }

    @Override
    public <P> boolean noneSatisfyWith(Predicate2<? super V, ? super P> predicate, P parameter)
    {
        return this.shortCircuitWith(predicate, parameter, true, false, true);
    }

    @Override
    protected MutableSet<K> newSetWithMatchingHashingStrategy()
    {
        return UnifiedSet.newSet(this.size());
    }

    protected class EntrySet implements Set<Entry<K, V>>, Serializable, BatchIterable<Entry<K, V>>
    {
        private static final long serialVersionUID = 1L;
        private transient WeakReference<UnifiedMap<K, V>> holder = new WeakReference<>(UnifiedMap.this);

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
            UnifiedMap.this.clear();
        }

        public boolean containsEntry(Entry<?, ?> entry)
        {
            return this.getEntry(entry) != null;
        }

        private Entry<K, V> getEntry(Entry<?, ?> entry)
        {
            K key = (K) entry.getKey();
            V value = (V) entry.getValue();
            int index = UnifiedMap.this.index(key);

            Object cur = UnifiedMap.this.table[index];
            Object curValue = UnifiedMap.this.table[index + 1];
            if (cur == CHAINED_KEY)
            {
                return this.chainGetEntry((Object[]) curValue, key, value);
            }
            if (cur == null)
            {
                return null;
            }
            if (UnifiedMap.this.nonNullTableObjectEquals(cur, key))
            {
                if (UnifiedMap.nullSafeEquals(value, curValue))
                {
                    return ImmutableEntry.of(UnifiedMap.this.nonSentinel(cur), (V) curValue);
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
                if (UnifiedMap.this.nonNullTableObjectEquals(cur, key))
                {
                    Object curValue = chain[i + 1];
                    if (UnifiedMap.nullSafeEquals(value, curValue))
                    {
                        return ImmutableEntry.of(UnifiedMap.this.nonSentinel(cur), (V) curValue);
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
            return UnifiedMap.this.isEmpty();
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

            int index = UnifiedMap.this.index(key);

            Object cur = UnifiedMap.this.table[index];
            if (cur != null)
            {
                Object val = UnifiedMap.this.table[index + 1];
                if (cur == CHAINED_KEY)
                {
                    return this.removeFromChain((Object[]) val, key, value, index);
                }
                if (UnifiedMap.this.nonNullTableObjectEquals(cur, key) && UnifiedMap.nullSafeEquals(value, val))
                {
                    UnifiedMap.this.table[index] = null;
                    UnifiedMap.this.table[index + 1] = null;
                    UnifiedMap.this.occupied--;
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
                if (UnifiedMap.this.nonNullTableObjectEquals(k, key))
                {
                    V val = (V) chain[i + 1];
                    if (UnifiedMap.nullSafeEquals(val, value))
                    {
                        UnifiedMap.this.overwriteWithLastElementFromChain(chain, index, i);
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
            UnifiedMap<K, V> retainedCopy = (UnifiedMap<K, V>) UnifiedMap.this.newEmpty(retainedSize);

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
                UnifiedMap.this.maxSize = retainedCopy.maxSize;
                UnifiedMap.this.occupied = retainedCopy.occupied;
                UnifiedMap.this.table = retainedCopy.table;
                return true;
            }
            return false;
        }

        @Override
        public int size()
        {
            return UnifiedMap.this.size();
        }

        @Override
        public void forEach(Procedure<? super Entry<K, V>> procedure)
        {
            for (int i = 0; i < UnifiedMap.this.table.length; i += 2)
            {
                Object cur = UnifiedMap.this.table[i];
                if (cur == CHAINED_KEY)
                {
                    this.chainedForEachEntry((Object[]) UnifiedMap.this.table[i + 1], procedure);
                }
                else if (cur != null)
                {
                    procedure.value(ImmutableEntry.of(UnifiedMap.this.nonSentinel(cur), (V) UnifiedMap.this.table[i + 1]));
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
                procedure.value(ImmutableEntry.of(UnifiedMap.this.nonSentinel(cur), (V) chain[i + 1]));
            }
        }

        @Override
        public int getBatchCount(int batchSize)
        {
            return UnifiedMap.this.getBatchCount(batchSize);
        }

        @Override
        public void batchForEach(Procedure<? super Entry<K, V>> procedure, int sectionIndex, int sectionCount)
        {
            Object[] map = UnifiedMap.this.table;
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
                    procedure.value(ImmutableEntry.of(UnifiedMap.this.nonSentinel(cur), (V) map[i + 1]));
                }
            }
        }

        protected void copyEntries(Object[] result)
        {
            Object[] table = UnifiedMap.this.table;
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
                                    new WeakBoundEntry<>(UnifiedMap.this.nonSentinel(cur), (V) chain[j + 1], this.holder);
                        }
                    }
                    else
                    {
                        result[count++] = new WeakBoundEntry<>(UnifiedMap.this.nonSentinel(x), (V) table[i + 1], this.holder);
                    }
                }
            }
        }

        @Override
        public Object[] toArray()
        {
            Object[] result = new Object[UnifiedMap.this.size()];
            this.copyEntries(result);
            return result;
        }

        @Override
        public <T> T[] toArray(T[] result)
        {
            int size = UnifiedMap.this.size();
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
            this.holder = new WeakReference<>(UnifiedMap.this);
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
            return UnifiedMap.this.hashCode();
        }
    }

    protected class EntrySetIterator extends PositionalIterator<Entry<K, V>>
    {
        private final WeakReference<UnifiedMap<K, V>> holder;

        protected EntrySetIterator(WeakReference<UnifiedMap<K, V>> holder)
        {
            this.holder = holder;
        }

        protected Entry<K, V> nextFromChain()
        {
            Object[] chain = (Object[]) UnifiedMap.this.table[this.position + 1];
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
            return new WeakBoundEntry<>(UnifiedMap.this.nonSentinel(cur), (V) value, this.holder);
        }

        @Override
        public Entry<K, V> next()
        {
            if (!this.hasNext())
            {
                throw new NoSuchElementException("next() called, but the iterator is exhausted");
            }
            this.count++;
            Object[] table = UnifiedMap.this.table;
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
            return new WeakBoundEntry<>(UnifiedMap.this.nonSentinel(cur), (V) value, this.holder);
        }
    }

    protected static class WeakBoundEntry<K, V> implements Map.Entry<K, V>
    {
        protected final K key;
        protected V value;
        protected final WeakReference<UnifiedMap<K, V>> holder;

        protected WeakBoundEntry(
                K key,
                V value,
                WeakReference<UnifiedMap<K, V>> holder)
        {
            this.key = key;
            this.value = value;
            this.holder = holder;
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
            UnifiedMap<K, V> map = this.holder.get();
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
                return UnifiedMap.nullSafeEquals(this.key, otherKey)
                        && UnifiedMap.nullSafeEquals(this.value, otherValue);
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return (this.key == null ? 0 : this.key.hashCode())
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
        return cur == key || (cur == NULL_KEY ? key == null : cur.equals(key));
    }

    @Override
    public ImmutableMap<K, V> toImmutable()
    {
        return Maps.immutable.withAll(this);
    }
}
