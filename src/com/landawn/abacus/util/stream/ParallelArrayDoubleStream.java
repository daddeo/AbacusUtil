package com.landawn.abacus.util.stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;

import com.landawn.abacus.util.CompletableFuture;
import com.landawn.abacus.util.DoubleList;
import com.landawn.abacus.util.DoubleSummaryStatistics;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.LongMultiset;
import com.landawn.abacus.util.Multimap;
import com.landawn.abacus.util.Multiset;
import com.landawn.abacus.util.MutableBoolean;
import com.landawn.abacus.util.MutableInt;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Nth;
import com.landawn.abacus.util.Optional;
import com.landawn.abacus.util.OptionalDouble;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.DoubleBiFunction;
import com.landawn.abacus.util.function.DoubleBinaryOperator;
import com.landawn.abacus.util.function.DoubleConsumer;
import com.landawn.abacus.util.function.DoubleFunction;
import com.landawn.abacus.util.function.DoublePredicate;
import com.landawn.abacus.util.function.DoubleToFloatFunction;
import com.landawn.abacus.util.function.DoubleToIntFunction;
import com.landawn.abacus.util.function.DoubleToLongFunction;
import com.landawn.abacus.util.function.DoubleUnaryOperator;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.ObjDoubleConsumer;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.ToDoubleFunction;
import com.landawn.abacus.util.function.ToFloatFunction;
import com.landawn.abacus.util.function.ToIntFunction;
import com.landawn.abacus.util.function.ToLongFunction;

/**
 * This class is a sequential, stateful and immutable stream implementation.
 *
 */
final class ParallelArrayDoubleStream extends AbstractDoubleStream {
    private final double[] elements;
    private final int fromIndex;
    private final int toIndex;
    private final boolean sorted;
    private final int maxThreadNum;
    private final Splitter splitter;
    private volatile ArrayDoubleStream sequential;
    private volatile Stream<Double> boxed;

    ParallelArrayDoubleStream(double[] values, int fromIndex, int toIndex, Collection<Runnable> closeHandlers, boolean sorted, int maxThreadNum,
            Splitter splitter) {
        super(closeHandlers);

        Stream.checkIndex(fromIndex, toIndex, values.length);

        this.elements = values;
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.sorted = sorted;
        this.maxThreadNum = fromIndex >= toIndex ? 1 : N.min(maxThreadNum, Stream.THREAD_POOL_SIZE, toIndex - fromIndex);
        this.splitter = splitter == null ? Stream.DEFAULT_SPILTTER : splitter;
    }

    @Override
    public DoubleStream filter(final DoublePredicate predicate) {
        return filter(predicate, Long.MAX_VALUE);
    }

