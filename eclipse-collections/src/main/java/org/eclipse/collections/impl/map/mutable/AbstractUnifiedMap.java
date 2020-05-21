/*
 * Copyright (c) 2020 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.map.mutable;

import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.block.procedure.MapCollectProcedure;
import org.eclipse.collections.impl.map.strategy.mutable.UnifiedMapWithHashingStrategy;
import org.eclipse.collections.impl.parallel.BatchIterable;
import org.eclipse.collections.impl.utility.Iterate;

public abstract class AbstractUnifiedMap<K, V>
    extends AbstractMutableMap<K, V>
    implements BatchIterable<V>
{
    @Override
    @SuppressWarnings("AbstractMethodOverridesAbstractMethod")
    public abstract MutableMap<K, V> clone();

    protected static final Object NULL_KEY = new Object()
    {
        @Override
        public boolean equals(Object obj)
        {
            throw new RuntimeException("Possible corruption through unsynchronized concurrent modification.");
        }

        @Override
        public int hashCode()
        {
            throw new RuntimeException("Possible corruption through unsynchronized concurrent modification.");
        }

        @Override
        public String toString()
        {
            return "UnifiedMap.NULL_KEY";
        }
    };

    protected static final Object CHAINED_KEY = new Object()
    {
        @Override
        public boolean equals(Object obj)
        {
            throw new RuntimeException("Possible corruption through unsynchronized concurrent modification.");
        }

        @Override
        public int hashCode()
        {
            throw new RuntimeException("Possible corruption through unsynchronized concurrent modification.");
        }

        @Override
        public String toString()
        {
            return "UnifiedMap.CHAINED_KEY";
        }
    };

    protected static final float DEFAULT_LOAD_FACTOR = 0.75f;

    protected static final int DEFAULT_INITIAL_CAPACITY = 8;

    protected transient Object[] table;

    protected transient int occupied;

    protected float loadFactor = DEFAULT_LOAD_FACTOR;

    protected int maxSize;

    protected void validatePrameters(int initialCapacity, float loadFactor)
    {
        if (initialCapacity < 0)
        {
            throw new IllegalArgumentException("initial capacity cannot be less than 0");
        }
        if (loadFactor <= 0.0)
        {
            throw new IllegalArgumentException("load factor cannot be less than or equal to 0");
        }
        if (loadFactor > 1.0)
        {
            throw new IllegalArgumentException("load factor cannot be greater than 1");
        }
    }

    protected int fastCeil(float v)
    {
        int possibleResult = (int) v;
        if (v - possibleResult > 0.0F)
        {
            possibleResult++;
        }
        return possibleResult;
    }

    protected int init(int initialCapacity)
    {
        int capacity = 1;
        while (capacity < initialCapacity)
        {
            capacity <<= 1;
        }

        return this.allocate(capacity);
    }

    protected int allocate(int capacity)
    {
        this.allocateTable(capacity << 1); // the table size is twice the capacity to handle both keys and values
        this.computeMaxSize(capacity);

        return capacity;
    }

    protected void allocateTable(int sizeToAllocate)
    {
        this.table = new Object[sizeToAllocate];
    }

    protected void computeMaxSize(int capacity)
    {
        // need at least one free slot for open addressing
        this.maxSize = Math.min(capacity - 1, (int) (capacity * this.loadFactor));
    }

    abstract protected int index(K key);
    
    @Override
    public void clear()
    {
        if (this.occupied == 0)
        {
            return;
        }
        this.occupied = 0;
        Object[] set = this.table;

        for (int i = set.length; i-- > 0; )
        {
            set[i] = null;
        }
    }

    @Override
    public V put(K key, V value)
    {
        int index = this.index(key);
        Object cur = this.table[index];
        if (cur == null)
        {
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            this.table[index + 1] = value;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return null;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            V result = (V) this.table[index + 1];
            this.table[index + 1] = value;
            return result;
        }
        return this.chainedPut(key, index, value);
    }

    private V chainedPut(K key, int index, V value)
    {
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            for (int i = 0; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    chain[i + 1] = value;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    return null;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    V result = (V) chain[i + 1];
                    chain[i + 1] = value;
                    return result;
                }
            }
            Object[] newChain = new Object[chain.length + 4];
            System.arraycopy(chain, 0, newChain, 0, chain.length);
            this.table[index + 1] = newChain;
            newChain[chain.length] = AbstractUnifiedMap.toSentinelIfNull(key);
            newChain[chain.length + 1] = value;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return null;
        }
        Object[] newChain = new Object[4];
        newChain[0] = this.table[index];
        newChain[1] = this.table[index + 1];
        newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
        newChain[3] = value;
        this.table[index] = CHAINED_KEY;
        this.table[index + 1] = newChain;
        if (++this.occupied > this.maxSize)
        {
            this.rehash(this.table.length);
        }
        return null;
    }

    @Override
    public V updateValue(K key, Function0<? extends V> factory, Function<? super V, ? extends V> function)
    {
        int index = this.index(key);
        Object cur = this.table[index];
        if (cur == null)
        {
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            V result = function.valueOf(factory.value());
            this.table[index + 1] = result;
            ++this.occupied;
            return result;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            V oldValue = (V) this.table[index + 1];
            V newValue = function.valueOf(oldValue);
            this.table[index + 1] = newValue;
            return newValue;
        }
        return this.chainedUpdateValue(key, index, factory, function);
    }

    private V chainedUpdateValue(K key, int index, Function0<? extends V> factory, Function<? super V, ? extends V> function)
    {
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            for (int i = 0; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    V result = function.valueOf(factory.value());
                    chain[i + 1] = result;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    return result;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    V oldValue = (V) chain[i + 1];
                    V result = function.valueOf(oldValue);
                    chain[i + 1] = result;
                    return result;
                }
            }
            Object[] newChain = new Object[chain.length + 4];
            System.arraycopy(chain, 0, newChain, 0, chain.length);
            this.table[index + 1] = newChain;
            newChain[chain.length] = AbstractUnifiedMap.toSentinelIfNull(key);
            V result = function.valueOf(factory.value());
            newChain[chain.length + 1] = result;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return result;
        }
        Object[] newChain = new Object[4];
        newChain[0] = this.table[index];
        newChain[1] = this.table[index + 1];
        newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
        V result = function.valueOf(factory.value());
        newChain[3] = result;
        this.table[index] = CHAINED_KEY;
        this.table[index + 1] = newChain;
        if (++this.occupied > this.maxSize)
        {
            this.rehash(this.table.length);
        }
        return result;
    }

    @Override
    public <P> V updateValueWith(K key, Function0<? extends V> factory, Function2<? super V, ? super P, ? extends V> function, P parameter)
    {
        int index = this.index(key);
        Object cur = this.table[index];
        if (cur == null)
        {
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            V result = function.value(factory.value(), parameter);
            this.table[index + 1] = result;
            ++this.occupied;
            return result;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            V oldValue = (V) this.table[index + 1];
            V newValue = function.value(oldValue, parameter);
            this.table[index + 1] = newValue;
            return newValue;
        }
        return this.chainedUpdateValueWith(key, index, factory, function, parameter);
    }

    private <P> V chainedUpdateValueWith(
            K key,
            int index,
            Function0<? extends V> factory,
            Function2<? super V, ? super P, ? extends V> function,
            P parameter)
    {
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            for (int i = 0; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    V result = function.value(factory.value(), parameter);
                    chain[i + 1] = result;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    return result;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    V oldValue = (V) chain[i + 1];
                    V result = function.value(oldValue, parameter);
                    chain[i + 1] = result;
                    return result;
                }
            }
            Object[] newChain = new Object[chain.length + 4];
            System.arraycopy(chain, 0, newChain, 0, chain.length);
            this.table[index + 1] = newChain;
            newChain[chain.length] = AbstractUnifiedMap.toSentinelIfNull(key);
            V result = function.value(factory.value(), parameter);
            newChain[chain.length + 1] = result;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return result;
        }
        Object[] newChain = new Object[4];
        newChain[0] = this.table[index];
        newChain[1] = this.table[index + 1];
        newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
        V result = function.value(factory.value(), parameter);
        newChain[3] = result;
        this.table[index] = CHAINED_KEY;
        this.table[index + 1] = newChain;
        if (++this.occupied > this.maxSize)
        {
            this.rehash(this.table.length);
        }
        return result;
    }

    @Override
    public V getIfAbsentPut(K key, Function0<? extends V> function)
    {
        int index = this.index(key);
        Object cur = this.table[index];

        if (cur == null)
        {
            V result = function.value();
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            this.table[index + 1] = result;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return result;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            return (V) this.table[index + 1];
        }
        return this.chainedGetIfAbsentPut(key, index, function);
    }

    private V chainedGetIfAbsentPut(K key, int index, Function0<? extends V> function)
    {
        V result = null;
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            int i = 0;
            for (; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    result = function.value();
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    chain[i + 1] = result;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    break;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    result = (V) chain[i + 1];
                    break;
                }
            }
            if (i == chain.length)
            {
                result = function.value();
                Object[] newChain = new Object[chain.length + 4];
                System.arraycopy(chain, 0, newChain, 0, chain.length);
                newChain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                newChain[i + 1] = result;
                this.table[index + 1] = newChain;
                if (++this.occupied > this.maxSize)
                {
                    this.rehash(this.table.length);
                }
            }
        }
        else
        {
            result = function.value();
            Object[] newChain = new Object[4];
            newChain[0] = this.table[index];
            newChain[1] = this.table[index + 1];
            newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
            newChain[3] = result;
            this.table[index] = CHAINED_KEY;
            this.table[index + 1] = newChain;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
        }
        return result;
    }

    @Override
    public V getIfAbsentPut(K key, V value)
    {
        int index = this.index(key);
        Object cur = this.table[index];

        if (cur == null)
        {
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            this.table[index + 1] = value;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return value;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            return (V) this.table[index + 1];
        }
        return this.chainedGetIfAbsentPut(key, index, value);
    }

    private V chainedGetIfAbsentPut(K key, int index, V value)
    {
        V result = value;
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            int i = 0;
            for (; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    chain[i + 1] = value;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    break;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    result = (V) chain[i + 1];
                    break;
                }
            }
            if (i == chain.length)
            {
                Object[] newChain = new Object[chain.length + 4];
                System.arraycopy(chain, 0, newChain, 0, chain.length);
                newChain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                newChain[i + 1] = value;
                this.table[index + 1] = newChain;
                if (++this.occupied > this.maxSize)
                {
                    this.rehash(this.table.length);
                }
            }
        }
        else
        {
            Object[] newChain = new Object[4];
            newChain[0] = this.table[index];
            newChain[1] = this.table[index + 1];
            newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
            newChain[3] = value;
            this.table[index] = CHAINED_KEY;
            this.table[index + 1] = newChain;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
        }
        return result;
    }

    @Override
    public <P> V getIfAbsentPutWith(K key, Function<? super P, ? extends V> function, P parameter)
    {
        int index = this.index(key);
        Object cur = this.table[index];

        if (cur == null)
        {
            V result = function.valueOf(parameter);
            this.table[index] = AbstractUnifiedMap.toSentinelIfNull(key);
            this.table[index + 1] = result;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
            return result;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, key))
        {
            return (V) this.table[index + 1];
        }
        return this.chainedGetIfAbsentPutWith(key, index, function, parameter);
    }

    private <P> V chainedGetIfAbsentPutWith(K key, int index, Function<? super P, ? extends V> function, P parameter)
    {
        V result = null;
        if (this.table[index] == CHAINED_KEY)
        {
            Object[] chain = (Object[]) this.table[index + 1];
            int i = 0;
            for (; i < chain.length; i += 2)
            {
                if (chain[i] == null)
                {
                    result = function.valueOf(parameter);
                    chain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                    chain[i + 1] = result;
                    if (++this.occupied > this.maxSize)
                    {
                        this.rehash(this.table.length);
                    }
                    break;
                }
                if (this.nonNullTableObjectEquals(chain[i], key))
                {
                    result = (V) chain[i + 1];
                    break;
                }
            }
            if (i == chain.length)
            {
                result = function.valueOf(parameter);
                Object[] newChain = new Object[chain.length + 4];
                System.arraycopy(chain, 0, newChain, 0, chain.length);
                newChain[i] = AbstractUnifiedMap.toSentinelIfNull(key);
                newChain[i + 1] = result;
                this.table[index + 1] = newChain;
                if (++this.occupied > this.maxSize)
                {
                    this.rehash(this.table.length);
                }
            }
        }
        else
        {
            result = function.valueOf(parameter);
            Object[] newChain = new Object[4];
            newChain[0] = this.table[index];
            newChain[1] = this.table[index + 1];
            newChain[2] = AbstractUnifiedMap.toSentinelIfNull(key);
            newChain[3] = result;
            this.table[index] = CHAINED_KEY;
            this.table[index + 1] = newChain;
            if (++this.occupied > this.maxSize)
            {
                this.rehash(this.table.length);
            }
        }
        return result;
    }

    public int getCollidingBuckets()
    {
        int count = 0;
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of JVM words that is used by this map. A word is 4 bytes in a 32bit VM and 8 bytes in a 64bit
     * VM. Each array has a 2 word header, thus the formula is:
     * words = (internal table length + 2) + sum (for all chains (chain length + 2))
     *
     * @return the number of JVM words that is used by this map.
     */
    public int getMapMemoryUsedInWords()
    {
        int headerSize = 2;
        int sizeInWords = this.table.length + headerSize;
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                sizeInWords += headerSize + ((Object[]) this.table[i + 1]).length;
            }
        }
        return sizeInWords;
    }

    protected void rehash(int newCapacity)
    {
        int oldLength = this.table.length;
        Object[] old = this.table;
        this.allocate(newCapacity);
        this.occupied = 0;

        for (int i = 0; i < oldLength; i += 2)
        {
            Object cur = old[i];
            if (cur == CHAINED_KEY)
            {
                Object[] chain = (Object[]) old[i + 1];
                for (int j = 0; j < chain.length; j += 2)
                {
                    if (chain[j] != null)
                    {
                        this.put(this.nonSentinel(chain[j]), (V) chain[j + 1]);
                    }
                }
            }
            else if (cur != null)
            {
                this.put(this.nonSentinel(cur), (V) old[i + 1]);
            }
        }
    }

    @Override
    public V get(Object key)
    {
        int index = this.index((K) key);
        Object cur = this.table[index];
        if (cur != null)
        {
            Object val = this.table[index + 1];
            if (cur == CHAINED_KEY)
            {
                return this.getFromChain((Object[]) val, (K) key);
            }
            if (this.nonNullTableObjectEquals(cur, (K) key))
            {
                return (V) val;
            }
        }
        return null;
    }

    private V getFromChain(Object[] chain, K key)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object k = chain[i];
            if (k == null)
            {
                return null;
            }
            if (this.nonNullTableObjectEquals(k, key))
            {
                return (V) chain[i + 1];
            }
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key)
    {
        int index = this.index((K) key);
        Object cur = this.table[index];
        if (cur == null)
        {
            return false;
        }
        if (cur != CHAINED_KEY && this.nonNullTableObjectEquals(cur, (K) key))
        {
            return true;
        }
        return cur == CHAINED_KEY && this.chainContainsKey((Object[]) this.table[index + 1], (K) key);
    }

    private boolean chainContainsKey(Object[] chain, K key)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object k = chain[i];
            if (k == null)
            {
                return false;
            }
            if (this.nonNullTableObjectEquals(k, key))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsValue(Object value)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            if (this.table[i] == CHAINED_KEY)
            {
                if (this.chainedContainsValue((Object[]) this.table[i + 1], (V) value))
                {
                    return true;
                }
            }
            else if (this.table[i] != null)
            {
                if (AbstractUnifiedMap.nullSafeEquals(value, this.table[i + 1]))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean chainedContainsValue(Object[] chain, V value)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            if (chain[i] == null)
            {
                return false;
            }
            if (AbstractUnifiedMap.nullSafeEquals(value, chain[i + 1]))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public void forEachKeyValue(Procedure2<? super K, ? super V> procedure)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            Object cur = this.table[i];
            if (cur == CHAINED_KEY)
            {
                this.chainedForEachEntry((Object[]) this.table[i + 1], procedure);
            }
            else if (cur != null)
            {
                procedure.value(this.nonSentinel(cur), (V) this.table[i + 1]);
            }
        }
    }

    @Override
    public V getFirst()
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            Object cur = this.table[i];
            if (cur == CHAINED_KEY)
            {
                Object[] chain = (Object[]) this.table[i + 1];
                return (V) chain[1];
            }
            if (cur != null)
            {
                return (V) this.table[i + 1];
            }
        }
        return null;
    }

    @Override
    public <E> MutableMap<K, V> collectKeysAndValues(
            Iterable<E> iterable,
            Function<? super E, ? extends K> keyFunction,
            Function<? super E, ? extends V> valueFunction)
    {
        Iterate.forEach(iterable, new MapCollectProcedure<>(this, keyFunction, valueFunction));
        return this;
    }

    @Override
    public V removeKey(K key)
    {
        return this.remove(key);
    }

    private void chainedForEachEntry(Object[] chain, Procedure2<? super K, ? super V> procedure)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object cur = chain[i];
            if (cur == null)
            {
                return;
            }
            procedure.value(this.nonSentinel(cur), (V) chain[i + 1]);
        }
    }

    @Override
    public int getBatchCount(int batchSize)
    {
        return Math.max(1, this.table.length / 2 / batchSize);
    }

    @Override
    public void batchForEach(Procedure<? super V> procedure, int sectionIndex, int sectionCount)
    {
        int sectionSize = this.table.length / sectionCount;
        int start = sectionIndex * sectionSize;
        int end = sectionIndex == sectionCount - 1 ? this.table.length : start + sectionSize;
        if (start % 2 == 0)
        {
            start++;
        }
        for (int i = start; i < end; i += 2)
        {
            Object value = this.table[i];
            if (value instanceof Object[])
            {
                this.chainedForEachValue((Object[]) value, procedure);
            }
            else if (value == null && this.table[i - 1] != null || value != null)
            {
                procedure.value((V) value);
            }
        }
    }

    @Override
    public void forEachKey(Procedure<? super K> procedure)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            Object cur = this.table[i];
            if (cur == CHAINED_KEY)
            {
                this.chainedForEachKey((Object[]) this.table[i + 1], procedure);
            }
            else if (cur != null)
            {
                procedure.value(this.nonSentinel(cur));
            }
        }
    }

    protected void chainedForEachKey(Object[] chain, Procedure<? super K> procedure)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object cur = chain[i];
            if (cur == null)
            {
                return;
            }
            procedure.value(this.nonSentinel(cur));
        }
    }

    @Override
    public void forEachValue(Procedure<? super V> procedure)
    {
        for (int i = 0; i < this.table.length; i += 2)
        {
            Object cur = this.table[i];
            if (cur == CHAINED_KEY)
            {
                this.chainedForEachValue((Object[]) this.table[i + 1], procedure);
            }
            else if (cur != null)
            {
                procedure.value((V) this.table[i + 1]);
            }
        }
    }

    private void chainedForEachValue(Object[] chain, Procedure<? super V> procedure)
    {
        for (int i = 0; i < chain.length; i += 2)
        {
            Object cur = chain[i];
            if (cur == null)
            {
                return;
            }
            procedure.value((V) chain[i + 1]);
        }
    }

    @Override
    public boolean isEmpty()
    {
        return this.occupied == 0;
    }
    // <--------- Bottom insertion

    // Placed before EntrySet
    protected static boolean nullSafeEquals(Object value, Object other)
    {
        if (value == null)
        {
            if (other == null)
            {
                return true;
            }
        }
        else if (other == value || value.equals(other))
        {
            return true;
        }
        return false;
    }

    // BOTTOM of the file
    protected static Object toSentinelIfNull(Object key)
    {
        if (key == null)
        {
            return NULL_KEY;
        }
        return key;
    }

    protected K nonSentinel(Object key)
    {
        return key == NULL_KEY ? null : (K) key;
    }

    protected abstract boolean nonNullTableObjectEquals(Object cur, K key);
}
