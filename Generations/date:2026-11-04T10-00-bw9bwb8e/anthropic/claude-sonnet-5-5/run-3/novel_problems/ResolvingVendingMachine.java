import java.lang.*;

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, CANCELLED, FAILED
    }

    public static final class Snapshot {
        private final int[] stock;
        private final long funds;
        private final long[] transactionIds;
        private final Status[] statuses;

        Snapshot(int[] stock, long funds, long[] transactionIds, Status[] statuses) {
            this.stock = stock;
            this.funds = funds;
            this.transactionIds = transactionIds;
            this.statuses = statuses;
        }

        public int[] getStock() {
            return stock.clone();
        }

        public long getFunds() {
            return funds;
        }

        public long[] getTransactionIds() {
            return transactionIds.clone();
        }

        public Status[] getStatuses() {
            return statuses.clone();
        }

        public Status getStatus(long id) {
            for (int i = 0; i < transactionIds.length; i++) {
                if (transactionIds[i] == id) {
                    return statuses[i];
                }
            }
            return null;
        }
    }

    private static final class Record {
        final long id;
        final int[] reserved;
        final long price;
        Status status;
        Record nextInBucket;

        Record(long id, int[] reserved, long price, Status status) {
            this.id = id;
            this.reserved = reserved;
            this.price = price;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] prices;
    private final Dispenser dispenser;

    // guarded by lock
    private final int[] stock;
    private final long[] reservedStock;
    private long funds;
    private long reservedFunds;

    private Record[] buckets = new Record[16];
    private Record[] order = new Record[16];
    private int count;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("null argument");
        }
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("negative price");
            }
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new int[prices.length];
        this.reservedStock = new long[prices.length];
    }

    // must hold lock
    private Record find(long id) {
        int idx = bucketIndex(id, buckets.length);
        Record r = buckets[idx];
        while (r != null) {
            if (r.id == id) {
                return r;
            }
            r = r.nextInBucket;
        }
        return null;
    }

    private static int bucketIndex(long id, int len) {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) ((h & 0x7fffffffffffffffL) % len);
    }

    // must hold lock
    private void register(Record r) {
        if (count >= buckets.length) {
            Record[] nb = new Record[buckets.length * 2];
            for (int i = 0; i < count; i++) {
                Record x = order[i];
                int idx = bucketIndex(x.id, nb.length);
                x.nextInBucket = nb[idx];
                nb[idx] = x;
            }
            buckets = nb;
        }
        if (count >= order.length) {
            Record[] no = new Record[order.length * 2];
            for (int i = 0; i < count; i++) {
                no[i] = order[i];
            }
            order = no;
        }
        int idx = bucketIndex(r.id, buckets.length);
        r.nextInBucket = buckets[idx];
        buckets[idx] = r;
        order[count++] = r;
    }

    // must hold lock; record must be pending
    private void releaseReservation(Record r, boolean refund) {
        for (int i = 0; i < r.reserved.length; i++) {
            int q = r.reserved[i];
            if (q > 0) {
                reservedStock[i] -= q;
                if (refund) {
                    stock[i] += q;
                }
            }
        }
        reservedFunds -= r.price;
        if (refund) {
            funds += r.price;
        }
    }

    public void restock(int product, int quantity) {
        synchronized (lock) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("bad product");
            }
            if (quantity < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            long total = (long) stock[product] + reservedStock[product] + quantity;
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("stock overflow");
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount");
        }
        synchronized (lock) {
            // funds and reservedFunds are nonnegative; check funds + reservedFunds + amount
            long base = funds + reservedFunds; // invariant: fits in long
            if (amount > Long.MAX_VALUE - base) {
                throw new IllegalArgumentException("funds overflow");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transaction id must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("bad bundle");
        }
        int[] bundle = quantities.clone();
        boolean positive = false;
        long total = 0;
        for (int i = 0; i < bundle.length; i++) {
            int q = bundle[i];
            if (q < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            if (q > 0) {
                positive = true;
                if (prices[i] > 0) {
                    if (prices[i] > Long.MAX_VALUE / q) {
                        throw new IllegalArgumentException("price overflow");
                    }
                    long part = prices[i] * q;
                    if (part > Long.MAX_VALUE - total) {
                        throw new IllegalArgumentException("price overflow");
                    }
                    total += part;
                }
            }
        }
        if (!positive) {
            throw new IllegalArgumentException("empty bundle");
        }

        Record rec;
        synchronized (lock) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction id");
            }
            boolean ok = funds >= total;
            if (ok) {
                for (int i = 0; i < bundle.length; i++) {
                    if (stock[i] < bundle[i]) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) {
                register(new Record(transactionId, bundle, total, Status.FAILED));
                return false;
            }
            for (int i = 0; i < bundle.length; i++) {
                stock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            funds -= total;
            reservedFunds += total;
            rec = new Record(transactionId, bundle, total, Status.PENDING);
            register(rec);
        }

        boolean result;
        try {
            result = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                if (rec.status == Status.PENDING) {
                    releaseReservation(rec, true);
                    rec.status = Status.FAILED;
                }
            }
            throw e;
        }

        synchronized (lock) {
            if (rec.status != Status.PENDING) {
                return false;
            }
            if (result) {
                releaseReservation(rec, false);
                rec.status = Status.COMMITTED;
                return true;
            } else {
                releaseReservation(rec, true);
                rec.status = Status.FAILED;
                return false;
            }
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            Record r = find(transactionId);
            if (r == null || r.status != Status.PENDING) {
                return false;
            }
            releaseReservation(r, true);
            r.status = Status.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[count];
            Status[] sts = new Status[count];
            for (int i = 0; i < count; i++) {
                ids[i] = order[i].id;
                sts[i] = order[i].status;
            }
            return new Snapshot(stock.clone(), funds, ids, sts);
        }
    }
}
