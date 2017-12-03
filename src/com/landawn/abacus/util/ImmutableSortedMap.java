/*
 * Copyright (C) 2017 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.landawn.abacus.util;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 
 * @since 1.1.4
 * 
 * @author Haiyang Li
 */
public class ImmutableSortedMap<K, V> extends ImmutableMap<K, V> implements SortedMap<K, V> {

    @SuppressWarnings("rawtypes")
    private static final ImmutableSortedMap EMPTY = new ImmutableSortedMap(N.emptySortedMap());

    private final SortedMap<K, V> sortedMap;

    ImmutableSortedMap(SortedMap<? extends K, ? extends V> sortedMap) {
        super(sortedMap);
        this.sortedMap = (SortedMap<K, V>) sortedMap;
    }

    public static <K, V> ImmutableSortedMap<K, V> empty() {
        return EMPTY;
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2,
            final k k3, final v v3) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2,
            final k k3, final v v3, final k k4, final v v4) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2,
            final k k3, final v v3, final k k4, final v v4, final k k5, final v v5) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2,
            final k k3, final v v3, final k k4, final v v4, final k k5, final v v5, final k k6, final v v6) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);

        return new ImmutableSortedMap<>(map);
    }

    public static <K extends Comparable<? super K>, V, k extends K, v extends V> ImmutableSortedMap<K, V> of(final k k1, final v v1, final k k2, final v v2,
            final k k3, final v v3, final k k4, final v v4, final k k5, final v v5, final k k6, final v v6, final k k7, final v v7) {
        final SortedMap<K, V> map = N.newTreeMap();

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);

        return new ImmutableSortedMap<>(map);
    }

    /**
     * 
     * @param sortedMap the elements in this <code>map</code> are shared by the returned ImmutableSortedMap.
     * @return
     */
    public static <K, V> ImmutableSortedMap<K, V> of(final SortedMap<? extends K, ? extends V> sortedMap) {
        if (sortedMap == null) {
            return empty();
        }

        return new ImmutableSortedMap<>(sortedMap);
    }

    public static <K, V> ImmutableSortedMap<K, V> copyOf(final SortedMap<? extends K, ? extends V> sortedMap) {
        if (N.isNullOrEmpty(sortedMap)) {
            return empty();
        }

        return of(new TreeMap<>(sortedMap));
    }

    @Override
    public Comparator<? super K> comparator() {
        return sortedMap.comparator();
    }

    @Override
    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        return of(sortedMap.subMap(fromKey, toKey));
    }

    @Override
    public SortedMap<K, V> headMap(K toKey) {
        return of(sortedMap.headMap(toKey));
    }

    @Override
    public SortedMap<K, V> tailMap(K fromKey) {
        return of(sortedMap.tailMap(fromKey));
    }

    @Override
    public K firstKey() {
        return sortedMap.firstKey();
    }

    @Override
    public K lastKey() {
        return sortedMap.lastKey();
    }
}
