/*
 * Copyright (c) 2015, Haiyang Li.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.IntFunction;
import com.landawn.abacus.util.function.Predicate;

/**
 * A collection that supports order-independent equality, like {@link Set}, but
 * may have duplicate elements.
 *
 * <p>Elements of a LongMultiset that are equal to one another are referred to as
 * <i>occurrences</i> of the same single element. The total number of
 * occurrences of an element in a LongMultiset is called the <i>count</i> of that
 * element (the terms "frequency" and "multiplicity" are equivalent, but not
 * used in this API). Since the count of an element is represented as an {@code
 * long}, a LongMultiset may never contain more than {@link MutableLong#MAX_VALUE}
 * occurrences of any one element.
 *
 * @param <E>
 *
 * @since 0.8
 *
 * @author Haiyang Li
 */
public final class LongMultiset<E> implements Collection<E> {
    private static final Comparator<Map.Entry<?, MutableLong>> cmpByCount = new Comparator<Map.Entry<?, MutableLong>>() {
        @Override
        public int compare(Entry<?, MutableLong> a, Entry<?, MutableLong> b) {
            return N.compare(a.getValue().longValue(), b.getValue().longValue());
        }
    };

    private final Map<E, MutableLong> valueMap;

    public LongMultiset() {
        this(HashMap.class);
    }

    public LongMultiset(int initialCapacity) {
        this(new HashMap<E, MutableLong>(initialCapacity));
    }

    @SuppressWarnings("rawtypes")
    public LongMultiset(final Class<? extends Map> valueMapType) {
        this(N.newInstance(valueMapType));
    }

    public LongMultiset(final Collection<? extends E> c) {
        this();

        addAll(c);
    }

    /**
     *
     * @param valueMap The valueMap and this Multiset share the same data; any changes to one will appear in the other.
     */
    @Internal
    LongMultiset(final Map<E, MutableLong> valueMap) {
        this.valueMap = valueMap;
    }

    public static <T> LongMultiset<T> of(final T... a) {
        final LongMultiset<T> multiset = new LongMultiset<>(new HashMap<T, MutableLong>(N.initHashCapacity(a.length)));

        for (T e : a) {
            multiset.add(e);
        }

        return multiset;
    }

    //    @SuppressWarnings("rawtypes")
    //    static <T> Multiset<T> of(final Class<? extends Map> valueMapType, final T... a) {
    //        final Multiset<T> multiset = new Multiset<T>(valueMapType);
    //
    //        for (T e : a) {
    //            multiset.add(e);
    //        }
    //
    //        return multiset;
    //    }

    public static <T> LongMultiset<T> of(final Collection<? extends T> coll) {
        return new LongMultiset<T>(coll);
    }

    //    @SuppressWarnings("rawtypes")
    //    static <T> Multiset<T> of(final Class<? extends Map> valueMapType, final Collection<T> coll) {
    //        final Multiset<T> multiset = new Multiset<T>(valueMapType);
    //
    //        multiset.addAll(coll);
    //
    //        return multiset;
    //    }

    public static <T> LongMultiset<T> from(final Map<? extends T, Long> m) {
        final LongMultiset<T> multiset = new LongMultiset<>(N.initHashCapacity(m.size()));

        multiset.setAll(m);

        return multiset;
    }

    public static <T> LongMultiset<T> from2(final Map<? extends T, Integer> m) {
        final LongMultiset<T> multiset = new LongMultiset<>(N.initHashCapacity(m.size()));

        for (Map.Entry<? extends T, Integer> entry : m.entrySet()) {
            checkOccurrences(entry.getValue().intValue());
        }

        for (Map.Entry<? extends T, Integer> entry : m.entrySet()) {
            multiset.set(entry.getKey(), entry.getValue().intValue());
        }

        return multiset;
    }

    public static <T> LongMultiset<T> from(final Multiset<? extends T> multiset) {
        final LongMultiset<T> result = new LongMultiset<>(N.initHashCapacity(multiset.size()));

        for (Map.Entry<? extends T, MutableInt> entry : multiset.entrySet()) {
            result.set(entry.getKey(), entry.getValue().intValue());
        }

        return result;
    }

