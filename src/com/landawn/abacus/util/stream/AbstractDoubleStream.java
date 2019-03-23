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

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.util.DoubleIterator;
import com.landawn.abacus.util.DoubleList;
import com.landawn.abacus.util.DoubleMatrix;
import com.landawn.abacus.util.DoubleSummaryStatistics;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IndexedDouble;
import com.landawn.abacus.util.Joiner;
import com.landawn.abacus.util.KahanSummation;
import com.landawn.abacus.util.Multiset;
import com.landawn.abacus.util.MutableDouble;
import com.landawn.abacus.util.MutableLong;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Nth;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.Percentage;
import com.landawn.abacus.util.StringUtil.Strings;
import com.landawn.abacus.util.Try;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.DoubleBiFunction;
import com.landawn.abacus.util.function.DoubleBiPredicate;
import com.landawn.abacus.util.function.DoubleConsumer;
import com.landawn.abacus.util.function.DoubleFunction;
import com.landawn.abacus.util.function.DoublePredicate;
import com.landawn.abacus.util.function.DoubleTriFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.ObjDoubleConsumer;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.ToDoubleFunction;

/**
 * 
 */
abstract class AbstractDoubleStream extends DoubleStream {

    AbstractDoubleStream(final boolean sorted, final Collection<Runnable> closeHandlers) {
        super(sorted, closeHandlers);
    }

