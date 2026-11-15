@DictionaryBag.ThreadSafe
public class DictionaryBag {
    @interface ThreadSafe {}

    private static class Entry {
        final String key;
        int count;

        Entry(String k, int c) {
            this.key = k;
            this.count = c;
        }
    }

    private Entry[] entries;
    private int size;
    private final Object lock = new Object();
    private volatile boolean isFull;

    public DictionaryBag(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.entries = new Entry[capacity];
    }

    public void put(String key, int count) {
        synchronized (lock) {
            while (isFull) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            Entry[] newEntries = entries.clone();
            int index = findIndex(key, newEntries);
            if (index == -1) {
                if (size >= entries.length) {
                    isFull = true;
                    return;
                }
                newEntries[size++] = new Entry(key, count);
            } else {
                newEntries[index].count += count;
            }

            entries = newEntries;
        }
    }

    public int get(String key) {
        synchronized (lock) {
            Entry[] currentEntries = entries.clone();
            int index = findIndex(key, currentEntries);
            return (index != -1) ? currentEntries[index].count : 0;
        }
    }

    public int remove(String key) {
        synchronized (lock) {
            Entry[] currentEntries = entries.clone();
            int index = findIndex(key, currentEntries);
            if (index == -1) return 0;

            int count = currentEntries[index].count;
            if (size == entries.length) {
                isFull = false;
                lock.notifyAll();
            }

            Entry[] newEntries = new Entry[entries.length];
            int newSize = 0;
            for (int i = 0; i < size; i++) {
                if (i != index) {
                    newEntries[newSize++] = currentEntries[i];
                }
            }

            entries = newEntries;
            size = newSize;
            return count;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    private int findIndex(String key, Entry[] entries) {
        for (int i = 0; i < size; i++) {
            if (entries[i].key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public void clear() {
        synchronized (lock) {
            entries = new Entry[entries.length];
            size = 0;
            isFull = false;
            lock.notifyAll();
        }
    }

    public boolean contains(String key) {
        synchronized (lock) {
            Entry[] currentEntries = entries.clone();
            return findIndex(key, currentEntries) != -1;
        }
    }

    public String[] keys() {
        synchronized (lock) {
            Entry[] currentEntries = entries.clone();
            String[] keys = new String[size];
            for (int i = 0; i < size; i++) {
                keys[i] = currentEntries[i].key;
            }
            return keys;
        }
    }

    public int totalCount() {
        synchronized (lock) {
            Entry[] currentEntries = entries.clone();
            int total = 0;
            for (int i = 0; i < size; i++) {
                total += currentEntries[i].count;
            }
            return total;
        }
    }

    public void waitForSpace() throws InterruptedException {
        synchronized (lock) {
            while (!isFull && size < entries.length) {
                lock.wait();
            }
        }
    }

    public void waitForNonEmpty() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
        }
    }

    public void notifySpaceAvailable() {
        synchronized (lock) {
            isFull = false;
            lock.notifyAll();
        }
    }

    public void notifyNonEmpty() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