    //    public static <T> LongMultiset<T> from(final LongLongMultiset<? extends T> multiset) {
    //        final LongMultiset<T> result = new LongMultiset<>(N.initHashCapacity(multiset.size()));
    //
    //        for (Map.Entry<? extends T, MutableLong> entry : multiset.entrySet()) {
    //            if (entry.getValue().longValue() < 0 || entry.getValue().longValue() > Long.MAX_VALUE) {
    //                throw new IllegalArgumentException("The specified 'occurrences' can not be less than 0 or bigger than Long.MAX_VALUE");
    //            }
    //
    //            result.set(entry.getKey(), entry.getValue().longValue());
    //        }
    //
    //        return result;
    //    }
    //
    //    public static LongMultiset<Character> from(CharSequence str) {
    //        final LongMultiset<Character> result = new LongMultiset<>(N.initHashCapacity(str.length()));
    //
    //        if (N.notNullOrEmpty(str)) {
    //            if (str instanceof String) {
    //                for (char ch : N.getCharsForReadOnly((String) str)) {
    //                    result.add(ch);
    //                }
    //            } else {
    //                for (long i = 0, len = str.length(); i < len; i++) {
    //                    result.add(str.charAt(i));
    //                }
    //            }
    //        }
    //
    //        return result;
    //    }

    //    @SuppressWarnings("rawtypes")
    //    public static <T> LongMultiset<T> from(final Class<? extends Map> valueMapType, final Map<? extends T, Long> m) {
    //        final LongMultiset<T> multiset = new LongMultiset<T>(valueMapType);
    //
    //        multiset.setAll(m);
    //
    //        return multiset;
    //    }

    /**
     *
     * @param e
     * @return the occurrences of the specified object. zero is returned if it's not in this set.
     */
    public long get(final Object e) {
        final MutableLong count = valueMap.get(e);

        return count == null ? 0 : count.longValue();
    }

    /**
     * 
     * @param e
     * @param defaultValue
     * @return the occurrences of the specified object. the specified defaultValue is returned if it's not in this set.
     */
    public long getOrDefault(final Object e, long defaultValue) {
        final MutableLong count = valueMap.get(e);

        return count == null ? defaultValue : count.longValue();
    }

    /**
     * The element will be removed if the specified count is 0.
     * 
     * @param e
     * @param occurrences
     * @return
     */
    public long getAndSet(final E e, final long occurrences) {
        checkOccurrences(occurrences);

        final MutableLong count = valueMap.get(e);
        long result = count == null ? 0 : count.longValue();

        if (occurrences == 0) {
            if (count != null) {
                valueMap.remove(e);
            }
        } else {
            if (count == null) {
                valueMap.put(e, MutableLong.of(occurrences));
            } else {
                count.setValue(occurrences);
            }
        }

        return result;
    }

    /**
     * The element will be removed if the specified count is 0.
     * 
     * @param e
     * @param occurrences
     * @return
     */
    public long setAndGet(final E e, final long occurrences) {
        checkOccurrences(occurrences);

        final MutableLong count = valueMap.get(e);

        if (occurrences == 0) {
            if (count != null) {
                valueMap.remove(e);
            }
        } else {
            if (count == null) {
                valueMap.put(e, MutableLong.of(occurrences));
            } else {
                count.setValue(occurrences);
            }
        }

        return occurrences;
    }

    /**
     * The element will be removed if the specified count is 0.
     *
     * @param e
     * @param occurrences
     * @return this LongMultiset.
     * @throws IllegalArgumentException if the occurrences of element is less than 0
     */
    public LongMultiset<E> set(final E e, final long occurrences) {
        checkOccurrences(occurrences);

        if (occurrences == 0) {
            valueMap.remove(e);
        } else {
            final MutableLong count = valueMap.get(e);

            if (count == null) {
                valueMap.put(e, MutableLong.of(occurrences));
            } else {
                count.setValue(occurrences);
            }
        }

        return this;
    }

