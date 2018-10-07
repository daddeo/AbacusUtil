/*
 * Copyright (C) 2016 HaiYang Li
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

package com.landawn.abacus.util.stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.landawn.abacus.util.ByteIterator;
import com.landawn.abacus.util.CharIterator;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DoubleIterator;
import com.landawn.abacus.util.FloatIterator;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.IntIterator;
import com.landawn.abacus.util.LongIterator;
import com.landawn.abacus.util.Multimap;
import com.landawn.abacus.util.Multiset;
import com.landawn.abacus.util.MutableBoolean;
import com.landawn.abacus.util.MutableLong;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Nth;
import com.landawn.abacus.util.Optional;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.ShortIterator;
import com.landawn.abacus.util.Try;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.ToByteFunction;
import com.landawn.abacus.util.function.ToCharFunction;
import com.landawn.abacus.util.function.ToDoubleFunction;
import com.landawn.abacus.util.function.ToFloatFunction;
import com.landawn.abacus.util.function.ToIntFunction;
import com.landawn.abacus.util.function.ToLongFunction;
import com.landawn.abacus.util.function.ToShortFunction;
import com.landawn.abacus.util.function.TriFunction;

/**
 * This class is a sequential, stateful and immutable stream implementation.
 *
 * @param <T>
 * @since 0.8
 * 
 * @author Haiyang Li
 */
final class ParallelIteratorStream<T> extends IteratorStream<T> {
    private final int maxThreadNum;
    private final Splitor splitor;
    private volatile IteratorStream<T> sequential;

    ParallelIteratorStream(final Iterator<? extends T> values, final boolean sorted, final Comparator<? super T> comparator, final int maxThreadNum,
            final Splitor splitor, final Collection<Runnable> closeHandlers) {
        super(values, sorted, comparator, closeHandlers);

        this.maxThreadNum = checkMaxThreadNum(maxThreadNum);
        this.splitor = splitor == null ? DEFAULT_SPLITOR : splitor;
    }

    ParallelIteratorStream(final Stream<T> stream, final boolean sorted, final Comparator<? super T> comparator, final int maxThreadNum, final Splitor splitor,
            final Set<Runnable> closeHandlers) {
        this(stream.iterator(), sorted, comparator, maxThreadNum, splitor, mergeCloseHandlers(stream, closeHandlers));
    }

    @Override
    public Stream<T> filter(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1) {
            return super.filter(predicate);
        }

        final List<Iterator<T>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private T next = null;
                private boolean hasNext = false;

