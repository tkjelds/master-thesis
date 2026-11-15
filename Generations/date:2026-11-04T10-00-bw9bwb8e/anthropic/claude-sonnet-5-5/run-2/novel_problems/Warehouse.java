import java.util.*;

@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}


    public enum Status {
        RESERVED, REJECTED, SHIPPED, CANCELLED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] bundle;
        private final Status status;

        OrderRecord(long orderId, long[] bundle, Status status) {
            this.orderId = orderId;
            this.bundle = bundle.clone();
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getBundle() {
            return bundle.clone();
        }

        public Status getStatus() {
            return status;
        }
    }

    public static final class Snapshot {
        private final long[] available;
        private final long[] reserved;
        private final OrderRecord[] orders;

        Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }

        public long[] getAvailable() {
            return available.clone();
        }

        public long[] getReserved() {
            return reserved.clone();
        }

        public OrderRecord[] getOrders() {
            return orders.clone();
        }
    }

    private static final class Entry {
        final long id;
        final long[] bundle;
        Status status;

        Entry(long id, long[] bundle, Status status) {
            this.id = id;
            this.bundle = bundle;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Entry[] entries = new Entry[16];
    private int count = 0;
    private int[] table = new int[64]; // stores index+1, 0 = empty

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("stock must be nonempty");
        }
        long[] copy = initialStock.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("negative stock");
            }
        }
        this.available = copy;
        this.reserved = new long[copy.length];
    }

    private static int hash(long id) {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) h;
    }

    // must hold lock
    private Entry find(long id) {
        int mask = table.length - 1;
        int pos = hash(id) & mask;
        while (table[pos] != 0) {
            Entry e = entries[table[pos] - 1];
            if (e.id == id) {
                return e;
            }
            pos = (pos + 1) & mask;
        }
        return null;
    }

    // must hold lock
    private void insert(Entry e) {
        if (count == entries.length) {
            Entry[] ne = new Entry[entries.length * 2];
            for (int i = 0; i < count; i++) {
                ne[i] = entries[i];
            }
            entries = ne;
        }
        if ((count + 1) * 2 > table.length) {
            int[] nt = new int[table.length * 2];
            int mask = nt.length - 1;
            for (int i = 0; i < count; i++) {
                int pos = hash(entries[i].id) & mask;
                while (nt[pos] != 0) {
                    pos = (pos + 1) & mask;
                }
                nt[pos] = i + 1;
            }
            table = nt;
        }
        entries[count] = e;
        count++;
        int mask = table.length - 1;
        int pos = hash(e.id) & mask;
        while (table[pos] != 0) {
            pos = (pos + 1) & mask;
        }
        table[pos] = count;
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("null quantities");
        }
        long[] q = quantities.clone();
        if (q.length != available.length) {
            throw new IllegalArgumentException("bundle length mismatch");
        }
        boolean anyPositive = false;
        for (int i = 0; i < q.length; i++) {
            if (q[i] < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            if (q[i] > 0) {
                anyPositive = true;
            }
        }
        if (!anyPositive) {
            throw new IllegalArgumentException("bundle has no positive quantity");
        }
        synchronized (lock) {
            if (find(orderId) != null) {
                throw new IllegalArgumentException("duplicate order id");
            }
            boolean ok = true;
            for (int i = 0; i < q.length; i++) {
                if (available[i] < q[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                for (int i = 0; i < q.length; i++) {
                    available[i] -= q[i];
                    reserved[i] += q[i];
                }
                insert(new Entry(orderId, q, Status.RESERVED));
                return true;
            } else {
                insert(new Entry(orderId, q, Status.REJECTED));
                return false;
            }
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        synchronized (lock) {
            Entry e = find(orderId);
            if (e == null || e.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < e.bundle.length; i++) {
                reserved[i] -= e.bundle[i];
            }
            e.status = Status.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        synchronized (lock) {
            Entry e = find(orderId);
            if (e == null || e.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < e.bundle.length; i++) {
                reserved[i] -= e.bundle[i];
                available[i] += e.bundle[i];
            }
            e.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length) {
            throw new IllegalArgumentException("invalid product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        synchronized (lock) {
            long total = available[product] + reserved[product];
            if (Long.MAX_VALUE - total < quantity) {
                throw new IllegalArgumentException("stock overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord[] recs = new OrderRecord[count];
            for (int i = 0; i < count; i++) {
                Entry e = entries[i];
                recs[i] = new OrderRecord(e.id, e.bundle, e.status);
            }
            return new Snapshot(available.clone(), reserved.clone(), recs);
        }
    }
}