    public LongMultiset<E> setAll(final Collection<? extends E> c, final long occurrences) {
        checkOccurrences(occurrences);

        if (N.notNullOrEmpty(c)) {
            for (E e : c) {
                set(e, occurrences);
            }
        }

        return this;
    }

    /**
     * 
     * @param m
     * @return this LongMultiset.
     * @throws IllegalArgumentException if the occurrences of element is less than 0.
     */
    public LongMultiset<E> setAll(final Map<? extends E, Long> m) throws IllegalArgumentException {
        if (N.notNullOrEmpty(m)) {
            for (Map.Entry<? extends E, Long> entry : m.entrySet()) {
                checkOccurrences(entry.getValue().longValue());
            }

            for (Map.Entry<? extends E, Long> entry : m.entrySet()) {
                set(entry.getKey(), entry.getValue().longValue());
            }
        }

        return this;
    }

    /**
     * 
     * @param m
     * @return this LongMultiset.
     * @throws IllegalArgumentException if the occurrences of element is less than 0.
     */
    public LongMultiset<E> setAll(final LongMultiset<? extends E> multiset) throws IllegalArgumentException {
        if (N.notNullOrEmpty(multiset)) {
            for (Map.Entry<? extends E, MutableLong> entry : multiset.entrySet()) {
                set(entry.getKey(), entry.getValue().longValue());
            }
        }

        return this;
    }

    public Optional<Map.Entry<E, Long>> minOccurrences() {
        if (size() == 0) {
            return Optional.empty();
        }

        final Iterator<Map.Entry<E, MutableLong>> it = valueMap.entrySet().iterator();
        Map.Entry<E, MutableLong> entry = it.next();
        E minCountElement = entry.getKey();
        long minCount = entry.getValue().longValue();

        while (it.hasNext()) {
            entry = it.next();

            if (entry.getValue().longValue() < minCount) {
                minCountElement = entry.getKey();
                minCount = entry.getValue().longValue();
            }
        }

        return Optional.of((Map.Entry<E, Long>) MapEntry.of(minCountElement, minCount));
    }

    public Optional<Map.Entry<E, Long>> maxOccurrences() {
        if (size() == 0) {
            return Optional.empty();
        }

        final Iterator<Map.Entry<E, MutableLong>> it = valueMap.entrySet().iterator();
        Map.Entry<E, MutableLong> entry = it.next();
        E maxCountElement = entry.getKey();
        long maxCount = entry.getValue().longValue();

        while (it.hasNext()) {
            entry = it.next();

            if (entry.getValue().longValue() > maxCount) {
                maxCountElement = entry.getKey();
                maxCount = entry.getValue().longValue();
            }
        }

        return Optional.of((Map.Entry<E, Long>) MapEntry.of(maxCountElement, maxCount));
    }

    public Long sumOfOccurrences() {
        long sum = 0;

        for (MutableLong count : valueMap.values()) {
            sum += count.longValue();
        }

        return sum;
    }

    public OptionalDouble averageOfOccurrences() {
        if (size() == 0) {
            return OptionalDouble.empty();
        }

        final double sum = sumOfOccurrences();

        return OptionalDouble.of(sum / size());
    }

    /**
     *
     * @param e
     * @return always true
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    @Override
    public boolean add(final E e) throws IllegalArgumentException {
        return add(e, 1);
    }

    /**
     *
     * @param e
     * @param occurrences
     * @return true if the specified occurrences is bigger than 0.
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean add(final E e, final long occurrences) throws IllegalArgumentException {
        checkOccurrences(occurrences);

        MutableLong count = valueMap.get(e);

        if (count != null && occurrences > (Long.MAX_VALUE - count.longValue())) {
            throw new IllegalArgumentException("The total count is out of the bound of long");
        }

        if (count == null) {
            if (occurrences > 0) {
                count = MutableLong.of(occurrences);
                valueMap.put(e, count);
            }
        } else {
            count.add(occurrences);
        }

        return occurrences > 0;
    }

    /**
     * 
     * @param e
     * @return true if the specified element is absent.
     * @throws IllegalArgumentException
     */
    public boolean addIfAbsent(final E e) throws IllegalArgumentException {
        return addIfAbsent(e, 1);
    }

