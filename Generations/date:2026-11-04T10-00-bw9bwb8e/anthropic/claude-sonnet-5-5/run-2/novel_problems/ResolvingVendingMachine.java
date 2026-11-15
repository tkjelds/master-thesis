import java.lang.Math;

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, FAILED, CANCELLED
    }

    public static final class Snapshot {
        private final int[] stock;
        private final long funds;
        private final long[] ids;
        private final Status[] statuses;

        Snapshot(int[] stock, long funds, long[] ids, Status[] statuses) {
            this.stock = stock;
            this.funds = funds;
            this.ids = ids;
            this.statuses = statuses;
        }

        public int[] stock() {
            return stock.clone();
        }

        public long funds() {
            return funds;
        }

        public long[] transactionIds() {
            return ids.clone();
        }

        public Status[] statuses() {
            return statuses.clone();
        }

        public Status statusOf(long id) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) {
                    return statuses[i];
                }
            }
            return null;
        }

        public int[] getStock() {
            return stock();
        }

        public long getFunds() {
            return funds;
        }
    }

    private static final class Record {
        final long id;
        final int[] bundle;
        final long price;
        Status status = Status.PENDING;
        Record nextInBucket;

        Record(long id, int[] bundle, long price) {
            this.id = id;
            this.bundle = bundle;
            this.price = price;
        }
    }

    private final Object lock = new Object();
    private final long[] prices;
    private final Dispenser dispenser;

    // guarded by lock
    private final int[] availableStock;
    private final long[] reservedStock;
    private long availableFunds;
    private long reservedFunds;
    private Record[] buckets = new Record[16];
    private Record[] ordered = new Record[16];
    private int count;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new NullPointerException();
        }
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("negative price");
            }
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.availableStock = new int[prices.length];
        this.reservedStock = new long[prices.length];
    }

    public ResolvingVendingMachine(long[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new NullPointerException();
        }
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("negative price");
            }
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.availableStock = new int[prices.length];
        this.reservedStock = new long[prices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("bad product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("bad quantity");
        }
        synchronized (lock) {
            long total = (long) availableStock[product] + reservedStock[product] + quantity;
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("bad amount");
        }
        synchronized (lock) {
            long used = availableFunds + reservedFunds; // invariant: fits
            if (amount > Long.MAX_VALUE - used) {
                throw new IllegalArgumentException("funds overflow");
            }
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transaction id must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("null quantities");
        }
        if (quantities.length != prices.length) {
            throw new IllegalArgumentException("wrong bundle length");
        }
        int[] bundle = quantities.clone();
        boolean positive = false;
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            if (bundle[i] > 0) {
                positive = true;
            }
            price = Math.addExact(price, Math.multiplyExact(prices[i], (long) bundle[i]));
        }
        if (!positive) {
            throw new IllegalArgumentException("empty bundle");
        }

        Record rec;
        synchronized (lock) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction id");
            }
            rec = new Record(transactionId, bundle, price);
            register(rec);
            boolean ok = availableFunds >= price;
            for (int i = 0; ok && i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    ok = false;
                }
            }
            if (!ok) {
                rec.status = Status.FAILED;
                return false;
            }
            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= price;
            reservedFunds += price;
        }

        boolean result;
        try {
            result = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (rec.status == Status.PENDING) {
                    rollback(rec);
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
                for (int i = 0; i < rec.bundle.length; i++) {
                    reservedStock[i] -= rec.bundle[i];
                }
                reservedFunds -= rec.price;
                rec.status = Status.COMMITTED;
                return true;
            } else {
                rollback(rec);
                rec.status = Status.FAILED;
                return false;
            }
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            Record rec = find(transactionId);
            if (rec == null || rec.status != Status.PENDING) {
                return false;
            }
            rollback(rec);
            rec.status = Status.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[count];
            Status[] st = new Status[count];
            for (int i = 0; i < count; i++) {
                ids[i] = ordered[i].id;
                st[i] = ordered[i].status;
            }
            return new Snapshot(availableStock.clone(), availableFunds, ids, st);
        }
    }

    // helpers: must hold lock
    private void rollback(Record rec) {
        for (int i = 0; i < rec.bundle.length; i++) {
            availableStock[i] += rec.bundle[i];
            reservedStock[i] -= rec.bundle[i];
        }
        availableFunds += rec.price;
        reservedFunds -= rec.price;
    }

    private int indexFor(long id, int length) {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) (h & (length - 1));
    }

    private Record find(long id) {
        Record r = buckets[indexFor(id, buckets.length)];
        while (r != null) {
            if (r.id == id) {
                return r;
            }
            r = r.nextInBucket;
        }
        return null;
    }

    private void register(Record rec) {
        if (count >= buckets.length) {
            Record[] nb = new Record[buckets.length * 2];
            for (int i = 0; i < count; i++) {
                Record r = ordered[i];
                int idx = indexFor(r.id, nb.length);
                r.nextInBucket = nb[idx];
                nb[idx] = r;
            }
            buckets = nb;
        }
        if (count >= ordered.length) {
            Record[] no = new Record[ordered.length * 2];
            for (int i = 0; i < count; i++) {
                no[i] = ordered[i];
            }
            ordered = no;
        }
        int idx = indexFor(rec.id, buckets.length);
        rec.nextInBucket = buckets[idx];
        buckets[idx] = rec;
        ordered[count++] = rec;
    }
}
