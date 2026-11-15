```java
public class InventoryTransactionBuffer {
    private final long[] committedQuantities;
    private final Object stagingLock = new Object();
    private Transaction stagingTransaction;

    public InventoryTransactionBuffer(long[] committedQuantities) {
        if (committedQuantities == null || committedQuantities.length == 0) {
            throw new IllegalArgumentException("Committed quantities array must be nonempty");
        }
        this.committedQuantities = committedQuantities.clone();
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Owner ID, delta, and tags must not be null");
        }
        if (delta.length != committedQuantities.length || tags.length == 0) {
            throw new IllegalArgumentException("Delta and tags must have the same length");
        }
        for (long d : delta) {
            if (d < 0 || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException("Delta must contain only nonnegative values within Long.MAX_VALUE");
            }
        }

        long generation;
        synchronized (stagingLock) {
            if (stagingTransaction != null && !stagingTransaction.ownerId.equals(ownerId)) {
                throw new IllegalStateException("Cannot stage a different owner's transaction");
            }
            generation = stagingTransaction == null ? 1 : stagingTransaction.generation + 1;
            stagingTransaction = new Transaction(ownerId, delta.clone(), tags.clone(), generation);
        }

        return generation;
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || required == null || forbidden == null) {
            throw new IllegalArgumentException("Owner ID, generation, required, and forbidden must not be null");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
        if (required.length == 0 && forbidden.length == 0) {
            return false; // No change needed
        }

        Transaction transaction;
        synchronized (stagingLock) {
            if (stagingTransaction == null || !stagingTransaction.ownerId.equals(ownerId) || stagingTransaction.generation != generation) {
                return false;
            }
            transaction = stagingTransaction;
            stagingTransaction = null;
        }

        for (String tag : required) {
            if (!ArrayUtils.contains(transaction.tags, tag)) {
                return false;
            }
        }

        for (String tag : forbidden) {
            if (ArrayUtils.contains(transaction.tags, tag)) {
                return false;
            }
        }

        for (int i = 0; i < committedQuantities.length; i++) {
            long newQuantity = committedQuantities[i] + transaction.delta[i];
            if (newQuantity < 0 || newQuantity > Long.MAX_VALUE) {
                throw new IllegalStateException("Projected quantity must be within valid range");
            }
            committedQuantities[i] = newQuantity;
        }

        return true;
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0) {
            throw new IllegalArgumentException("Owner ID must not be null and generation must be positive");
        }

        synchronized (stagingLock) {
            if (stagingTransaction == null || !stagingTransaction.ownerId.equals(ownerId) || stagingTransaction.generation != generation) {
                return false;
            }
            stagingTransaction = null;
        }

        return true;
    }

    public Snapshot snapshot(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }

        synchronized (stagingLock) {
            return new Snapshot(committedQuantities.clone(), stagingTransaction == null ? null : new Transaction(stagingTransaction.ownerId, stagingTransaction.delta.clone(), stagingTransaction.tags.clone(), stagingTransaction.generation));
        }
    }

    private static class Transaction {
        final String ownerId;
        final long[] delta;
        final String[] tags;
        final long generation;

        Transaction(String ownerId, long[] delta, String[] tags, long generation) {
            this.ownerId = ownerId;
            this.delta = delta;
            this.tags = tags;
            this.generation = generation;
        }
    }

    private static class Snapshot {
        final long[] committedQuantities;
        final Transaction stagingTransaction;

        Snapshot(long[] committedQuantities, Transaction stagingTransaction) {
            this.committedQuantities = committedQuantities;
            this.stagingTransaction = stagingTransaction;
        }
    }

    private static class ArrayUtils {
        public static boolean contains(Object[] array, Object object) {
            for (Object obj : array) {
                if (obj.equals(object)) {
                    return true;
                }
            }
            return false;
        }

        public static boolean contains(String[] array, String string) {
            for (String str : array) {
                if (str.equals(string)) {
                    return true;
                }
            }
            return false;
        }
    }
}
```