    /**
     * 
     * @param e
     * @param occurrences
     * @return true if the specified element is absent and occurrences is bigger than 0.
     * @throws IllegalArgumentException 
     */
    public boolean addIfAbsent(final E e, final long occurrences) throws IllegalArgumentException {
        checkOccurrences(occurrences);

        MutableLong count = valueMap.get(e);

        if (count == null && occurrences > 0) {
            count = MutableLong.of(occurrences);
            valueMap.put(e, count);

            return true;
        }

        return false;
    }

    public long addAndGet(final E e) {
        return addAndGet(e, 1);
    }

    public long addAndGet(final E e, final long occurrences) {
        checkOccurrences(occurrences);

        MutableLong count = valueMap.get(e);

        if (count != null && occurrences > (Long.MAX_VALUE - count.longValue())) {
            throw new IllegalArgumentException("The total count is out of the bound of long");
        }

        if (count == null) {
            if (occurrences > 0) {
                count = MutableLong.of(occurrences);
                valueMap.put(e, count);
            }
        } else {
            count.add(occurrences);
        }

        return count == null ? 0 : count.longValue();
    }

    public long getAndAdd(final E e) {
        return getAndAdd(e, 1);
    }

    public long getAndAdd(final E e, final long occurrences) {
        checkOccurrences(occurrences);

        MutableLong count = valueMap.get(e);

        if (count != null && occurrences > (Long.MAX_VALUE - count.longValue())) {
            throw new IllegalArgumentException("The total count is out of the bound of long");
        }

        final long result = count == null ? 0 : count.longValue();

        if (count == null) {
            if (occurrences > 0) {
                count = MutableLong.of(occurrences);
                valueMap.put(e, count);
            }
        } else {
            count.add(occurrences);
        }

        return result;
    }

    /**
     * 
     * @param c
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    @Override
    public boolean addAll(final Collection<? extends E> c) throws IllegalArgumentException {
        return addAll(c, 1);
    }

    /**
     * 
     * @param c
     * @param occurrences
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean addAll(final Collection<? extends E> c, final long occurrences) throws IllegalArgumentException {
        checkOccurrences(occurrences);

        if (N.isNullOrEmpty(c) || occurrences == 0) {
            return false;
        }

        for (E e : c) {
            add(e, occurrences);
        }

        return occurrences > 0;
    }

    /**
     * 
     * @param m
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean addAll(final Map<? extends E, Long> m) throws IllegalArgumentException {
        if (N.isNullOrEmpty(m)) {
            return false;
        }

        for (Map.Entry<? extends E, Long> entry : m.entrySet()) {
            checkOccurrences(entry.getValue().longValue());
        }

        boolean result = false;

        for (Map.Entry<? extends E, Long> entry : m.entrySet()) {
            if (result == false) {
                result = add(entry.getKey(), entry.getValue().longValue());
            } else {
                add(entry.getKey(), entry.getValue().longValue());
            }
        }

        return result;
    }

    /**
     * 
     * @param m
     * @throws IllegalArgumentException if the occurrences of element is less than 0.
     */
    public boolean addAll(final LongMultiset<? extends E> multiset) throws IllegalArgumentException {
        if (N.isNullOrEmpty(multiset)) {
            return false;
        }

        for (Map.Entry<? extends E, MutableLong> entry : multiset.entrySet()) {
            add(entry.getKey(), entry.getValue().longValue());
        }

        return true;
    }

    @Override
    public boolean contains(final Object o) {
        return valueMap.containsKey(o);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
        return valueMap.keySet().containsAll(c);
    }

    /**
     * The element will be removed from this set if the occurrences equals to or less than 0 after the operation.
     *
     * @param e
     * @param occurrences
     * @return
     */
    @Override
    public boolean remove(final Object e) throws IllegalArgumentException {
        return remove(e, 1);
    }