                @Override
                public boolean hasNext() {
                    if (hasNext == false) {
                        while (true) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                hasNext = true;
                                break;
                            }
                        }
                    }

                    return hasNext;
                }

                @Override
                public T next() {
                    if (hasNext == false && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    hasNext = false;
                    return next;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public Stream<T> takeWhile(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1) {
            return super.takeWhile(predicate);
        }

        final List<Iterator<T>> iters = new ArrayList<>(maxThreadNum);
        final MutableBoolean hasMore = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private T next = null;
                private boolean hasNext = false;

                @Override
                public boolean hasNext() {
                    if (hasNext == false && hasMore.isTrue()) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                                hasNext = true;
                            } else {
                                hasMore.setFalse();
                            }
                        }

                        if (hasNext && predicate.test(next) == false) {
                            hasNext = false;
                            hasMore.setFalse();
                        }
                    }

                    return hasNext;
                }

                @Override
                public T next() {
                    if (hasNext == false && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    hasNext = false;
                    return next;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public Stream<T> dropWhile(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1) {
            return super.dropWhile(predicate);
        }

        final List<Iterator<T>> iters = new ArrayList<>(maxThreadNum);
        final MutableBoolean dropped = MutableBoolean.of(false);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private T next = null;
                private boolean hasNext = false;

                @Override
                public boolean hasNext() {
                    if (hasNext == false) {
                        // Only one thread is kept for running after it's dropped.
                        if (dropped.isTrue()) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                    hasNext = true;
                                }
                            }
                        } else {
                            while (dropped.isFalse()) {
                                synchronized (elements) {
                                    if (elements.hasNext()) {
                                        next = elements.next();
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next) == false) {
                                    hasNext = true;
                                    dropped.setTrue();
                                    break;
                                }
                            }

                            if (hasNext == false && dropped.isTrue()) {
                                synchronized (elements) {
                                    if (elements.hasNext()) {
                                        next = elements.next();
                                        hasNext = true;
                                    }
                                }
                            }
                        }
                    }

                    return hasNext;
                }

                @Override
                public T next() {
                    if (hasNext == false && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    hasNext = false;
                    return next;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <R> Stream<R> map(final Function<? super T, ? extends R> mapper) {
        if (maxThreadNum <= 1) {
            return super.map(mapper);
        }

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public R next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    R result = mapper.apply((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    //    @Override
    //    public <R> Stream<R> biMap(final BiFunction<? super T, ? super T, ? extends R> mapper, final boolean ignoreNotPaired) {
    //        if (maxThreadNum <= 1) {
    //            return new ParallelIteratorStream<>(sequential().biMap(mapper, ignoreNotPaired).iterator(), false, null, maxThreadNum, splitor, closeHandlers);
    //        }
    //
    //        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
    //
    //        for (int i = 0; i < maxThreadNum; i++) {
    //            iters.add(new ObjIteratorEx<R>() {
    //                private Object pre = NONE;
    //                private Object next = NONE;
    //
    //                @Override
    //                public boolean hasNext() {
    //                    if (pre == NONE) {
    //                        synchronized (elements) {
    //                            if (elements.hasNext()) {
    //                                pre = elements.next();
    //
    //                                if (elements.hasNext()) {
    //                                    next = elements.next();
    //                                }
    //                            }
    //                        }
    //                    }
    //
    //                    return ignoreNotPaired ? next != NONE : pre != NONE;
    //                }
    //
    //                @Override
    //                public R next() {
    //                    if (next == NONE && hasNext() == false) {
    //                        throw new NoSuchElementException();
    //                    }
    //
    //                    final R result = mapper.apply((T) pre, next == NONE ? null : (T) next);
    //                    pre = NONE;
    //                    next = NONE;
    //                    return result;
    //                }
    //            });
    //        }
    //
    //        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    //    }
    //
    //    @Override
    //    public <R> Stream<R> triMap(final TriFunction<? super T, ? super T, ? super T, ? extends R> mapper, final boolean ignoreNotPaired) {
    //        if (maxThreadNum <= 1) {
    //            return new ParallelIteratorStream<>(sequential().triMap(mapper, ignoreNotPaired).iterator(), false, null, maxThreadNum, splitor, closeHandlers);
    //        }
    //
    //        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
    //
    //        for (int i = 0; i < maxThreadNum; i++) {
    //            iters.add(new ObjIteratorEx<R>() {
    //                private Object prepre = NONE;
    //                private Object pre = NONE;
    //                private Object next = NONE;
    //
    //                @Override
    //                public boolean hasNext() {
    //                    if (prepre == NONE) {
    //                        synchronized (elements) {
    //                            if (elements.hasNext()) {
    //                                prepre = elements.next();
    //
    //                                if (elements.hasNext()) {
    //                                    pre = elements.next();
    //
    //                                    if (elements.hasNext()) {
    //                                        next = elements.next();
    //                                    }
    //                                }
    //                            }
    //                        }
    //                    }
    //
    //                    return ignoreNotPaired ? next != NONE : prepre != NONE;
    //                }
    //
    //                @Override
    //                public R next() {
    //                    if (next == NONE && hasNext() == false) {
    //                        throw new NoSuchElementException();
    //                    }
    //
    //                    final R result = mapper.apply((T) prepre, pre == NONE ? null : (T) pre, next == NONE ? null : (T) next);
    //                    prepre = NONE;
    //                    pre = NONE;
    //                    next = NONE;
    //                    return result;
    //                }
    //            });
    //        }
    //
    //        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    //    }

    @Override
    public <R> Stream<R> slidingMap(final BiFunction<? super T, ? super T, R> mapper, final int increment, final boolean ignoreNotPaired) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<>(sequential().slidingMap(mapper, increment).iterator(), false, null, maxThreadNum, splitor, closeHandlers);
        }

        final int windowSize = 2;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
        final MutableBoolean isFirst = MutableBoolean.of(true);
        final Holder<T> prev = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                @SuppressWarnings("unchecked")
                private final T NONE = (T) Stream.NONE;

                private T first = NONE;
                private T second = NONE;

                @Override
                public boolean hasNext() {
                    if (first == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                if (increment > windowSize && isFirst.isFalse()) {
                                    int skipNum = increment - windowSize;

                                    while (skipNum-- > 0 && elements.hasNext()) {
                                        elements.next();
                                    }
                                }

                                if (elements.hasNext()) {
                                    if (increment == 1) {
                                        first = isFirst.isTrue() ? elements.next() : prev.value();
                                        second = elements.hasNext() ? elements.next() : null;

                                        prev.setValue(second);

                                    } else {
                                        first = elements.next();
                                        second = elements.hasNext() ? elements.next() : null;
                                    }
                                }

                                isFirst.setFalse();
                            }
                        }
                    }

                    return ignoreNotPaired ? second != NONE : first != NONE;
                }

                @Override
                public R next() {
                    if ((ignoreNotPaired ? second == NONE : first == NONE) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final R result = mapper.apply(first, second == NONE ? null : second);
                    first = NONE;
                    second = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <R> Stream<R> slidingMap(final TriFunction<? super T, ? super T, ? super T, R> mapper, final int increment, final boolean ignoreNotPaired) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<>(sequential().slidingMap(mapper, increment).iterator(), false, null, maxThreadNum, splitor, closeHandlers);
        }

        final int windowSize = 3;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
        final MutableBoolean isFirst = MutableBoolean.of(true);
        final Holder<T> prev = new Holder<>();
        final Holder<T> prev2 = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                @SuppressWarnings("unchecked")
                private final T NONE = (T) Stream.NONE;

                private T first = NONE;
                private T second = NONE;
                private T third = NONE;

                @Override
                public boolean hasNext() {
                    if (first == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                if (increment > windowSize && isFirst.isFalse()) {
                                    int skipNum = increment - windowSize;

                                    while (skipNum-- > 0 && elements.hasNext()) {
                                        elements.next();
                                    }
                                }

                                if (elements.hasNext()) {
                                    if (increment == 1) {
                                        first = isFirst.isTrue() ? elements.next() : prev2.value();
                                        second = isFirst.isTrue() ? (elements.hasNext() ? elements.next() : null) : prev.value();
                                        third = elements.hasNext() ? elements.next() : null;

                                        prev2.setValue(second);
                                        prev.setValue(third);
                                    } else if (increment == 2) {
                                        first = isFirst.isTrue() ? elements.next() : prev.value();
                                        second = elements.hasNext() ? elements.next() : null;
                                        third = elements.hasNext() ? elements.next() : null;

                                        prev.setValue(third);
                                    } else {
                                        first = elements.next();
                                        second = elements.hasNext() ? elements.next() : null;
                                        third = elements.hasNext() ? elements.next() : null;
                                    }
                                }

                                isFirst.setFalse();
                            }
                        }
                    }

                    return ignoreNotPaired ? third != NONE : first != NONE;
                }

                @Override
                public R next() {
                    if ((ignoreNotPaired ? third == NONE : first == NONE) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final R result = mapper.apply(first, second == NONE ? null : second, third == NONE ? null : third);
                    first = NONE;
                    second = NONE;
                    third = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <R> Stream<R> mapFirstOrElse(final Function<? super T, ? extends R> mapperForFirst, final Function<? super T, ? extends R> mapperForElse) {
        N.checkArgNotNull(mapperForFirst);
        N.checkArgNotNull(mapperForElse);

        if (maxThreadNum <= 1) {
            return super.mapFirstOrElse(mapperForFirst, mapperForElse);
        }

        if (elements.hasNext()) {
            final Function<T, R> mapperForFirst2 = (Function<T, R>) mapperForFirst;
            final Function<T, R> mapperForElse2 = (Function<T, R>) mapperForElse;
            final T first = elements.next();

            return map(mapperForElse2).prepend(Stream.of(first).map(mapperForFirst2));
        } else {
            return (Stream<R>) this;
        }
    }

    @Override
    public <R> Stream<R> mapLastOrElse(final Function<? super T, ? extends R> mapperForLast, final Function<? super T, ? extends R> mapperForElse) {
        N.checkArgNotNull(mapperForLast);
        N.checkArgNotNull(mapperForElse);

        if (maxThreadNum <= 1) {
            return super.mapLastOrElse(mapperForLast, mapperForElse);
        }

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                private Object next = NONE;
                private boolean isLast = false;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();

                                if (elements.hasNext() == false) {
                                    isLast = true;
                                }
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public R next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final R result = isLast ? mapperForLast.apply((T) next) : mapperForElse.apply((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public CharStream mapToChar(final ToCharFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToChar(mapper);
        }

        final List<Iterator<Character>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Character>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Character next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Character result = mapper.applyAsChar((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorCharStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ByteStream mapToByte(final ToByteFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToByte(mapper);
        }

        final List<Iterator<Byte>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Byte>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Byte next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Byte result = mapper.applyAsByte((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorByteStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream mapToShort(final ToShortFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToShort(mapper);
        }

        final List<Iterator<Short>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Short>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Short next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Short result = mapper.applyAsShort((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorShortStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public IntStream mapToInt(final ToIntFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToInt(mapper);
        }

        final List<Iterator<Integer>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Integer>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Integer next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Integer result = mapper.applyAsInt((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorIntStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public LongStream mapToLong(final ToLongFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToLong(mapper);
        }

        final List<Iterator<Long>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Long>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Long next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Long result = mapper.applyAsLong((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorLongStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public FloatStream mapToFloat(final ToFloatFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToFloat(mapper);
        }

        final List<Iterator<Float>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Float>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Float next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Float result = mapper.applyAsFloat((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorFloatStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public DoubleStream mapToDouble(final ToDoubleFunction<? super T> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToDouble(mapper);
        }

        final List<Iterator<Double>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Double>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public Double next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    Double result = mapper.applyAsDouble((T) next);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorDoubleStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <R> Stream<R> flatMap(final Function<? super T, ? extends Stream<? extends R>> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<>(sequential().flatMap(mapper), false, null, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<R>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                private T next = null;
                private Iterator<? extends R> cur = null;
                private Stream<? extends R> s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public R next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.next();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public CharStream flatMapToChar(final Function<? super T, ? extends CharStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorCharStream(sequential().flatMapToChar(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Character>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Character>() {
                private T next = null;
                private CharIterator cur = null;
                private CharStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Character next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextChar();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorCharStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public ByteStream flatMapToByte(final Function<? super T, ? extends ByteStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorByteStream(sequential().flatMapToByte(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Byte>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Byte>() {
                private T next = null;
                private ByteIterator cur = null;
                private ByteStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Byte next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextByte();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorByteStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public ShortStream flatMapToShort(final Function<? super T, ? extends ShortStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorShortStream(sequential().flatMapToShort(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Short>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Short>() {
                private T next = null;
                private ShortIterator cur = null;
                private ShortStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Short next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextShort();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorShortStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public IntStream flatMapToInt(final Function<? super T, ? extends IntStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorIntStream(sequential().flatMapToInt(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Integer>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Integer>() {
                private T next = null;
                private IntIterator cur = null;
                private IntStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Integer next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextInt();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorIntStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public LongStream flatMapToLong(final Function<? super T, ? extends LongStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorLongStream(sequential().flatMapToLong(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Long>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Long>() {
                private T next = null;
                private LongIterator cur = null;
                private LongStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Long next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextLong();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorLongStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public FloatStream flatMapToFloat(final Function<? super T, ? extends FloatStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorFloatStream(sequential().flatMapToFloat(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Float>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Float>() {
                private T next = null;
                private FloatIterator cur = null;
                private FloatStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Float next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextFloat();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorFloatStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public DoubleStream flatMapToDouble(final Function<? super T, ? extends DoubleStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().flatMapToDouble(mapper), false, maxThreadNum, splitor, null);
        }

        final List<ObjIteratorEx<Double>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<Double>() {
                private T next = null;
                private DoubleIterator cur = null;
                private DoubleStream s = null;
                private Runnable closeHandle = null;

                @Override
                public boolean hasNext() {
                    while ((cur == null || cur.hasNext() == false) && next != NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            } else {
                                next = (T) NONE;
                                break;
                            }
                        }

                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }

                        s = mapper.apply(next);

                        if (N.notNullOrEmpty(s.closeHandlers)) {
                            final Set<Runnable> tmp = s.closeHandlers;

                            closeHandle = new Runnable() {
                                @Override
                                public void run() {
                                    Stream.close(tmp);
                                }
                            };
                        }

                        cur = s.iterator();
                    }

                    return cur != null && cur.hasNext();
                }

                @Override
                public Double next() {
                    if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    return cur.nextDouble();
                }

                @Override
                public void close() {
                    if (closeHandle != null) {
                        final Runnable tmp = closeHandle;
                        closeHandle = null;
                        tmp.run();
                    }
                }
            });
        }

        final Set<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalLinkedHashSet<Runnable>(1)
                : new LocalLinkedHashSet<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorDoubleStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, newCloseHandlers);
    }

    @Override
    public Stream<T> peek(final Consumer<? super T> action) {
        if (maxThreadNum <= 1) {
            return super.peek(action);
        }

        final List<Iterator<T>> iters = new ArrayList<>(maxThreadNum);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private Object next = NONE;

                @Override
                public boolean hasNext() {
                    if (next == NONE) {
                        synchronized (elements) {
                            if (elements.hasNext()) {
                                next = elements.next();
                            }
                        }
                    }

                    return next != NONE;
                }

                @Override
                public T next() {
                    if (next == NONE && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final T result = (T) next;
                    action.accept(result);
                    next = NONE;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <E extends Exception> void forEach(final Try.Consumer<? super T, E> action) throws E {
        if (maxThreadNum <= 1) {
            super.forEach(action);
            return;
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            action.accept(next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);
    }

    @Override
    public <E extends Exception> void forEachPair(final Try.BiConsumer<? super T, ? super T, E> action, final int increment) throws E {
        if (maxThreadNum <= 1) {
            super.forEachPair(action, increment);
            return;
        }

        final int windowSize = 2;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<T> prev = new Holder<>();
        final MutableBoolean isFirst = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                private T first = null;
                private T second = null;

                @Override
                public void run() {
                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    if (increment > windowSize && isFirst.isFalse()) {
                                        int skipNum = increment - windowSize;

                                        while (skipNum-- > 0 && elements.hasNext()) {
                                            elements.next();
                                        }

                                        if (elements.hasNext() == false) {
                                            break;
                                        }
                                    }

                                    if (increment == 1) {
                                        first = isFirst.isTrue() ? elements.next() : prev.value();
                                        second = elements.hasNext() ? elements.next() : null;

                                        prev.setValue(second);
                                    } else {
                                        first = elements.next();
                                        second = elements.hasNext() ? elements.next() : null;
                                    }

                                    isFirst.setFalse();
                                } else {
                                    break;
                                }
                            }

                            action.accept(first, second);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);
    }

    @Override
    public <E extends Exception> void forEachTriple(final Try.TriConsumer<? super T, ? super T, ? super T, E> action, final int increment) throws E {
        if (maxThreadNum <= 1) {
            super.forEachTriple(action, increment);
            return;
        }

        final int windowSize = 3;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<T> prev = new Holder<>();
        final Holder<T> prev2 = new Holder<>();
        final MutableBoolean isFirst = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                private T first = null;
                private T second = null;
                private T third = null;

                @Override
                public void run() {
                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    if (increment > windowSize && isFirst.isFalse()) {
                                        int skipNum = increment - windowSize;

                                        while (skipNum-- > 0 && elements.hasNext()) {
                                            elements.next();
                                        }

                                        if (elements.hasNext() == false) {
                                            break;
                                        }
                                    }

                                    if (increment == 1) {
                                        first = isFirst.isTrue() ? elements.next() : prev2.value();
                                        second = isFirst.isTrue() ? (elements.hasNext() ? elements.next() : null) : prev.value();
                                        third = elements.hasNext() ? elements.next() : null;

                                        prev2.setValue(second);
                                        prev.setValue(third);
                                    } else if (increment == 2) {
                                        first = isFirst.isTrue() ? elements.next() : prev.value();
                                        second = elements.hasNext() ? elements.next() : null;
                                        third = elements.hasNext() ? elements.next() : null;

                                        prev.setValue(third);
                                    } else {
                                        first = elements.next();
                                        second = elements.hasNext() ? elements.next() : null;
                                        third = elements.hasNext() ? elements.next() : null;
                                    }

                                    isFirst.setFalse();
                                } else {
                                    break;
                                }
                            }

                            action.accept(first, second, third);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);
    }

    @Override
    <A> A[] toArray(A[] a) {
        return elements.toArray(a);
    }

    @Override
    public <K, V, M extends Map<K, V>> M toMap(final Function<? super T, ? extends K> keyExtractor, final Function<? super T, ? extends V> valueMapper,
            final BinaryOperator<V> mergeFunction, final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return super.toMap(keyExtractor, valueMapper, mapFactory);
        }

        // return collect(Collectors.toMap(keyExtractor, valueMapper, mapFactory));

        //    final M res = mapFactory.get();
        //    res.putAll(collect(Collectors.toConcurrentMap(keyExtractor, valueMapper, mergeFunction)));
        //    return res;

        final List<ContinuableFuture<M>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<M>() {

                @Override
                public M call() {
                    M map = mapFactory.get();
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            Collectors.merge(map, keyExtractor.apply(next), valueMapper.apply(next), mergeFunction);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return map;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        M res = null;

        try {
            for (ContinuableFuture<M> future : futureList) {
                if (res == null) {
                    res = future.get();
                } else {
                    final M m = future.get();

                    for (Map.Entry<K, V> entry : m.entrySet()) {
                        Collectors.merge(res, entry.getKey(), entry.getValue(), mergeFunction);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return res;
    }

    @Override
    public <K, A, D, M extends Map<K, D>> M toMap(final Function<? super T, ? extends K> classifier, final Collector<? super T, A, D> downstream,
            final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return super.toMap(classifier, downstream, mapFactory);
        }

        // return collect(Collectors.groupingBy(classifier, downstream, mapFactory));

        //    final M res = mapFactory.get();
        //    res.putAll(collect(Collectors.groupingByConcurrent(classifier, downstream)));
        //    return res;

        final Supplier<A> downstreamSupplier = downstream.supplier();
        final BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();
        final BinaryOperator<A> downstreamCombiner = downstream.combiner();
        final Function<A, D> downstreamFinisher = downstream.finisher();

        final List<ContinuableFuture<Map<K, A>>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<Map<K, A>>() {

                @Override
                public Map<K, A> call() {
                    @SuppressWarnings("rawtypes")
                    Map<K, A> map = (Map) mapFactory.get();
                    K key = null;
                    A value = null;
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            key = N.checkArgNotNull(classifier.apply(next), "element cannot be mapped to a null key");
                            value = map.get(key);

                            if (value == null) {
                                value = downstreamSupplier.get();
                                map.put(key, value);
                            }

                            downstreamAccumulator.accept(value, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return map;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        Map<K, A> intermediate = null;

        try {
            for (ContinuableFuture<Map<K, A>> future : futureList) {
                if (intermediate == null) {
                    intermediate = future.get();
                } else {
                    final Map<K, A> m = future.get();
                    K key = null;

                    for (Map.Entry<K, A> entry : m.entrySet()) {
                        key = entry.getKey();

                        if (intermediate.containsKey(key)) {
                            intermediate.put(key, downstreamCombiner.apply(intermediate.get(key), m.get(key)));
                        } else {
                            intermediate.put(key, m.get(key));
                        }
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        final BiFunction<? super K, ? super A, ? extends A> function = new BiFunction<K, A, A>() {
            @Override
            public A apply(K k, A v) {
                return (A) downstreamFinisher.apply(v);
            }
        };

        Collectors.replaceAll(intermediate, function);

        return (M) intermediate;
    }

    @Override
    public <K, U, V extends Collection<U>, M extends Multimap<K, U, V>> M toMultimap(final Function<? super T, ? extends K> keyExtractor,
            final Function<? super T, ? extends U> valueMapper, final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return super.toMultimap(keyExtractor, valueMapper, mapFactory);
        }

        // return collect(Collectors.toMultimap(keyExtractor, valueMapper, mapFactory));

        //    final M res = mapFactory.get();
        //    final ConcurrentMap<K, List<U>> tmp = collect(Collectors.groupingByConcurrent(keyExtractor, Collectors.mapping(valueMapper, Collectors.<U> toList())));
        //
        //    for (Map.Entry<K, List<U>> entry : tmp.entrySet()) {
        //        res.putAll(entry.getKey(), entry.getValue());
        //    }
        //
        //    return res;

        final List<ContinuableFuture<M>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<M>() {

                @Override
                public M call() {
                    M map = mapFactory.get();
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            map.put(keyExtractor.apply(next), valueMapper.apply(next));
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return map;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        M res = null;

        try {
            for (ContinuableFuture<M> future : futureList) {
                if (res == null) {
                    res = future.get();
                } else {
                    final M m = future.get();

                    for (Map.Entry<K, V> entry : m.entrySet()) {
                        res.putAll(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return res;
    }

    @Override
    public <A, D> Map<Boolean, D> partitionTo(final Predicate<? super T> predicate, Collector<? super T, A, D> downstream) {
        final Function<T, Boolean> keyExtractor = new Function<T, Boolean>() {
            @Override
            public Boolean apply(T t) {
                return predicate.test(t);
            }
        };

        final Supplier<Map<Boolean, D>> mapFactory = Fn.Suppliers.ofMap();
        final Map<Boolean, D> map = toMap(keyExtractor, downstream, mapFactory);

        if (map.containsKey(Boolean.TRUE) == false) {
            map.put(Boolean.TRUE, downstream.finisher().apply(downstream.supplier().get()));
        } else if (map.containsKey(Boolean.FALSE) == false) {
            map.put(Boolean.FALSE, downstream.finisher().apply(downstream.supplier().get()));
        }

        return map;
    }

    @Override
    public T reduce(final T identity, final BinaryOperator<T> accumulator) {
        if (maxThreadNum <= 1) {
            return super.reduce(identity, accumulator);
        }

        final List<ContinuableFuture<T>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<T>() {
                @Override
                public T call() {
                    T result = identity;
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            result = accumulator.apply(result, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return result;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        T result = (T) NONE;

        try {
            for (ContinuableFuture<T> future : futureList) {
                if (result == NONE) {
                    result = future.get();
                } else {
                    result = accumulator.apply(result, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return result == NONE ? identity : result;
    }

    @Override
    public Optional<T> reduce(final BinaryOperator<T> accumulator) {
        if (maxThreadNum <= 1) {
            return super.reduce(accumulator);
        }

        final List<ContinuableFuture<T>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<T>() {
                @Override
                public T call() {
                    T result = (T) NONE;
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            result = result == NONE ? next : accumulator.apply(result, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return result;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        T result = (T) NONE;

        try {
            for (ContinuableFuture<T> future : futureList) {
                final T tmp = future.get();

                if (tmp == NONE) {
                    continue;
                } else if (result == NONE) {
                    result = tmp;
                } else {
                    result = accumulator.apply(result, tmp);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return result == NONE ? (Optional<T>) Optional.empty() : Optional.of(result);
    }

    @Override
    public <U> U reduce(final U identity, final BiFunction<U, ? super T, U> accumulator, final BinaryOperator<U> combiner) {
        if (maxThreadNum <= 1) {
            return super.reduce(identity, accumulator, combiner);
        }

        final List<ContinuableFuture<U>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<U>() {
                @Override
                public U call() {
                    U result = identity;
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            result = accumulator.apply(result, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return result;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        U result = (U) NONE;

        try {
            for (ContinuableFuture<U> future : futureList) {
                final U tmp = future.get();

                if (result == NONE) {
                    result = tmp;
                } else {
                    result = combiner.apply(result, tmp);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return result == NONE ? identity : result;
    }

    @Override
    public <R> R collect(final Supplier<R> supplier, final BiConsumer<R, ? super T> accumulator, final BiConsumer<R, R> combiner) {
        if (maxThreadNum <= 1) {
            return super.collect(supplier, accumulator, combiner);
        }

        final List<ContinuableFuture<R>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<R>() {
                @Override
                public R call() {
                    final R container = supplier.get();
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            accumulator.accept(container, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return container;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        R container = (R) NONE;

        try {
            for (ContinuableFuture<R> future : futureList) {
                if (container == NONE) {
                    container = future.get();
                } else {
                    combiner.accept(container, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return container == NONE ? supplier.get() : container;
    }

    @Override
    public <R, A> R collect(final Collector<? super T, A, R> collector) {
        if (maxThreadNum <= 1 || collector.characteristics().contains(Collector.Characteristics.CONCURRENT) == false
                || collector.characteristics().contains(Collector.Characteristics.UNORDERED) == false) {
            return sequential().collect(collector);
        }

        final Supplier<A> supplier = collector.supplier();
        final BiConsumer<A, ? super T> accumulator = collector.accumulator();
        final BinaryOperator<A> combiner = collector.combiner();
        final Function<A, R> finisher = collector.finisher();

        final List<ContinuableFuture<A>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<A>() {
                @Override
                public A call() {
                    A container = supplier.get();
                    T next = null;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            accumulator.accept(container, next);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }

                    return container;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        A container = (A) NONE;

        try {
            for (ContinuableFuture<A> future : futureList) {
                if (container == NONE) {
                    container = future.get();
                } else {
                    container = combiner.apply(container, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return finisher.apply(container == NONE ? supplier.get() : container);
    }

    @Override
    public Optional<T> min(Comparator<? super T> comparator) {
        if (elements.hasNext() == false) {
            return Optional.empty();
        } else if (sorted && isSameComparator(comparator, cmp)) {
            return Optional.of(elements.next());
        }

        comparator = comparator == null ? NATURAL_COMPARATOR : comparator;

        return collect(Collectors.minBy(comparator));
    }

    @Override
    public Optional<T> max(Comparator<? super T> comparator) {
        if (elements.hasNext() == false) {
            return Optional.empty();
        } else if (sorted && isSameComparator(comparator, cmp)) {
            T next = null;

            while (elements.hasNext()) {
                next = elements.next();
            }

            return Optional.of(next);
        }

        comparator = comparator == null ? NATURAL_COMPARATOR : comparator;

        return collect(Collectors.maxBy(comparator));
    }

    @Override
    public long count() {
        return elements.count();
    }

    @Override
    public <E extends Exception> boolean anyMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.anyMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(false);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    T next = null;

                    try {
                        while (result.isFalse() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                result.setTrue();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> boolean allMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.allMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    T next = null;

                    try {
                        while (result.isTrue() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next) == false) {
                                result.setFalse();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> boolean noneMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.noneMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    T next = null;

                    try {
                        while (result.isTrue() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                result.setFalse();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> Optional<T> findFirst(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findFirst(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Long, T>> resultHolder = new Holder<>();
        final MutableLong index = MutableLong.of(0);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final Pair<Long, T> pair = new Pair<>();

                    try {
                        while (resultHolder.value() == null && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    pair.left = index.getAndIncrement();
                                    pair.right = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(pair.right)) {
                                synchronized (resultHolder) {
                                    if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                                        resultHolder.setValue(pair.copy());
                                    }
                                }

                                break;
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == null ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> Optional<T> findLast(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findLast(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Long, T>> resultHolder = new Holder<>();
        final MutableLong index = MutableLong.of(0);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final Pair<Long, T> pair = new Pair<>();

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    pair.left = index.getAndIncrement();
                                    pair.right = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(pair.right)) {
                                synchronized (resultHolder) {
                                    if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                                        resultHolder.setValue(pair.copy());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == null ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> Optional<T> findAny(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findAny(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<T> resultHolder = Holder.of((T) NONE);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    T next = null;

                    try {
                        while (resultHolder.value() == NONE && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.next();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                synchronized (resultHolder) {
                                    if (resultHolder.value() == NONE) {
                                        resultHolder.setValue(next);
                                    }
                                }

                                break;
                            }
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == NONE ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value());
    }

    @Override
    public Stream<T> intersection(final Function<? super T, ?> mapper, final Collection<?> c) {
        if (maxThreadNum <= 1) {
            super.intersection(mapper, c);
        }

        final Multiset<?> multiset = Multiset.from(c);

        return filter(new Predicate<T>() {
            @Override
            public boolean test(T value) {
                final Object key = mapper.apply(value);

                synchronized (multiset) {
                    return multiset.getAndRemove(key) > 0;
                }
            }
        });
    }

    @Override
    public Stream<T> difference(final Function<? super T, ?> mapper, final Collection<?> c) {
        if (maxThreadNum <= 1) {
            return super.intersection(mapper, c);
        }

        final Multiset<?> multiset = Multiset.from(c);

        return filter(new Predicate<T>() {
            @Override
            public boolean test(T value) {
                final Object key = mapper.apply(value);

                synchronized (multiset) {
                    return multiset.getAndRemove(key) < 1;
                }
            }
        });
    }

    @Override
    public Stream<T> append(Stream<T> stream) {
        return new ParallelIteratorStream<>(Stream.concat(this, stream), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public Stream<T> prepend(Stream<T> stream) {
        return new ParallelIteratorStream<>(Stream.concat(stream, this), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public Stream<T> merge(final Stream<? extends T> b, final BiFunction<? super T, ? super T, Nth> nextSelector) {
        return new ParallelIteratorStream<>(Stream.merge(this, b, nextSelector), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <T2, R> Stream<R> zipWith(Stream<T2> b, BiFunction<? super T, ? super T2, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, zipFunction), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <T2, T3, R> Stream<R> zipWith(Stream<T2> b, Stream<T3> c, TriFunction<? super T, ? super T2, ? super T3, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, c, zipFunction), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <T2, R> Stream<R> zipWith(Stream<T2> b, T valueForNoneA, T2 valueForNoneB, BiFunction<? super T, ? super T2, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, valueForNoneA, valueForNoneB, zipFunction), false, null, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <T2, T3, R> Stream<R> zipWith(Stream<T2> b, Stream<T3> c, T valueForNoneA, T2 valueForNoneB, T3 valueForNoneC,
            TriFunction<? super T, ? super T2, ? super T3, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, c, valueForNoneA, valueForNoneB, valueForNoneC, zipFunction), false, null, maxThreadNum,
                splitor, closeHandlers);
    }

    //    @Override
    //    public long persist(final PreparedStatement stmt, final int batchSize, final int batchInterval,
    //            final Try.BiConsumer<? super PreparedStatement, ? super T, SQLException> stmtSetter) {
    //        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
    //                batchInterval);
    //
    //        if (maxThreadNum <= 1) {
    //            return sequential().persist(stmt, batchSize, batchInterval, stmtSetter);
    //        }
    //
    //        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
    //        final Holder<Throwable> eHolder = new Holder<>();
    //        final AtomicLong result = new AtomicLong();
    //
    //        for (int i = 0; i < maxThreadNum; i++) {
    //            futureList.add(asyncExecutor.execute(new Runnable() {
    //                @Override
    //                public void run() {
    //                    long cnt = 0;
    //                    T next = null;
    //
    //                    try {
    //                        while (eHolder.value() == null) {
    //                            synchronized (elements) {
    //                                if (elements.hasNext()) {
    //                                    next = elements.next();
    //                                } else {
    //                                    break;
    //                                }
    //                            }
    //
    //                            stmtSetter.accept(stmt, next);
    //                            stmt.addBatch();
    //
    //                            if ((++cnt % batchSize) == 0) {
    //                                stmt.executeBatch();
    //                                stmt.clearBatch();
    //
    //                                if (batchInterval > 0) {
    //                                    N.sleep(batchInterval);
    //                                }
    //                            }
    //                        }
    //
    //                        if ((cnt % batchSize) > 0) {
    //                            stmt.executeBatch();
    //                            stmt.clearBatch();
    //                        }
    //
    //                        result.addAndGet(cnt);
    //                    } catch (Exception e) {
    //                        setError(eHolder, e);
    //                    }
    //                }
    //            }));
    //        }
    //
    //        complete(futureList, eHolder);
    //
    //        return result.longValue();
    //    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public Stream<T> sequential() {
        IteratorStream<T> tmp = sequential;

        if (tmp == null) {
            tmp = new IteratorStream<>(elements, sorted, cmp, closeHandlers);
            sequential = tmp;
        }

        return tmp;
    }

    @Override
    public Stream<T> parallel(int maxThreadNum, Splitor splitor) {
        if (this.maxThreadNum == checkMaxThreadNum(maxThreadNum) && this.splitor == splitor) {
            return this;
        }

        return new ParallelIteratorStream<>(elements, sorted, cmp, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public int maxThreadNum() {
        return maxThreadNum;
    }

    //    @Override
    //    public Stream<T> maxThreadNum(int maxThreadNum) {
    //        if (this.maxThreadNum == checkMaxThreadNum(maxThreadNum)) {
    //            return this;
    //        }
    //
    //        return new ParallelIteratorStream<>(elements, sorted, cmp, maxThreadNum, splitor, closeHandlers);
    //    }

    @Override
    public Splitor splitor() {
        return splitor;
    }

    //    @Override
    //    public Stream<T> splitor(Splitor splitor) {
    //        if (this.splitor == splitor) {
    //            return this;
    //        }
    //
    //        return new ParallelIteratorStream<>(elements, sorted, cmp, maxThreadNum, splitor, closeHandlers);
    //    }

    @Override
    public Stream<T> onClose(Runnable closeHandler) {
        final Set<Runnable> newCloseHandlers = new LocalLinkedHashSet<>(N.isNullOrEmpty(this.closeHandlers) ? 1 : this.closeHandlers.size() + 1);

        if (N.notNullOrEmpty(this.closeHandlers)) {
            newCloseHandlers.addAll(this.closeHandlers);
        }

        newCloseHandlers.add(closeHandler);

        return new ParallelIteratorStream<>(elements, sorted, cmp, maxThreadNum, splitor, newCloseHandlers);
    }
}
