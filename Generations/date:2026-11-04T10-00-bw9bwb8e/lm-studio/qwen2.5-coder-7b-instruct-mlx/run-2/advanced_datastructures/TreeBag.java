import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeBag<?> parent;
    private final int size, distinctSize;

    Snapshot(TreeBag<?> parent) {
        this.parent = parent;
        this.size = parent.totalCount;
        this.distinctSize = parent.counts.length;
    }

    int size() {
        return size;
    }

    int distinctSize() {
        return distinctSize;
    }

    int count(T item) {
        return parent.count(item);
    }

    T get(int index) {
        synchronized (parent.monitor) {
            return parent.get(index);
        }
    }
}

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Lock monitor = new ReentrantLock();
    private final Condition condition = monitor.newCondition();
    private int totalCount;
    private Object[] counts;
    private Object[] elements;

    TreeBag(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
        this.counts = new Object[16];
        this.elements = new Object[16];
    }

    void add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        monitor.lock();
        try {
            int index = find(item);
            if (index == -1) {
                insert(index, item);
            } else {
                counts[index]++;
            }
        } finally {
            monitor.unlock();
        }
    }

    boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        monitor.lock();
        try {
            int index = find(item);
            if (index == -1) return false;
            counts[index]--;
            if (counts[index] == 0) {
                delete(index, item);
            }
            return true;
        } finally {
            monitor.unlock();
        }
    }

    int count(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        monitor.lock();
        try {
            int index = find(item);
            return index == -1 ? 0 : (int) counts[index];
        } finally {
            monitor.unlock();
        }
    }

    int size() {
        return totalCount;
    }

    int distinctSize() {
        monitor.lock();
        try {
            return counts.length > 0 ? counts[0].getClass().equals(Integer.class) ? (int) counts[0] : 1 : 0;
        } finally {
            monitor.unlock();
        }
    }

    T get(int index) {
        if (index < 0 || index >= totalCount) throw new IndexOutOfBoundsException();
        monitor.lock();
        try {
            int i = 0;
            for (int j = 0; j < counts.length; j++) {
                int count = (int) counts[j];
                if (count > 0) {
                    i += count;
                    if (i > index + 1) return (T) elements[j];
                }
            }
        } finally {
            monitor.unlock();
        }
        throw new IndexOutOfBoundsException();
    }

    int rank(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        monitor.lock();
        try {
            int rank = 0;
            for (int i = 1; i < counts.length; i++) {
                int count = (int) counts[i];
                if ((order.compare((T) elements[i], item) < 0)) {
                    rank += count;
                } else break;
            }
            return rank;
        } finally {
            monitor.unlock();
        }
    }

    Snapshot<T> snapshot() {
        return new Snapshot<>(this);
    }

    private int find(T item) {
        for (int i = 1; i < counts.length; i++) {
            if ((order.compare((T) elements[i], item) == 0)) return i;
        }
        return -1;
    }

    private void insert(int index, T item) {
        if (totalCount >= Integer.MAX_VALUE - 1) throw new IllegalStateException("Too many elements");
        totalCount++;
        counts[index] = 1;
        elements[index] = item;
    }

    private void delete(int index, T item) {
        totalCount--;
        counts[index] = 0;
    }
}