    /**
     * The element will be removed from this set if the occurrences equals to or less than 0 after the operation.
     *
     * @param e
     * @param occurrences
     * @return
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean remove(final Object e, final long occurrences) throws IllegalArgumentException {
        checkOccurrences(occurrences);

        final MutableLong count = valueMap.get(e);

        if (count == null) {
            return false;
        } else {
            count.subtract(occurrences);

            if (count.longValue() <= 0) {
                valueMap.remove(e);
            }

            return occurrences > 0;
        }
    }

    public long removeAndGet(final Object e) {
        return removeAndGet(e, 1);
    }

    public long removeAndGet(final Object e, final long occurrences) {
        checkOccurrences(occurrences);

        final MutableLong count = valueMap.get(e);

        if (count == null) {
            return 0;
        } else {
            count.subtract(occurrences);

            if (count.longValue() <= 0) {
                valueMap.remove(e);
            }

            return count.longValue() > 0 ? count.longValue() : 0;
        }
    }

    public long getAndRemove(final Object e) {
        return getAndRemove(e, 1);
    }

    public long getAndRemove(final Object e, final long occurrences) {
        checkOccurrences(occurrences);

        final MutableLong count = valueMap.get(e);
        final long result = count == null ? 0 : count.longValue();

        if (count != null) {
            count.subtract(occurrences);

            if (count.longValue() <= 0) {
                valueMap.remove(e);
            }
        }

        return result;
    }

    /**
     * 
     * @param e
     * @return the occurrences of the specified element before it's removed.
     */
    public long removeAllOccurrences(final Object e) {
        final MutableLong count = valueMap.remove(e);

        return count == null ? 0 : count.longValue();
    }

    /**
     * Removes all of this Multiset's elements that are also contained in the
     * specified collection (optional operation).  After this call returns,
     * this Multiset will contain no elements in common with the specified
     * collection. This method ignores how often any element might appear in
     * {@code c}, and only cares whether or not an element appears at all.
     * 
     * @param c
     * @return <tt>true</tt> if this set changed as a result of the call
     * @see Collection#removeAll(Collection)
     */
    @Override
    public boolean removeAll(final Collection<?> c) {
        return removeAll(c, Long.MAX_VALUE);
    }

    /**
     * The elements will be removed from this set if the occurrences equals to or less than 0 after the operation.
     *
     * @param c
     * @param occurrences
     *            the occurrences to remove if the element is in the specified collection <code>c</code>.
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean removeAll(final Collection<?> c, final long occurrences) throws IllegalArgumentException {
        checkOccurrences(occurrences);

        if (N.isNullOrEmpty(c) || occurrences == 0) {
            return false;
        }

        boolean result = false;

        for (Object e : c) {
            if (result == false) {
                result = remove(e, occurrences);
            } else {
                remove(e, occurrences);
            }
        }

        return result;
    }

    /**
     * 
     * @param m
     * @return
     * @throws IllegalArgumentException if the occurrences of element after this operation is bigger than Long.MAX_VALUE.
     */
    public boolean removeAll(final Map<?, Long> m) throws IllegalArgumentException {
        if (N.isNullOrEmpty(m)) {
            return false;
        }

        for (Map.Entry<?, Long> entry : m.entrySet()) {
            checkOccurrences(entry.getValue().longValue());
        }

        boolean result = false;

        for (Map.Entry<?, Long> entry : m.entrySet()) {
            if (result == false) {
                result = remove(entry.getKey(), entry.getValue().longValue());
            } else {
                remove(entry.getKey(), entry.getValue().longValue());
            }
        }

        return result;
    }

    /**
     * 
     * @param m
     * @throws IllegalArgumentException if the occurrences of element is less than 0.
     */
    public boolean removeAll(final LongMultiset<?> multiset) throws IllegalArgumentException {
        if (N.isNullOrEmpty(multiset)) {
            return false;
        }

        for (Map.Entry<?, MutableLong> entry : multiset.entrySet()) {
            remove(entry.getKey(), entry.getValue().longValue());
        }

        return true;
    }

