@ConcurrentMap.ThreadSafe
public class ConcurrentMap {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Entry[] buckets;
    private volatile int size;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        this.buckets = new Entry[initialCapacity];
    }

    public void put(String key, String value) {
        int hash = hash(key);
        Entry[] bucketArray = buckets;
        synchronized (this) {
            int index = hash & (bucketArray.length - 1);
            Entry entry = bucketArray[index];
            while (entry != null) {
                if (key.equals(entry.key)) {
                    entry.value = value;
                    return;
                }
                entry = entry.next;
            }

            Entry newEntry = new Entry(key, value);
            newEntry.next = bucketArray[index];
            bucketArray[index] = newEntry;
        }
    }

    public String get(String key) {
        int hash = hash(key);
        Entry[] bucketArray;
        synchronized (this) {
            bucketArray = buckets;
        }

        int index = hash & (bucketArray.length - 1);
        Entry entry = bucketArray[index];
        while (entry != null) {
            if (key.equals(entry.key)) {
                return entry.value;
            }
            entry = entry.next;
        }

        return null;
    }

    public String remove(String key) {
        int hash = hash(key);
        Entry[] bucketArray;
        synchronized (this) {
            bucketArray = buckets;
        }

        int index = hash & (bucketArray.length - 1);
        Entry prev = null;
        Entry entry = bucketArray[index];
        while (entry != null) {
            if (key.equals(entry.key)) {
                synchronized (this) {
                    if (prev == null) {
                        bucketArray[index] = entry.next;
                    } else {
                        prev.next = entry.next;
                    }
                }
                return entry.value;
            }
            prev = entry;
            entry = entry.next;
        }

        return null;
    }

    public boolean containsKey(String key) {
        int hash = hash(key);
        Entry[] bucketArray;
        synchronized (this) {
            bucketArray = buckets;
        }

        int index = hash & (bucketArray.length - 1);
        Entry entry = bucketArray[index];
        while (entry != null) {
            if (key.equals(entry.key)) {
                return true;
            }
            entry = entry.next;
        }

        return false;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private static int hash(String key) {
        if (key == null) {
            return 0;
        }
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    private static class Entry {
        final String key;
        String value;
        Entry next;

        Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
