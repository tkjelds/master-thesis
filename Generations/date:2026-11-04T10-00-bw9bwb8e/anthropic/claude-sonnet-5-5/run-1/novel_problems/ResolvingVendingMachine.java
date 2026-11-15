import java.lang.*;

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, CANCELLED, ROLLED_BACK, REJECTED
    }

    public static final class Snapshot {
        private final long[] stock;
        private final long funds;
        private final long[] ids;
        private final Status[] statuses;

        private Snapshot(long[] stock, long funds, long[] ids, Status[] statuses) {
            this.stock = stock;
            this.funds = funds;
            this.ids = ids;
            this.statuses = statuses;
        }

        public long[] stock() {
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
    }

    private static final class Record {
        final long id;
        final int[] bundle;
        final long cost;
        Status status;
        Record chainNext;
        Record orderNext;

        Record(long id, int[] bundle, long cost, Status status) {
            this.id = id;
            this.bundle = bundle;
            this.cost = cost;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] prices;
    private final Dispenser dispenser;

    // guarded by lock
    private final long[] availableStock;
    private final long[] reservedStock;
    private long availableFunds;
    private long reservedFunds;
    private Record[] table = new Record[16];
    private int count;
    private Record head;
    private Record tail;

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
        this.availableStock = new long[prices.length];
        this.reservedStock = new long[prices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("bad product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("negative quantity");
        }
        synchronized (lock) {
            long total = availableStock[product] + reservedStock[product]; // never overflows by invariant
            if (total > Long.MAX_VALUE - quantity) {
                throw new IllegalArgumentException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount");
        }
        synchronized (lock) {
            long total = availableFunds + reservedFunds;
            if (total > Long.MAX_VALUE - amount) {
                throw new IllegalArgumentException("funds overflow");
            }
            availableFunds += amount;
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
        long cost = 0;
        for (int i = 0; i < bundle.length; i++) {
            int q = bundle[i];
            if (q < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            if (q > 0) {
                positive = true;
                long p = prices[i];
                if (p > 0) {
                    if (q > Long.MAX_VALUE / p) {
                        throw new IllegalArgumentException("cost overflow");
                    }
                    long line = p * q;
                    if (cost > Long.MAX_VALUE - line) {
                        throw new IllegalArgumentException("cost overflow");
                    }
                    cost += line;
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
            boolean ok = availableFunds >= cost;
            if (ok) {
                for (int i = 0; i < bundle.length; i++) {
                    if (availableStock[i] < bundle[i]) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) {
                register(new Record(transactionId, bundle, cost, Status.REJECTED));
                return false;
            }
            rec = new Record(transactionId, bundle, cost, Status.PENDING);
            register(rec);
            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= cost;
            reservedFunds += cost;
        }

        boolean result;
        try {
            result = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (rec.status == Status.PENDING) {
                    rollback(rec, Status.ROLLED_BACK);
                }
            }
            throw e;
        } catch (Error e) {
            synchronized (lock) {
                if (rec.status == Status.PENDING) {
                    rollback(rec, Status.ROLLED_BACK);
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
                reservedFunds -= rec.cost;
                rec.status = Status.COMMITTED;
                return true;
            } else {
                rollback(rec, Status.ROLLED_BACK);
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
            rollback(rec, Status.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[count];
            Status[] st = new Status[count];
            int i = 0;
            for (Record r = head; r != null; r = r.orderNext) {
                ids[i] = r.id;
                st[i] = r.status;
                i++;
            }
            return new Snapshot(availableStock.clone(), availableFunds, ids, st);
        }
    }

    // ---- helpers; must be called with lock held ----

    private void rollback(Record rec, Status newStatus) {
        for (int i = 0; i < rec.bundle.length; i++) {
            reservedStock[i] -= rec.bundle[i];
            availableStock[i] += rec.bundle[i];
        }
        reservedFunds -= rec.cost;
        availableFunds += rec.cost;
        rec.status = newStatus;
    }

    private static int hash(long id, int length) {
        int h = (int) (id ^ (id >>> 32));
        h ^= (h >>> 16);
        return h & (length - 1);
    }

    private Record find(long id) {
        for (Record r = table[hash(id, table.length)]; r != null; r = r.chainNext) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private void register(Record rec) {
        if (count + 1 > table.length / 4 * 3) {
            Record[] nt = new Record[table.length * 2];
            for (int i = 0; i < table.length; i++) {
                Record r = table[i];
                while (r != null) {
                    Record next = r.chainNext;
                    int idx = hash(r.id, nt.length);
                    r.chainNext = nt[idx];
                    nt[idx] = r;
                    r = next;
                }
            }
            table = nt;
        }
        int idx = hash(rec.id, table.length);
        rec.chainNext = table[idx];
        table[idx] = rec;
        if (tail == null) {
            head = rec;
        } else {
            tail.orderNext = rec;
        }
        tail = rec;
        count++;
    }
}