    /**
     * Retains only the elements in this collection that are contained in the
     * specified collection (optional operation).  In other words, removes from
     * this collection all of its elements that are not contained in the
     * specified collection.
     *
     * @param c
     * @return <tt>true</tt> if this set changed as a result of the call
     * @see Collection#retainAll(Collection)
     */
    @Override
    public boolean retainAll(final Collection<?> c) {
        Set<E> others = null;

        for (E e : valueMap.keySet()) {
            if (!c.contains(e)) {
                if (others == null) {
                    others = new HashSet<>(valueMap.size());
                }

                others.add(e);
            }
        }

        return N.isNullOrEmpty(others) ? false : removeAll(others, Long.MAX_VALUE);
    }

    @Override
    public int size() {
        return valueMap.size();
    }

    @Override
    public boolean isEmpty() {
        return valueMap.isEmpty();
    }

    @Override
    public void clear() {
        valueMap.clear();
    }

    @Override
    public Iterator<E> iterator() {
        return valueMap.keySet().iterator();
    }

    public Set<Map.Entry<E, MutableLong>> entrySet() {
        return valueMap.entrySet();
    }

    @Override
    public Object[] toArray() {
        return valueMap.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(final T[] a) {
        return valueMap.keySet().toArray(a);
    }

    public Map<E, Long> toMap() {
        final Map<E, Long> result = new HashMap<>(N.initHashCapacity(size()));

        for (Map.Entry<E, MutableLong> entry : valueMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().longValue());
        }

        return result;
    }

    public Map<E, Long> toMap(final IntFunction<Map<E, Long>> supplier) {
        final Map<E, Long> result = supplier.apply(size());

        for (Map.Entry<E, MutableLong> entry : valueMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().longValue());
        }

