@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private final Object[] entries;
    private final int size;
    private final int maxSize;
    private final Object sentinel = new Object();

    @SuppressWarnings("unchecked")
    public ConcurrentSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.entries = (Object[]) new Object[capacity];
        this.maxSize = capacity;
        this.size = 0;
    }

    public synchronized boolean add(E element) {
        if (element == null) {
            throw new NullPointerException("Element must not be null");
        }
        if (size >= maxSize) {
            return false;
        }
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null || !entries[i].equals(element)) {
                entries[i] = element;
                size++;
                return true;
            }
        }
        return false;
    }

    public synchronized E get(E target) {
        if (target == null) {
            return null;
        }
        for (Object e : entries) {
            if (e == target || ((e instanceof Object et) && et.equals(target))) {
                return (E) e;
            }
        }
        return null;
    }

    public synchronized boolean contains(E target) {
        if (target == null) {
            return false;
        }
        for (Object e : entries) {
            if (e == target || ((e instanceof Object et) && et.equals(target))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public synchronized void remove(E element) {
        if (element == null) {
            throw new NullPointerException("Element must not be null");
        }
        int indexToRemove = -1;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && (entries[i] == element || entries[i].equals(element))) {
                indexToRemove = i;
                break;
            }
        }
        if (indexToRemove != -1) {
            entries[indexToRemove] = null;
            size--;
        }
        if (size == 0 && entries.length > maxCap) {
                expand();
        }
    }

    private synchronized void expand() {
        Object[] newEntries = new Object[entries.length * 2];
        int newCount = 0;
        for (Object e : entries) {
            if (e != null) {
                newEntries[newCount++] = e;
            }
        }
        entries = newEntries;
        entries[0] = null;
        maxCap = entries.length;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    public synchronized E removeFirst() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        E removed = (E) entries[0];
        entries[0] = null;
        size--;
        notifyAll();
        return removed;
    }

    @SuppressWarnings("unchecked")
    public synchronized void removeLast() throws InterruptedException {
        int index = entries.length - 1;
        while (index >= 0 && entries[index] == null) {
            index--;
        }
        if (index < 0) {
            throw new IllegalStateException("Set is empty");
        }
        Object removed = entries[index];
        entries[index] = null;
        size--;
        notifyAll();
        return removed;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    @SuppressWarnings("unchecked")
    public synchronized void clear() throws InterruptedException {
        while (size > 0) {
            entries[0] = null;
            size--;
            notifyAll();
        }
    }

    @Override
    public synchronized String toString() {
        return "ConcurrentSet{size=" + size + ", entries=" + entries.length + "}";
    }

    private static final int maxCap = 16;

    private static class Object {
        public Object(Object o) {
        }
    }
}