    @Override
    public DoubleStream distinct() {
        final Set<Object> set = new HashSet<>();

        return newStream(this.sequential().filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                return set.add(value);
            }
        }).iteratorEx(), sorted);
    }

    @Override
    public DoubleStream flattMap(final DoubleFunction<double[]> mapper) {
        return flatMap(new DoubleFunction<DoubleStream>() {
            @Override
            public DoubleStream apply(double t) {
                return DoubleStream.of(mapper.apply(t));
            }
        });
    }

    @Override
    public <T> Stream<T> flattMapToObj(final DoubleFunction<? extends Collection<T>> mapper) {
        return flatMapToObj(new DoubleFunction<Stream<T>>() {
            @Override
            public Stream<T> apply(double t) {
                return Stream.of(mapper.apply(t));
            }
        });
    }

    @Override
    public <T> Stream<T> flatMappToObj(final DoubleFunction<T[]> mapper) {
        return flatMapToObj(new DoubleFunction<Stream<T>>() {
            @Override
            public Stream<T> apply(double t) {
                return Stream.of(mapper.apply(t));
            }
        });
    }

    @Override
    public DoubleStream skip(final long n, final DoubleConsumer action) {
        final DoublePredicate filter = isParallel() ? new DoublePredicate() {
            final AtomicLong cnt = new AtomicLong(n);

            @Override
            public boolean test(double value) {
                return cnt.getAndDecrement() > 0;
            }
        } : new DoublePredicate() {
            final MutableLong cnt = MutableLong.of(n);

            @Override
            public boolean test(double value) {
                return cnt.getAndDecrement() > 0;
            }
        };

        return dropWhile(filter, action);
    }

    @Override
    public DoubleStream removeIf(final DoublePredicate predicate) {
        checkArgNotNull(predicate);

        return filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                return predicate.test(value) == false;
            }
        });
    }

    @Override
    public DoubleStream removeIf(final DoublePredicate predicate, final DoubleConsumer action) {
        checkArgNotNull(predicate);
        checkArgNotNull(predicate);

        return filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                if (predicate.test(value)) {
                    action.accept(value);
                    return false;
                }

                return true;
            }
        });
    }

    @Override
    public DoubleStream dropWhile(final DoublePredicate predicate, final DoubleConsumer action) {
        checkArgNotNull(predicate);
        checkArgNotNull(action);

        return dropWhile(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                if (predicate.test(value)) {
                    action.accept(value);
                    return true;
                }

                return false;
            }
        });
    }

    @Override
    public DoubleStream step(final long step) {
        checkArgPositive(step, "step");

        final long skip = step - 1;
        final DoubleIteratorEx iter = this.iteratorEx();

        final DoubleIterator doubleIterator = new DoubleIteratorEx() {
            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public double nextDouble() {
                final double next = iter.nextDouble();
                iter.skip(skip);
                return next;
            }
        };

        return newStream(doubleIterator, sorted);
    }

    @Override
    public Stream<DoubleStream> split(final int size) {
        return splitToList(size).map(new Function<DoubleList, DoubleStream>() {
            @Override
            public DoubleStream apply(DoubleList t) {
                return new ArrayDoubleStream(t.array(), 0, t.size(), sorted, null);
            }
        });
    }

    @Override
    public Stream<DoubleStream> split(final DoublePredicate predicate) {
        return splitToList(predicate).map(new Function<DoubleList, DoubleStream>() {
            @Override
            public DoubleStream apply(DoubleList t) {
                return new ArrayDoubleStream(t.array(), 0, t.size(), sorted, null);
            }
        });
    }

    @Override
    public Stream<DoubleStream> splitBy(final DoublePredicate where) {
        checkArgNotNull(where);

        final DoubleIteratorEx iter = iteratorEx();

        return newStream(new ObjIteratorEx<DoubleStream>() {
            private int cursor = 0;
            private double next = 0;
            private boolean hasNext = false;

            @Override
            public boolean hasNext() {
                return cursor < 2;
            }

            @Override
            public DoubleStream next() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                DoubleStream result = null;

                if (cursor == 0) {
                    final DoubleList list = new DoubleList();

                    while (iter.hasNext()) {
                        next = iter.nextDouble();

                        if (where.test(next)) {
                            list.add(next);
                        } else {
                            hasNext = true;
                            break;
                        }
                    }

                    result = new ArrayDoubleStream(list.array(), 0, list.size(), sorted, null);
                } else {
                    DoubleIteratorEx iterEx = iter;

                    if (hasNext) {
                        iterEx = new DoubleIteratorEx() {
                            private boolean isFirst = true;

                            @Override
                            public boolean hasNext() {
                                return isFirst || iter.hasNext();
                            }

                            @Override
                            public double nextDouble() {
                                if (hasNext() == false) {
                                    throw new NoSuchElementException();
                                }

                                if (isFirst) {
                                    isFirst = false;
                                    return next;
                                } else {
                                    return iter.nextDouble();
                                }
                            }
                        };
                    }

                    result = new IteratorDoubleStream(iterEx, sorted, null);
                }

                cursor++;

                return result;
            }

            @Override
            public long count() {
                iter.count();

                return 2 - cursor;
            }

            @Override
            public void skip(long n) {
                checkArgNotNegative(n, "n");

                if (n == 0) {
                    return;
                } else if (n == 1) {
                    if (cursor == 0) {
                        while (iter.hasNext()) {
                            next = iter.nextDouble();

                            if (where.test(next) == false) {
                                hasNext = true;
                                break;
                            }
                        }
                    } else {
                        iter.skip(Long.MAX_VALUE);
                    }
                } else {
                    iter.skip(Long.MAX_VALUE);
                }

                cursor = n >= 2 ? 2 : cursor + (int) n;
            }

        }, false, null);
    }

    @Override
    public Stream<DoubleStream> sliding(final int windowSize, final int increment) {
        return slidingToList(windowSize, increment).map(new Function<DoubleList, DoubleStream>() {
            @Override
            public DoubleStream apply(DoubleList t) {
                return new ArrayDoubleStream(t.array(), 0, t.size(), sorted, null);
            }
        });
    }

    @Override
    public DoubleStream collapse(final DoubleBiPredicate collapsible, final DoubleBiFunction<Double> mergeFunction) {
        final DoubleIteratorEx iter = iteratorEx();

        return newStream(new DoubleIteratorEx() {
            private boolean hasNext = false;
            private double next = 0;

            @Override
            public boolean hasNext() {
                return hasNext || iter.hasNext();
            }

            @Override
            public double nextDouble() {
                double res = hasNext ? next : (next = iter.nextDouble());

                while ((hasNext = iter.hasNext())) {
                    if (collapsible.test(next, (next = iter.nextDouble()))) {
                        res = mergeFunction.apply(res, next);
                    } else {
                        break;
                    }
                }

                return res;
            }
        }, false);
    }

    @Override
    public DoubleStream scan(final DoubleBiFunction<Double> accumulator) {
        final DoubleIteratorEx iter = iteratorEx();

        return newStream(new DoubleIteratorEx() {
            private double res = 0;
            private boolean isFirst = true;

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public double nextDouble() {
                if (isFirst) {
                    isFirst = false;
                    return (res = iter.nextDouble());
                } else {
                    return (res = accumulator.apply(res, iter.nextDouble()));
                }
            }
        }, false);
    }

    @Override
    public DoubleStream scan(final double seed, final DoubleBiFunction<Double> accumulator) {
        final DoubleIteratorEx iter = iteratorEx();

        return newStream(new DoubleIteratorEx() {
            private double res = seed;

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public double nextDouble() {
                return (res = accumulator.apply(res, iter.nextDouble()));
            }
        }, false);
    }

    @Override
    public DoubleStream scan(final double seed, final DoubleBiFunction<Double> accumulator, final boolean seedIncluded) {
        if (seedIncluded == false) {
            return scan(seed, accumulator);
        }

        final DoubleIteratorEx iter = iteratorEx();

        return newStream(new DoubleIteratorEx() {
            private boolean isFirst = true;
            private double res = seed;

            @Override
            public boolean hasNext() {
                return isFirst || iter.hasNext();
            }

            @Override
            public double nextDouble() {
                if (isFirst) {
                    isFirst = false;
                    return seed;
                }

                return (res = accumulator.apply(res, iter.nextDouble()));
            }
        }, false);
    }

    @Override
    public DoubleStream intersection(final Collection<?> c) {
        final Multiset<?> multiset = Multiset.from(c);

        return newStream(this.sequential().filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                return multiset.getAndRemove(value) > 0;
            }
        }).iteratorEx(), sorted);
    }

    @Override
    public DoubleStream difference(final Collection<?> c) {
        final Multiset<?> multiset = Multiset.from(c);

        return newStream(this.sequential().filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                return multiset.getAndRemove(value) < 1;
            }
        }).iteratorEx(), sorted);
    }

    @Override
    public DoubleStream symmetricDifference(final Collection<Double> c) {
        final Multiset<?> multiset = Multiset.from(c);

        return newStream(this.sequential().filter(new DoublePredicate() {
            @Override
            public boolean test(double value) {
                return multiset.getAndRemove(value) < 1;
            }
        }).append(Stream.of(c).filter(new Predicate<Double>() {
            @Override
            public boolean test(Double value) {
                return multiset.getAndRemove(value) > 0;
            }
        }).mapToDouble(ToDoubleFunction.UNBOX)).iteratorEx(), false);
    }

    @Override
    public DoubleStream reversed() {
        return newStream(new DoubleIteratorEx() {
            private boolean initialized = false;
            private double[] aar;
            private int cursor;

            @Override
            public boolean hasNext() {
                if (initialized == false) {
                    init();
                }

                return cursor > 0;
            }

            @Override
            public double nextDouble() {
                if (initialized == false) {
                    init();
                }

                if (cursor <= 0) {
                    throw new NoSuchElementException();
                }

                return aar[--cursor];
            }

            @Override
            public long count() {
                if (initialized == false) {
                    init();
                }

                return cursor;
            }

            @Override
            public void skip(long n) {
                checkArgNotNegative(n, "n");

                if (initialized == false) {
                    init();
                }

                cursor = n < cursor ? cursor - (int) n : 0;
            }

            @Override
            public double[] toArray() {
                if (initialized == false) {
                    init();
                }

                final double[] a = new double[cursor];

                for (int i = 0; i < cursor; i++) {
                    a[i] = aar[cursor - i - 1];
                }

                return a;
            }

            private void init() {
                if (initialized == false) {
                    initialized = true;
                    aar = AbstractDoubleStream.this.toArray();
                    cursor = aar.length;
                }
            }
        }, false);
    }

    @Override
    public DoubleStream shuffled(final Random rnd) {
        return lazyLoad(new Function<double[], double[]>() {
            @Override
            public double[] apply(final double[] a) {
                N.shuffle(a, rnd);
                return a;
            }
        }, false);
    }

    @Override
    public DoubleStream rotated(final int distance) {
        return newStream(new DoubleIteratorEx() {
            private boolean initialized = false;
            private double[] aar;
            private int len;
            private int start;
            private int cnt = 0;

            @Override
            public boolean hasNext() {
                if (initialized == false) {
                    init();
                }

                return cnt < len;
            }

            @Override
            public double nextDouble() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                return aar[(start + cnt++) % len];
            }

            @Override
            public long count() {
                if (initialized == false) {
                    init();
                }

                return len - cnt;
            }

            @Override
            public void skip(long n) {
                checkArgNotNegative(n, "n");

                if (initialized == false) {
                    init();
                }

                cnt = n < len - cnt ? cnt + (int) n : len;
            }

            @Override
            public double[] toArray() {
                if (initialized == false) {
                    init();
                }

                final double[] a = new double[len - cnt];

                for (int i = cnt; i < len; i++) {
                    a[i - cnt] = aar[(start + i) % len];
                }

                return a;
            }

            private void init() {
                if (initialized == false) {
                    initialized = true;
                    aar = AbstractDoubleStream.this.toArray();
                    len = aar.length;

                    if (len > 0) {
                        start = distance % len;

                        if (start < 0) {
                            start += len;
                        }

                        start = len - start;
                    }
                }
            }
        }, distance == 0 && sorted);
    }

    @Override
    public DoubleStream sorted() {
        if (sorted) {
            return newStream(iterator(), sorted);
        }

        return lazyLoad(new Function<double[], double[]>() {
            @Override
            public double[] apply(final double[] a) {
                if (isParallel()) {
                    N.parallelSort(a);
                } else {
                    N.sort(a);
                }

                return a;
            }
        }, true);
    }

    @Override
    public DoubleStream reverseSorted() {
        return newStream(new DoubleIteratorEx() {
            private boolean initialized = false;
            private double[] aar;
            private int cursor;

            @Override
            public boolean hasNext() {
                if (initialized == false) {
                    init();
                }

                return cursor > 0;
            }

            @Override
            public double nextDouble() {
                if (initialized == false) {
                    init();
                }

                if (cursor <= 0) {
                    throw new NoSuchElementException();
                }

                return aar[--cursor];
            }

            @Override
            public long count() {
                if (initialized == false) {
                    init();
                }

                return cursor;
            }

            @Override
            public void skip(long n) {
                checkArgNotNegative(n, "n");

                if (initialized == false) {
                    init();
                }

                cursor = n < cursor ? cursor - (int) n : 0;
            }

            @Override
            public double[] toArray() {
                if (initialized == false) {
                    init();
                }

                final double[] a = new double[cursor];

                for (int i = 0; i < cursor; i++) {
                    a[i] = aar[cursor - i - 1];
                }

                return a;
            }

            private void init() {
                if (initialized == false) {
                    initialized = true;
                    aar = AbstractDoubleStream.this.toArray();

                    if (isParallel()) {
                        N.parallelSort(aar);
                    } else {
                        N.sort(aar);
                    }

                    cursor = aar.length;
                }
            }
        }, false);
    }

    private DoubleStream lazyLoad(final Function<double[], double[]> op, final boolean sorted) {
        return newStream(new DoubleIteratorEx() {
            private boolean initialized = false;
            private double[] aar;
            private int cursor = 0;
            private int len;

            @Override
            public boolean hasNext() {
                if (initialized == false) {
                    init();
                }

                return cursor < len;
            }

            @Override
            public double nextDouble() {
                if (initialized == false) {
                    init();
                }

                if (cursor >= len) {
                    throw new NoSuchElementException();
                }

                return aar[cursor++];
            }

            @Override
            public long count() {
                if (initialized == false) {
                    init();
                }

                return len - cursor;
            }

            @Override
            public void skip(long n) {
                checkArgNotNegative(n, "n");

                if (initialized == false) {
                    init();
                }

                cursor = n > len - cursor ? len : cursor + (int) n;
            }

            @Override
            public double[] toArray() {
                if (initialized == false) {
                    init();
                }

                final double[] a = new double[len - cursor];

                for (int i = cursor; i < len; i++) {
                    a[i - cursor] = aar[i];
                }

                return a;
            }

            private void init() {
                if (initialized == false) {
                    initialized = true;
                    aar = op.apply(AbstractDoubleStream.this.toArray());
                    len = aar.length;
                }
            }
        }, sorted);
    }

    @Override
    public Stream<IndexedDouble> indexed() {
        final MutableLong idx = MutableLong.of(0);

        return newStream(this.sequential().mapToObj(new DoubleFunction<IndexedDouble>() {
            @Override
            public IndexedDouble apply(double t) {
                return IndexedDouble.of(t, idx.getAndIncrement());
            }
        }).iterator(), true, INDEXED_DOUBLE_COMPARATOR);
    }

    @Override
    public DoubleStream append(DoubleStream stream) {
        return DoubleStream.concat(this, stream);
    }

    @Override
    public DoubleStream prepend(DoubleStream stream) {
        return DoubleStream.concat(stream, this);
    }

    @Override
    public DoubleStream merge(DoubleStream b, DoubleBiFunction<Nth> nextSelector) {
        return DoubleStream.merge(this, b, nextSelector);
    }

    @Override
    public DoubleStream zipWith(DoubleStream b, DoubleBiFunction<Double> zipFunction) {
        return DoubleStream.zip(this, b, zipFunction);
    }

    @Override
    public DoubleStream zipWith(DoubleStream b, DoubleStream c, DoubleTriFunction<Double> zipFunction) {
        return DoubleStream.zip(this, b, c, zipFunction);
    }

    @Override
    public DoubleStream zipWith(DoubleStream b, double valueForNoneA, double valueForNoneB, DoubleBiFunction<Double> zipFunction) {
        return DoubleStream.zip(this, b, valueForNoneA, valueForNoneB, zipFunction);
    }

    @Override
    public DoubleStream zipWith(DoubleStream b, DoubleStream c, double valueForNoneA, double valueForNoneB, double valueForNoneC,
            DoubleTriFunction<Double> zipFunction) {
        return DoubleStream.zip(this, b, c, valueForNoneA, valueForNoneB, valueForNoneC, zipFunction);
    }

    @Override
    public DoubleStream top(int n) {
        return top(n, DOUBLE_COMPARATOR);
    }

    //    @Override
    //    public DoubleStream cached() {
    //        return newStream(toArray(), sorted);
    //    }

    @Override
    public <K, V> Map<K, V> toMap(DoubleFunction<? extends K> keyExtractor, DoubleFunction<? extends V> valueMapper) {
        final Supplier<Map<K, V>> mapFactory = Fn.Suppliers.ofMap();

        return toMap(keyExtractor, valueMapper, mapFactory);
    }

    @Override
    public <K, V, M extends Map<K, V>> M toMap(DoubleFunction<? extends K> keyExtractor, DoubleFunction<? extends V> valueMapper, Supplier<M> mapFactory) {
        final BinaryOperator<V> mergeFunction = Fn.throwingMerger();

        return toMap(keyExtractor, valueMapper, mergeFunction, mapFactory);
    }

    @Override
    public <K, V> Map<K, V> toMap(DoubleFunction<? extends K> keyExtractor, DoubleFunction<? extends V> valueMapper, BinaryOperator<V> mergeFunction) {
        final Supplier<Map<K, V>> mapFactory = Fn.Suppliers.ofMap();

        return toMap(keyExtractor, valueMapper, mergeFunction, mapFactory);
    }

    @Override
    public <K, A, D> Map<K, D> toMap(DoubleFunction<? extends K> classifier, Collector<Double, A, D> downstream) {
        final Supplier<Map<K, D>> mapFactory = Fn.Suppliers.ofMap();

        return toMap(classifier, downstream, mapFactory);
    }

    @Override
    public DoubleMatrix toMatrix() {
        return DoubleMatrix.of(toArray());
    }

    @Override
    public double sum() {
        assertNotClosed();

        try {
            return summation().sum();
        } finally {
            close();
        }
    }

    private KahanSummation summation() {
        final KahanSummation summation = new KahanSummation();

        final DoubleConsumer action = new DoubleConsumer() {
            @Override
            public void accept(double t) {
                summation.add(t);
            }
        };

        this.forEach(action);
        return summation;
    }

    @Override
    public OptionalDouble average() {
        assertNotClosed();

        try {
            return summation().average();
        } finally {
            close();
        }
    }

    @Override
    public OptionalDouble first() {
        assertNotClosed();

        try {
            final DoubleIterator iter = this.iteratorEx();

            return iter.hasNext() ? OptionalDouble.of(iter.nextDouble()) : OptionalDouble.empty();
        } finally {
            close();
        }
    }

    @Override
    public OptionalDouble last() {
        assertNotClosed();

        try {
            final DoubleIterator iter = this.iteratorEx();

            if (iter.hasNext() == false) {
                return OptionalDouble.empty();
            }

            double next = iter.nextDouble();

            while (iter.hasNext()) {
                next = iter.nextDouble();
            }

            return OptionalDouble.of(next);
        } finally {
            close();
        }
    }

    @Override
    public OptionalDouble onlyOne() throws DuplicatedResultException {
        assertNotClosed();

        try {
            final DoubleIterator iter = this.iteratorEx();

            final OptionalDouble result = iter.hasNext() ? OptionalDouble.of(iter.nextDouble()) : OptionalDouble.empty();

            if (result.isPresent() && iter.hasNext()) {
                throw new DuplicatedResultException("There are at least two elements: " + Strings.concat(result.get(), ", ", iter.nextDouble()));
            }

            return result;
        } finally {
            close();
        }
    }

    @Override
    public <E extends Exception> OptionalDouble findAny(final Try.DoublePredicate<E> predicate) throws E {
        return findFirst(predicate);
    }

    @Override
    public <E extends Exception, E2 extends Exception> OptionalDouble findFirstOrLast(Try.DoublePredicate<E> predicateForFirst,
            Try.DoublePredicate<E> predicateForLast) throws E, E2 {
        assertNotClosed();

        try {
            final DoubleIteratorEx iter = iteratorEx();
            MutableDouble last = null;
            double next = 0;

            while (iter.hasNext()) {
                next = iter.nextDouble();

                if (predicateForFirst.test(next)) {
                    return OptionalDouble.of(next);
                } else if (predicateForLast.test(next)) {
                    if (last == null) {
                        last = MutableDouble.of(next);
                    } else {
                        last.setValue(next);
                    }
                }
            }

            return last == null ? OptionalDouble.empty() : OptionalDouble.of(last.value());
        } finally {
            close();
        }
    }

    @Override
    public Optional<Map<Percentage, Double>> percentiles() {
        assertNotClosed();

        try {
            final double[] a = sorted().toArray();

            if (a.length == 0) {
                return Optional.empty();
            }

            return Optional.of(N.percentiles(a));
        } finally {
            close();
        }

    }

    @Override
    public Pair<DoubleSummaryStatistics, Optional<Map<Percentage, Double>>> summarizeAndPercentiles() {
        assertNotClosed();

        try {
            final double[] a = sorted().toArray();

            if (N.isNullOrEmpty(a)) {
                return Pair.of(new DoubleSummaryStatistics(), Optional.<Map<Percentage, Double>> empty());
            } else {
                return Pair.of(new DoubleSummaryStatistics(a.length, sum(a), a[0], a[a.length - 1]), Optional.of(N.percentiles(a)));
            }
        } finally {
            close();
        }
    }

    @Override
    public String join(final CharSequence delimiter, final CharSequence prefix, final CharSequence suffix) {
        assertNotClosed();

        try {
            final Joiner joiner = Joiner.with(delimiter, prefix, suffix).reuseCachedBuffer(true);
            final DoubleIteratorEx iter = this.iteratorEx();

            while (iter.hasNext()) {
                joiner.append(iter.nextDouble());
            }

            return joiner.toString();
        } finally {
            close();
        }
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator) {
        final BiConsumer<R, R> combiner = collectingCombiner;

        return collect(supplier, accumulator, combiner);
    }
}
