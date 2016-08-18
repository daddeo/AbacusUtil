package com.landawn.abacus.util.stream;

import com.landawn.abacus.util.IntList;

abstract class ImmutableIntIterator {
    public abstract boolean hasNext();

    public abstract int next();

    public long count() {
        long result = 0;

        while (hasNext()) {
            next();
            result++;
        }

        return result;
    }

    public void skip(long n) {
        while (n > 0 && hasNext()) {
            next();
            n--;
        }
    }

    public int[] toArray() {
        final IntList list = new IntList();

        while (hasNext()) {
            list.add(next());
        }

        return list.trimToSize().array();
    }
}