        return result;
    }

    @SuppressWarnings("rawtypes")
    public Map<E, Long> toMapSortedByOccurrences() {
        return toMapSortedBy((Comparator) cmpByCount);
    }

    public Map<E, Long> toMapSortedBy(final Comparator<Map.Entry<E, MutableLong>> cmp) {
        if (N.isNullOrEmpty(valueMap)) {
            return new LinkedHashMap<>();
        }

        final Map.Entry<E, MutableLong>[] entries = entrySet().toArray(new Map.Entry[size()]);
        Arrays.sort(entries, cmp);

        final Map<E, Long> sortedValues = new LinkedHashMap<>(N.initHashCapacity(size()));

        for (Map.Entry<E, MutableLong> entry : entries) {
            sortedValues.put(entry.getKey(), entry.getValue().longValue());
        }

        return sortedValues;
    }

    /**
     * 
     * @return a list with all elements, each of them is repeated with the occurrences in this <code>LongMultiset</code>   
     */
    public List<E> flat() {
        final long totalOccurrences = sumOfOccurrences().longValue();

        if (totalOccurrences > Integer.MAX_VALUE) {
            throw new RuntimeException("The total occurrences(" + totalOccurrences + ") is bigger than the max value of int.");
        }

        final Object[] a = new Object[sumOfOccurrences().intValue()];

        int fromIndex = 0;
        int toIndex = 0;

        for (Map.Entry<E, MutableLong> entry : valueMap.entrySet()) {
            toIndex = fromIndex + (int) entry.getValue().longValue();

            Arrays.fill(a, fromIndex, toIndex, entry.getKey());
            fromIndex = toIndex;
        }

        return N.asList((E[]) a);
    }

    public void forEach(BiConsumer<? super E, MutableLong> action) {
        for (Map.Entry<E, MutableLong> entry : valueMap.entrySet()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Execute <code>accumulator</code> on each element till <code>till</code> returns true.
     * 
     * @param identity
     * @param accumulator
     * @param till break if the <code>till</code> returns true.
     * @return
     */
    public <R> R forEach(final R identity, BiFunction<R, ? super Map.Entry<E, MutableLong>, R> accumulator, final Predicate<? super R> till) {
        R result = identity;

        for (Map.Entry<E, MutableLong> entry : valueMap.entrySet()) {
            result = accumulator.apply(result, entry);

            if (till.test(result)) {
                break;
            }
        }

        return result;
    }

    /**
     * The implementation is equivalent to performing the following steps for this LongMultiset:
     * 
     * <pre>
     * final long oldValue = get(e);
     * 
     * if (oldValue > 0) {
     *     return oldValue;
     * }
     * 
     * final long newValue = mappingFunction.apply(e);
     * 
     * if (newValue > 0) {
     *     set(e, newValue);
     * }
     * 
     * return newValue;
     * </pre>
     * 
     * @param e
     * @param mappingFunction
     * @return
     */
    public long computeIfAbsent(E e, Function<? super E, Long> mappingFunction) {
        N.requireNonNull(mappingFunction);

        final long oldValue = get(e);

        if (oldValue > 0) {
            return oldValue;
        }

        final long newValue = mappingFunction.apply(e);

        if (newValue > 0) {
            set(e, newValue);
        }

        return newValue;
    }

    /**
     * The implementation is equivalent to performing the following steps for this LongMultiset:
     * 
     * <pre> 
     * final long oldValue = get(e);
     * 
     * if (oldValue == 0) {
     *     return oldValue;
     * }
     * 
     * final long newValue = remappingFunction.apply(e, oldValue);
     * 
     * if (newValue > 0) {
     *     set(e, newValue);
     * } else {
     *     remove(e);
     * }
     * 
     * return newValue;
     * </pre>
     * 
     * @param e
     * @param remappingFunction
     * @return
     */
    public long computeIfPresent(E e, BiFunction<? super E, Long, Long> remappingFunction) {
        N.requireNonNull(remappingFunction);

        final long oldValue = get(e);

        if (oldValue == 0) {
            return oldValue;
        }

        final long newValue = remappingFunction.apply(e, oldValue);

        if (newValue > 0) {
            set(e, newValue);
        } else {
            remove(e);
        }

        return newValue;
    }

    /**
     * The implementation is equivalent to performing the following steps for this LongMultiset:
     * 
     * <pre>
     * final long oldValue = get(key);
     * final long newValue = remappingFunction.apply(key, oldValue);
     * 
     * if (newValue > 0) {
     *     set(key, newValue);
     * } else {
     *     if (oldValue > 0) {
     *         remove(key);
     *     }
     * }
     * 
     * return newValue;
     * </pre>
     * 
     * @param key
     * @param remappingFunction
     * @return
     */
    public long compute(E key, BiFunction<? super E, Long, Long> remappingFunction) {
        N.requireNonNull(remappingFunction);

        final long oldValue = get(key);
        final long newValue = remappingFunction.apply(key, oldValue);

        if (newValue > 0) {
            set(key, newValue);
        } else {
            if (oldValue > 0) {
                remove(key);
            }
        }

        return newValue;
    }

    /**
     * The implementation is equivalent to performing the following steps for this LongMultiset:
     * 
     * <pre>
     * long oldValue = get(key);
     * long newValue = (oldValue == 0) ? value : remappingFunction.apply(oldValue, value);
     * 
     * if (newValue > 0) {
     *     set(key, newValue);
     * } else {
     *     if (oldValue > 0) {
     *         remove(key);
     *     }
     * }
     * 
     * return newValue;
     * </pre>
     * 
     * @param key
     * @param value
     * @param remappingFunction
     * @return
     */
    public long merge(E key, long value, BiFunction<Long, Long, Long> remappingFunction) {
        N.requireNonNull(remappingFunction);
        N.requireNonNull(value);

        long oldValue = get(key);
        long newValue = (oldValue == 0) ? value : remappingFunction.apply(oldValue, value);

        if (newValue > 0) {
            set(key, newValue);
        } else {
            if (oldValue > 0) {
                remove(key);
            }
        }

        return newValue;
    }

    @Override
    public int hashCode() {
        return valueMap.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        return obj == this || (obj instanceof LongMultiset && valueMap.equals(((LongMultiset<E>) obj).valueMap));
    }

    @Override
    public String toString() {
        return valueMap.toString();
    }

    private static void checkOccurrences(final long occurrences) {
        if (occurrences < 0) {
            throw new IllegalArgumentException("The specified 'occurrences' can not be less than 1");
        }
    }
}