    @Override
    public DoubleStream filter(final DoublePredicate predicate, final long max) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().filter(predicate, max).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
        }

        final Stream<Double> stream = boxed().filter(new Predicate<Double>() {
            @Override
            public boolean test(Double value) {
                return predicate.test(value);
            }
        }, max);

        return new ParallelIteratorDoubleStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream takeWhile(final DoublePredicate predicate) {
        return takeWhile(predicate, Long.MAX_VALUE);
    }

    @Override
    public DoubleStream takeWhile(final DoublePredicate predicate, final long max) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().takeWhile(predicate, max).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
        }

        final Stream<Double> stream = boxed().takeWhile(new Predicate<Double>() {
            @Override
            public boolean test(Double value) {
                return predicate.test(value);
            }
        }, max);

        return new ParallelIteratorDoubleStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream dropWhile(final DoublePredicate predicate) {
        return dropWhile(predicate, Long.MAX_VALUE);
    }

    @Override
    public DoubleStream dropWhile(final DoublePredicate predicate, final long max) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().dropWhile(predicate, max).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
        }

        final Stream<Double> stream = boxed().dropWhile(new Predicate<Double>() {
            @Override
            public boolean test(Double value) {
                return predicate.test(value);
            }
        }, max);

        return new ParallelIteratorDoubleStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream map(final DoubleUnaryOperator mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().map(mapper).doubleIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final DoubleStream stream = boxed().mapToDouble(new ToDoubleFunction<Double>() {
            @Override
            public double applyAsDouble(Double value) {
                return mapper.applyAsDouble(value);
            }
        });

        return new ParallelIteratorDoubleStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public IntStream mapToInt(final DoubleToIntFunction mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorIntStream(sequential().mapToInt(mapper).intIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final IntStream stream = boxed().mapToInt(new ToIntFunction<Double>() {
            @Override
            public int applyAsInt(Double value) {
                return mapper.applyAsInt(value);
            }
        });

        return new ParallelIteratorIntStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public LongStream mapToLong(final DoubleToLongFunction mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorLongStream(sequential().mapToLong(mapper).longIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final LongStream stream = boxed().mapToLong(new ToLongFunction<Double>() {
            @Override
            public long applyAsLong(Double value) {
                return mapper.applyAsLong(value);
            }
        });

        return new ParallelIteratorLongStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public FloatStream mapToFloat(final DoubleToFloatFunction mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorFloatStream(sequential().mapToFloat(mapper).floatIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final FloatStream stream = boxed().mapToFloat(new ToFloatFunction<Double>() {
            @Override
            public float applyAsFloat(Double value) {
                return mapper.applyAsFloat(value);
            }
        });

        return new ParallelIteratorFloatStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public <U> Stream<U> mapToObj(final DoubleFunction<? extends U> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<U>(sequential().mapToObj(mapper).iterator(), closeHandlers, false, null, maxThreadNum, splitter);
        }

        return boxed().map(new Function<Double, U>() {
            @Override
            public U apply(Double value) {
                return mapper.apply(value);
            }
        });
    }

    @Override
    public DoubleStream flatMap(final DoubleFunction<? extends DoubleStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorDoubleStream(sequential().flatMap(mapper).doubleIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final DoubleStream stream = boxed().flatMapToDouble(new Function<Double, DoubleStream>() {
            @Override
            public DoubleStream apply(Double value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorDoubleStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public IntStream flatMapToInt(final DoubleFunction<? extends IntStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorIntStream(sequential().flatMapToInt(mapper).intIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final IntStream stream = boxed().flatMapToInt(new Function<Double, IntStream>() {
            @Override
            public IntStream apply(Double value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorIntStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public LongStream flatMapToLong(final DoubleFunction<? extends LongStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorLongStream(sequential().flatMapToLong(mapper).longIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final LongStream stream = boxed().flatMapToLong(new Function<Double, LongStream>() {
            @Override
            public LongStream apply(Double value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorLongStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public FloatStream flatMapToFloat(final DoubleFunction<? extends FloatStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorFloatStream(sequential().flatMapToFloat(mapper).floatIterator(), closeHandlers, false, maxThreadNum, splitter);
        }

        final FloatStream stream = boxed().flatMapToFloat(new Function<Double, FloatStream>() {
            @Override
            public FloatStream apply(Double value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorFloatStream(stream, closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public <T> Stream<T> flatMapToObj(final DoubleFunction<? extends Stream<T>> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<>(sequential().flatMapToObj(mapper).iterator(), closeHandlers, false, null, maxThreadNum, splitter);
        }

        return boxed().flatMap(new Function<Double, Stream<T>>() {
            @Override
            public Stream<T> apply(Double value) {
                return mapper.apply(value);
            }
        });
    }

    @Override
    public Stream<DoubleStream> split(final int size) {
        return new ParallelIteratorStream<DoubleStream>(new ImmutableIterator<DoubleStream>() {
            private int cursor = fromIndex;

            @Override
            public boolean hasNext() {
                return cursor < toIndex;
            }

            @Override
            public DoubleStream next() {
                if (cursor >= toIndex) {
                    throw new NoSuchElementException();
                }

                return new ArrayDoubleStream(elements, cursor, (cursor = toIndex - cursor > size ? cursor + size : toIndex), null, sorted);
            }

        }, closeHandlers, false, null, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream distinct() {
        final double[] a = N.removeDuplicates(elements, fromIndex, toIndex, sorted);
        return new ParallelArrayDoubleStream(a, 0, a.length, closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream top(int n) {
        return top(n, Stream.DOUBLE_COMPARATOR);
    }

    @Override
    public DoubleStream top(int n, Comparator<? super Double> comparator) {
        if (n < 1) {
            throw new IllegalArgumentException("'n' can not be less than 1");
        }

        if (n >= toIndex - fromIndex) {
            return this;
        } else if (sorted && Stream.isSameComparator(comparator, Stream.DOUBLE_COMPARATOR)) {
            return new ParallelArrayDoubleStream(elements, toIndex - n, toIndex, closeHandlers, sorted, maxThreadNum, splitter);
        } else {
            final double[] a = N.top(elements, fromIndex, toIndex, n, comparator);
            return new ParallelArrayDoubleStream(a, 0, a.length, closeHandlers, sorted, maxThreadNum, splitter);
        }
    }

    @Override
    public DoubleStream sorted() {
        if (sorted) {
            return this;
        }

        final double[] a = N.copyOfRange(elements, fromIndex, toIndex);
        N.parallelSort(a);
        return new ParallelArrayDoubleStream(a, 0, a.length, closeHandlers, true, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream peek(DoubleConsumer action) {
        for (int i = fromIndex; i < toIndex; i++) {
            action.accept(elements[i]);
        }

        // return new DoubleStreamImpl(values, fromIndex, toIndex, sorted, closeHandlers);
        return this;
    }

    @Override
    public DoubleStream limit(long maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("'maxSize' can't be negative: " + maxSize);
        } else if (maxSize >= toIndex - fromIndex) {
            return this;
        }

        return new ParallelArrayDoubleStream(elements, fromIndex, (int) (fromIndex + maxSize), closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream skip(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("The skipped number can't be negative: " + n);
        } else if (n == 0) {
            return this;
        }

        if (n >= toIndex - fromIndex) {
            return new ParallelArrayDoubleStream(elements, toIndex, toIndex, closeHandlers, sorted, maxThreadNum, splitter);
        } else {
            return new ParallelArrayDoubleStream(elements, (int) (fromIndex + n), toIndex, closeHandlers, sorted, maxThreadNum, splitter);
        }
    }

    @Override
    public void forEach(final DoubleConsumer action) {
        if (maxThreadNum <= 1) {
            sequential().forEach(action);
            return;
        }

        final List<CompletableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                action.accept(elements[cursor++]);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        double next = 0;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                action.accept(next);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Void> future : futureList) {
                future.get();
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    //    @Override
    //    public boolean forEach2(final DoubleFunction<Boolean> action) {
    //        if (maxThreadNum <= 1) {
    //            return sequential().forEach2(action);
    //        }
    //
    //        final List<CompletableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
    //        final Holder<Throwable> eHolder = new Holder<>();
    //        final MutableBoolean result = MutableBoolean.of(true);
    //
    //        if (splitter == Splitter.ARRAY) {
    //            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;
    //
    //            for (int i = 0; i < maxThreadNum; i++) {
    //                final int sliceIndex = i;
    //
    //                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
    //                    @Override
    //                    public void run() {
    //                        int cursor = fromIndex + sliceIndex * sliceSize;
    //                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
    //
    //                        try {
    //                            while (cursor < to && result.isTrue() && eHolder.value() == null) {
    //                                if (action.apply(elements[cursor++]) == false) {
    //                                    result.setFalse();
    //                                    break;
    //                                }
    //                            }
    //                        } catch (Throwable e) {
    //                            Stream.setError(eHolder, e);
    //                        }
    //                    }
    //                }));
    //            }
    //        } else {
    //            final MutableInt cursor = MutableInt.of(fromIndex);
    //
    //            for (int i = 0; i < maxThreadNum; i++) {
    //                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
    //                    @Override
    //                    public void run() {
    //                        double next = 0;
    //
    //                        try {
    //                            while (result.isTrue() && eHolder.value() == null) {
    //                                synchronized (elements) {
    //                                    if (cursor.intValue() < toIndex) {
    //                                        next = elements[cursor.getAndIncrement()];
    //                                    } else {
    //                                        break;
    //                                    }
    //                                }
    //
    //                                if (action.apply(next) == false) {
    //                                    result.setFalse();
    //                                    break;
    //                                }
    //                            }
    //                        } catch (Throwable e) {
    //                            Stream.setError(eHolder, e);
    //                        }
    //                    }
    //                }));
    //            }
    //        }
    //
    //        if (eHolder.value() != null) {
    //            throw N.toRuntimeException(eHolder.value());
    //        }
    //
    //        try {
    //            for (CompletableFuture<Void> future : futureList) {
    //                future.get();
    //            }
    //        } catch (Exception e) {
    //            throw N.toRuntimeException(e);
    //        }
    //
    //        return result.booleanValue();
    //    }

    @Override
    public double[] toArray() {
        return N.copyOfRange(elements, fromIndex, toIndex);
    }

    @Override
    public DoubleList toDoubleList() {
        return DoubleList.of(N.copyOfRange(elements, fromIndex, toIndex));
    }

    @Override
    public List<Double> toList() {
        final List<Double> result = new ArrayList<>();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public List<Double> toList(Supplier<? extends List<Double>> supplier) {
        final List<Double> result = supplier.get();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public Set<Double> toSet() {
        final Set<Double> result = new HashSet<>();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public Set<Double> toSet(Supplier<? extends Set<Double>> supplier) {
        final Set<Double> result = supplier.get();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public Multiset<Double> toMultiset() {
        final Multiset<Double> result = new Multiset<>();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public Multiset<Double> toMultiset(Supplier<? extends Multiset<Double>> supplier) {
        final Multiset<Double> result = supplier.get();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public LongMultiset<Double> toLongMultiset() {
        final LongMultiset<Double> result = new LongMultiset<>();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public LongMultiset<Double> toLongMultiset(Supplier<? extends LongMultiset<Double>> supplier) {
        final LongMultiset<Double> result = supplier.get();

        for (int i = fromIndex; i < toIndex; i++) {
            result.add(elements[i]);
        }

        return result;
    }

    @Override
    public <K> Map<K, List<Double>> toMap(DoubleFunction<? extends K> classifier) {
        return toMap(classifier, new Supplier<Map<K, List<Double>>>() {
            @Override
            public Map<K, List<Double>> get() {
                return new HashMap<>();
            }
        });
    }

    @Override
    public <K, M extends Map<K, List<Double>>> M toMap(DoubleFunction<? extends K> classifier, Supplier<M> mapFactory) {
        final Collector<Double, ?, List<Double>> downstream = Collectors.toList();
        return toMap(classifier, downstream, mapFactory);
    }

    @Override
    public <K, A, D> Map<K, D> toMap(DoubleFunction<? extends K> classifier, Collector<Double, A, D> downstream) {
        return toMap(classifier, downstream, new Supplier<Map<K, D>>() {
            @Override
            public Map<K, D> get() {
                return new HashMap<>();
            }
        });
    }

    @Override
    public <K, D, A, M extends Map<K, D>> M toMap(final DoubleFunction<? extends K> classifier, final Collector<Double, A, D> downstream,
            final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return sequential().toMap(classifier, downstream, mapFactory);
        }

        final Function<? super Double, ? extends K> classifier2 = new Function<Double, K>() {
            @Override
            public K apply(Double value) {
                return classifier.apply(value);
            }
        };

        return boxed().toMap(classifier2, downstream, mapFactory);
    }

    @Override
    public <K, U> Map<K, U> toMap(DoubleFunction<? extends K> keyMapper, DoubleFunction<? extends U> valueMapper) {
        return toMap(keyMapper, valueMapper, new Supplier<Map<K, U>>() {
            @Override
            public Map<K, U> get() {
                return new HashMap<>();
            }
        });
    }

    @Override
    public <K, U, M extends Map<K, U>> M toMap(DoubleFunction<? extends K> keyMapper, DoubleFunction<? extends U> valueMapper, Supplier<M> mapSupplier) {
        final BinaryOperator<U> mergeFunction = Collectors.throwingMerger();
        return toMap(keyMapper, valueMapper, mergeFunction, mapSupplier);
    }

    @Override
    public <K, U> Map<K, U> toMap(DoubleFunction<? extends K> keyMapper, DoubleFunction<? extends U> valueMapper, BinaryOperator<U> mergeFunction) {
        return toMap(keyMapper, valueMapper, mergeFunction, new Supplier<Map<K, U>>() {
            @Override
            public Map<K, U> get() {
                return new HashMap<>();
            }
        });
    }

    @Override
    public <K, U, M extends Map<K, U>> M toMap(final DoubleFunction<? extends K> keyMapper, final DoubleFunction<? extends U> valueMapper,
            final BinaryOperator<U> mergeFunction, final Supplier<M> mapSupplier) {
        if (maxThreadNum <= 1) {
            return sequential().toMap(keyMapper, valueMapper, mergeFunction, mapSupplier);
        }

        final Function<? super Double, ? extends K> keyMapper2 = new Function<Double, K>() {
            @Override
            public K apply(Double value) {
                return keyMapper.apply(value);
            }
        };

        final Function<? super Double, ? extends U> valueMapper2 = new Function<Double, U>() {
            @Override
            public U apply(Double value) {
                return valueMapper.apply(value);
            }
        };

        return boxed().toMap(keyMapper2, valueMapper2, mergeFunction, mapSupplier);
    }

    @Override
    public <K, U> Multimap<K, U, List<U>> toMultimap(DoubleFunction<? extends K> keyMapper, DoubleFunction<? extends U> valueMapper) {
        return toMultimap(keyMapper, valueMapper, new Supplier<Multimap<K, U, List<U>>>() {
            @Override
            public Multimap<K, U, List<U>> get() {
                return N.newListMultimap();
            }
        });
    }

    @Override
    public <K, U, V extends Collection<U>> Multimap<K, U, V> toMultimap(final DoubleFunction<? extends K> keyMapper,
            final DoubleFunction<? extends U> valueMapper, final Supplier<Multimap<K, U, V>> mapSupplier) {

        if (maxThreadNum <= 1) {
            return sequential().toMultimap(keyMapper, valueMapper, mapSupplier);
        }

        final Function<? super Double, ? extends K> keyMapper2 = new Function<Double, K>() {
            @Override
            public K apply(Double value) {
                return keyMapper.apply(value);
            }
        };

        final Function<? super Double, ? extends U> valueMapper2 = new Function<Double, U>() {
            @Override
            public U apply(Double value) {
                return valueMapper.apply(value);
            }
        };

        return boxed().toMultimap(keyMapper2, valueMapper2, mapSupplier);
    }

    @Override
    public double reduce(final double identity, final DoubleBinaryOperator op) {
        if (maxThreadNum <= 1) {
            return sequential().reduce(identity, op);
        }

        final List<CompletableFuture<Double>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                    @Override
                    public Double call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        double result = identity;

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                result = op.applyAsDouble(result, elements[cursor++]);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                    @Override
                    public Double call() {
                        double result = identity;
                        double next = 0;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                result = op.applyAsDouble(result, next);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        Double result = null;

        try {
            for (CompletableFuture<Double> future : futureList) {
                if (result == null) {
                    result = future.get();
                } else {
                    result = op.applyAsDouble(result, future.get());
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result == null ? identity : result;
    }

    @Override
    public OptionalDouble reduce(final DoubleBinaryOperator accumulator) {
        if (maxThreadNum <= 1) {
            return sequential().reduce(accumulator);
        }

        final List<CompletableFuture<Double>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                    @Override
                    public Double call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        if (cursor >= to) {
                            return null;
                        }

                        double result = elements[cursor++];

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                result = accumulator.applyAsDouble(result, elements[cursor++]);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                    @Override
                    public Double call() {
                        double result = 0;

                        synchronized (elements) {
                            if (cursor.intValue() < toIndex) {
                                result = elements[cursor.getAndIncrement()];
                            } else {
                                return null;
                            }
                        }

                        double next = 0;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                result = accumulator.applyAsDouble(result, next);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        Double result = null;

        try {
            for (CompletableFuture<Double> future : futureList) {
                final Double tmp = future.get();

                if (tmp == null) {
                    continue;
                } else if (result == null) {
                    result = tmp;
                } else {
                    result = accumulator.applyAsDouble(result, tmp);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result == null ? OptionalDouble.empty() : OptionalDouble.of(result);
    }

    @Override
    public <R> R collect(final Supplier<R> supplier, final ObjDoubleConsumer<R> accumulator, final BiConsumer<R, R> combiner) {
        if (maxThreadNum <= 1) {
            return sequential().collect(supplier, accumulator, combiner);
        }

        final List<CompletableFuture<R>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<R>() {
                    @Override
                    public R call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        R container = supplier.get();

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                accumulator.accept(container, elements[cursor++]);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<R>() {
                    @Override
                    public R call() {
                        R container = supplier.get();
                        double next = 0;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                accumulator.accept(container, next);
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        R container = (R) Stream.NONE;

        try {
            for (CompletableFuture<R> future : futureList) {
                final R tmp = future.get();

                if (container == Stream.NONE) {
                    container = tmp;
                } else {
                    combiner.accept(container, tmp);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return container == Stream.NONE ? supplier.get() : container;
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator) {
        if (Stream.logger.isWarnEnabled()) {
            Stream.logger.warn("'collect' is sequentially executed in parallel stream");
        }

        return sequential().collect(supplier, accumulator);
    }

    @Override
    public OptionalDouble min() {
        if (count() == 0) {
            return OptionalDouble.empty();
        } else if (sorted) {
            return OptionalDouble.of(elements[fromIndex]);
        } else if (maxThreadNum <= 1) {
            return OptionalDouble.of(N.min(elements, fromIndex, toIndex));
        }

        final List<CompletableFuture<Double>> futureList = new ArrayList<>(maxThreadNum);
        final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

        for (int i = 0; i < maxThreadNum; i++) {
            final int sliceIndex = i;

            futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                @Override
                public Double call() {
                    int cursor = fromIndex + sliceIndex * sliceSize;
                    final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    return cursor >= to ? null : N.min(elements, cursor, to);
                }
            }));
        }

        Double min = null;

        try {
            for (CompletableFuture<Double> future : futureList) {
                final Double tmp = future.get();

                if (tmp == null) {
                    continue;
                } else if (min == null || N.compare(tmp.doubleValue(), min.doubleValue()) < 0) {
                    min = tmp;
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return min == null ? OptionalDouble.empty() : OptionalDouble.of(min);
    }

    @Override
    public OptionalDouble max() {
        if (count() == 0) {
            return OptionalDouble.empty();
        } else if (sorted) {
            return OptionalDouble.of(elements[toIndex - 1]);
        } else if (maxThreadNum <= 1) {
            return OptionalDouble.of(N.max(elements, fromIndex, toIndex));
        }

        final List<CompletableFuture<Double>> futureList = new ArrayList<>(maxThreadNum);
        final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

        for (int i = 0; i < maxThreadNum; i++) {
            final int sliceIndex = i;

            futureList.add(Stream.asyncExecutor.execute(new Callable<Double>() {
                @Override
                public Double call() {
                    int cursor = fromIndex + sliceIndex * sliceSize;
                    final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    return cursor >= to ? null : N.max(elements, cursor, to);
                }
            }));
        }

        Double min = null;

        try {
            for (CompletableFuture<Double> future : futureList) {
                final Double tmp = future.get();

                if (tmp == null) {
                    continue;
                } else if (min == null || N.compare(tmp.doubleValue(), min.doubleValue()) > 0) {
                    min = tmp;
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return min == null ? OptionalDouble.empty() : OptionalDouble.of(min);
    }

    @Override
    public OptionalDouble kthLargest(int k) {
        if (count() == 0 || k > toIndex - fromIndex) {
            return OptionalDouble.empty();
        } else if (sorted) {
            return OptionalDouble.of(elements[toIndex - k]);
        }

        return OptionalDouble.of(N.kthLargest(elements, fromIndex, toIndex, k));
    }

    @Override
    public Double sum() {
        if (count() == 0) {
            return 0d;
        } else if (maxThreadNum <= 1) {
            return sequential().sum();
        }

        final Supplier<double[]> supplier = new Supplier<double[]>() {
            @Override
            public double[] get() {
                return new double[3];
            }
        };

        final ObjDoubleConsumer<double[]> accumulator = new ObjDoubleConsumer<double[]>() {
            @Override
            public void accept(double[] ll, double d) {
                Collectors.sumWithCompensation(ll, d);
                ll[2] += d;
            }
        };

        final BiConsumer<double[], double[]> combiner = new BiConsumer<double[], double[]>() {
            @Override
            public void accept(double[] ll, double[] rr) {
                Collectors.sumWithCompensation(ll, rr[0]);
                Collectors.sumWithCompensation(ll, rr[1]);
                ll[2] += rr[2];
            }
        };

        final List<CompletableFuture<double[]>> futureList = new ArrayList<>(maxThreadNum);
        final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

        for (int i = 0; i < maxThreadNum; i++) {
            final int sliceIndex = i;

            futureList.add(Stream.asyncExecutor.execute(new Callable<double[]>() {
                @Override
                public double[] call() {
                    int cursor = fromIndex + sliceIndex * sliceSize;
                    final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    double[] container = supplier.get();

                    while (cursor < to) {
                        accumulator.accept(container, elements[cursor++]);
                    }

                    return container;
                }
            }));
        }

        double[] summation = null;

        try {
            for (CompletableFuture<double[]> future : futureList) {
                final double[] tmp = future.get();

                if (summation == null) {
                    summation = tmp;
                } else {
                    combiner.accept(summation, tmp);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return Collectors.computeFinalSum(summation);
    }

    @Override
    public OptionalDouble average() {
        if (count() == 0) {
            return OptionalDouble.empty();
        } else if (maxThreadNum <= 1) {
            return sequential().average();
        }

        final Supplier<double[]> supplier = new Supplier<double[]>() {
            @Override
            public double[] get() {
                return new double[4];
            }
        };

        final ObjDoubleConsumer<double[]> accumulator = new ObjDoubleConsumer<double[]>() {
            @Override
            public void accept(double[] ll, double d) {
                ll[2]++;
                Collectors.sumWithCompensation(ll, d);
                ll[3] += d;
            }
        };

        final BiConsumer<double[], double[]> combiner = new BiConsumer<double[], double[]>() {
            @Override
            public void accept(double[] ll, double[] rr) {
                Collectors.sumWithCompensation(ll, rr[0]);
                Collectors.sumWithCompensation(ll, rr[1]);
                ll[2] += rr[2];
                ll[3] += rr[3];
            }
        };

        final List<CompletableFuture<double[]>> futureList = new ArrayList<>(maxThreadNum);
        final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

        for (int i = 0; i < maxThreadNum; i++) {
            final int sliceIndex = i;

            futureList.add(Stream.asyncExecutor.execute(new Callable<double[]>() {
                @Override
                public double[] call() {
                    int cursor = fromIndex + sliceIndex * sliceSize;
                    final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    double[] container = supplier.get();

                    while (cursor < to) {
                        accumulator.accept(container, elements[cursor++]);
                    }

                    return container;
                }
            }));
        }

        double[] avg = null;

        try {
            for (CompletableFuture<double[]> future : futureList) {
                final double[] tmp = future.get();

                if (avg == null) {
                    avg = tmp;
                } else {
                    combiner.accept(avg, tmp);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return avg[2] > 0 ? OptionalDouble.of(Collectors.computeFinalSum(avg) / avg[2]) : OptionalDouble.empty();
    }

    @Override
    public long count() {
        return toIndex - fromIndex;
    }

    @Override
    public DoubleSummaryStatistics summarize() {
        if (count() == 0) {
            return new DoubleSummaryStatistics();
        } else if (maxThreadNum <= 1) {
            return sequential().summarize();
        }

        final List<CompletableFuture<DoubleSummaryStatistics>> futureList = new ArrayList<>(maxThreadNum);
        final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

        for (int i = 0; i < maxThreadNum; i++) {
            final int sliceIndex = i;

            futureList.add(Stream.asyncExecutor.execute(new Callable<DoubleSummaryStatistics>() {
                @Override
                public DoubleSummaryStatistics call() {
                    int cursor = fromIndex + sliceIndex * sliceSize;
                    final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    final DoubleSummaryStatistics result = new DoubleSummaryStatistics();

                    for (int i = cursor; i < to; i++) {
                        result.accept(elements[i]);
                    }

                    return result;
                }
            }));
        }

        DoubleSummaryStatistics result = null;

        try {
            for (CompletableFuture<DoubleSummaryStatistics> future : futureList) {
                final DoubleSummaryStatistics tmp = future.get();

                if (tmp == null) {
                    continue;
                } else if (result == null) {
                    result = tmp;
                } else {
                    result.combine(tmp);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result;
    }

    @Override
    public Optional<Map<String, Double>> distribution() {
        if (count() == 0) {
            return Optional.empty();
        }

        final double[] a = sorted().toArray();

        return Optional.of(N.distribution(a));
    }

    @Override
    public boolean anyMatch(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().anyMatch(predicate);
        }

        final List<CompletableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(false);

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isFalse() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++])) {
                                    result.setTrue();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        double next = 0;

                        try {
                            while (result.isFalse() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    result.setTrue();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Void> future : futureList) {
                future.get();
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result.booleanValue();
    }

    @Override
    public boolean allMatch(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().allMatch(predicate);
        }

        final List<CompletableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isTrue() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++]) == false) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        double next = 0;

                        try {
                            while (result.isTrue() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next) == false) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Void> future : futureList) {
                future.get();
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result.booleanValue();
    }

    @Override
    public boolean noneMatch(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().noneMatch(predicate);
        }

        final List<CompletableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isTrue() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++])) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        double next = 0;

                        try {
                            while (result.isTrue() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Void> future : futureList) {
                future.get();
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return result.booleanValue();
    }

    //    @Override
    //    public OptionalDouble findFirst() {
    //        return count() == 0 ? OptionalDouble.empty() : OptionalDouble.of(elements[fromIndex]);
    //    }

    @Override
    public OptionalDouble findFirst(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().findFirst(predicate);
        }

        final List<CompletableFuture<Pair<Integer, Double>>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Integer, Double>> resultHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<Pair<Integer, Double>>() {
                    @Override
                    public Pair<Integer, Double> call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                        final Pair<Integer, Double> pair = new Pair<>();

                        try {
                            while (cursor < to && resultHolder.value() == null && eHolder.value() == null) {
                                pair.left = cursor;
                                pair.right = elements[cursor++];

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                                            resultHolder.setValue(pair);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return pair;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<Pair<Integer, Double>>() {
                    @Override
                    public Pair<Integer, Double> call() {
                        final Pair<Integer, Double> pair = new Pair<>();

                        try {
                            while (resultHolder.value() == null && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        pair.left = cursor.intValue();
                                        pair.right = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                                            resultHolder.setValue(pair);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return pair;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Pair<Integer, Double>> future : futureList) {
                final Pair<Integer, Double> pair = future.get();

                if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                    resultHolder.setValue(pair);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return resultHolder.value() == null ? OptionalDouble.empty() : OptionalDouble.of(resultHolder.value().right);
    }

    //    @Override
    //    public OptionalDouble findLast() {
    //        return count() == 0 ? OptionalDouble.empty() : OptionalDouble.of(elements[toIndex - 1]);
    //    }

    @Override
    public OptionalDouble findLast(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().findLast(predicate);
        }

        final List<CompletableFuture<Pair<Integer, Double>>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Integer, Double>> resultHolder = new Holder<>();

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<Pair<Integer, Double>>() {
                    @Override
                    public Pair<Integer, Double> call() {
                        final int from = fromIndex + sliceIndex * sliceSize;
                        int cursor = toIndex - from > sliceSize ? from + sliceSize : toIndex;
                        final Pair<Integer, Double> pair = new Pair<>();

                        try {
                            while (cursor > from && resultHolder.value() == null && eHolder.value() == null) {
                                pair.left = cursor;
                                pair.right = elements[--cursor];

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                                            resultHolder.setValue(pair);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return pair;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(toIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<Pair<Integer, Double>>() {
                    @Override
                    public Pair<Integer, Double> call() {
                        final Pair<Integer, Double> pair = new Pair<>();

                        try {
                            while (resultHolder.value() == null && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() > fromIndex) {
                                        pair.left = cursor.intValue();
                                        pair.right = elements[cursor.decrementAndGet()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                                            resultHolder.setValue(pair);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return pair;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Pair<Integer, Double>> future : futureList) {
                final Pair<Integer, Double> pair = future.get();

                if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                    resultHolder.setValue(pair);
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return resultHolder.value() == null ? OptionalDouble.empty() : OptionalDouble.of(resultHolder.value().right);
    }

    //    @Override
    //    public OptionalDouble findAny() {
    //        return count() == 0 ? OptionalDouble.empty() : OptionalDouble.of(elements[fromIndex]);
    //    }

    @Override
    public OptionalDouble findAny(final DoublePredicate predicate) {
        if (maxThreadNum <= 1) {
            return sequential().findAny(predicate);
        }

        final List<CompletableFuture<Object>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Object> resultHolder = Holder.of(Stream.NONE);

        if (splitter == Splitter.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) % maxThreadNum == 0 ? (toIndex - fromIndex) / maxThreadNum : (toIndex - fromIndex) / maxThreadNum + 1;

            for (int i = 0; i < maxThreadNum; i++) {
                final int sliceIndex = i;

                futureList.add(Stream.asyncExecutor.execute(new Callable<Object>() {
                    @Override
                    public Object call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                        double next = 0;

                        try {
                            while (cursor < to && resultHolder.value() == null && eHolder.value() == null) {
                                next = elements[cursor++];

                                if (predicate.test(next)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == Stream.NONE) {
                                            resultHolder.setValue(next);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return next;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < maxThreadNum; i++) {
                futureList.add(Stream.asyncExecutor.execute(new Callable<Object>() {
                    @Override
                    public Object call() {
                        double next = 0;

                        try {
                            while (resultHolder.value() == Stream.NONE && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == Stream.NONE) {
                                            resultHolder.setValue(next);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            Stream.setError(eHolder, e);
                        }

                        return next;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        try {
            for (CompletableFuture<Object> future : futureList) {
                if (resultHolder.value() == Stream.NONE) {
                    future.get();
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }

        return resultHolder.value() == Stream.NONE ? OptionalDouble.empty() : OptionalDouble.of((Double) resultHolder.value());
    }

    @Override
    public DoubleStream except(final Collection<?> c) {
        return new ParallelIteratorDoubleStream(this.sequential().except(c).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream intersect(final Collection<?> c) {
        return new ParallelIteratorDoubleStream(this.sequential().intersect(c).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
    }

    //    @Override
    //    public DoubleStream exclude(final Collection<?> c) {
    //        if (maxThreadNum <= 1) {
    //            return new ParallelIteratorDoubleStream(sequential().exclude(c).doubleIterator(), closeHandlers, sorted, maxThreadNum, splitter);
    //        }
    //
    //        final Set<?> set = c instanceof Set ? (Set<?>) c : new HashSet<>(c);
    //
    //        return filter(new DoublePredicate() {
    //            @Override
    //            public boolean test(double value) {
    //                return !set.contains(value);
    //            }
    //        });
    //    }

    @Override
    public Stream<Double> boxed() {
        Stream<Double> tmp = boxed;

        if (tmp == null) {
            tmp = new ParallelIteratorStream<Double>(iterator(), closeHandlers, sorted, sorted ? Stream.DOUBLE_COMPARATOR : null, maxThreadNum, splitter);
            boxed = tmp;
        }

        return tmp;
    }

    @Override
    public DoubleStream append(final DoubleStream stream) {
        return new ParallelIteratorDoubleStream(DoubleStream.concat(this, stream), closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream merge(final DoubleStream b, final DoubleBiFunction<Nth> nextSelector) {
        return new ParallelIteratorDoubleStream(DoubleStream.merge(this, b, nextSelector), closeHandlers, false, maxThreadNum, splitter);
    }

    @Override
    public ImmutableIterator<Double> iterator() {
        return this.sequential().iterator();
    }

    @Override
    public ImmutableDoubleIterator doubleIterator() {
        return this.sequential().doubleIterator();
    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public DoubleStream sequential() {
        ArrayDoubleStream tmp = sequential;

        if (tmp == null) {
            tmp = new ArrayDoubleStream(elements, fromIndex, toIndex, closeHandlers, sorted);
            sequential = tmp;
        }

        return tmp;
    }

    @Override
    public DoubleStream parallel(int maxThreadNum, Splitter splitter) {
        if (this.maxThreadNum == maxThreadNum && this.splitter == splitter) {
            return this;
        }

        return new ParallelArrayDoubleStream(elements, fromIndex, toIndex, closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public int maxThreadNum() {
        return maxThreadNum;
    }

    @Override
    public DoubleStream maxThreadNum(int maxThreadNum) {
        if (this.maxThreadNum == maxThreadNum) {
            return this;
        }

        return new ParallelArrayDoubleStream(elements, fromIndex, toIndex, closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public BaseStream.Splitter splitter() {
        return splitter;
    }

    @Override
    public DoubleStream splitter(BaseStream.Splitter splitter) {
        if (this.splitter == splitter) {
            return this;
        }

        return new ParallelArrayDoubleStream(elements, fromIndex, toIndex, closeHandlers, sorted, maxThreadNum, splitter);
    }

    @Override
    public DoubleStream onClose(Runnable closeHandler) {
        final Set<Runnable> newCloseHandlers = new AbstractStream.LocalLinkedHashSet<>(N.isNullOrEmpty(this.closeHandlers) ? 1 : this.closeHandlers.size() + 1);

        if (N.notNullOrEmpty(this.closeHandlers)) {
            newCloseHandlers.addAll(this.closeHandlers);
        }

        newCloseHandlers.add(closeHandler);

        return new ParallelArrayDoubleStream(elements, fromIndex, toIndex, newCloseHandlers, sorted, maxThreadNum, splitter);
    }
}