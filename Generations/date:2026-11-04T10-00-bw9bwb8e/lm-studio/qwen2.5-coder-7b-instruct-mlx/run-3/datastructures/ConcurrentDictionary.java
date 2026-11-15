@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary {
    @interface ThreadSafe {}

    private final Object[] entries;
    private int size;

    public ConcurrentDictionary(int capacity) {
        this.entries = new Object[capacity];
        this.size = 0;
    }

    public void put(String key, String value) {
        synchronized (this) {
            int hash = Math.abs(key.hashCode()) % entries.length;
            while (entries[hash] != null) {
                if (((Entry) entries[hash]).getKey().equals(key)) {
                    ((Entry) entries[hash]).setValue(value);
                    return;
                }
                hash = (hash + 1) % entries.length;
            }
            entries[hash] = new Entry(key, value);
            size++;
        }
    }

    public String get(String key) {
        synchronized (this) {
            int hash = Math.abs(key.hashCode()) % entries.length;
            while (entries[hash] != null) {
                if (((Entry) entries[hash]).getKey().equals(key)) {
                    return ((Entry) entries[hash]).getValue();
                }
                hash = (hash + 1) % entries.length;
            }
            return null;
        }
    }

    public void remove(String key) {
        synchronized (this) {
            int hash = Math.abs(key.hashCode()) % entries.length;
            while (entries[hash] != null) {
                if (((Entry) entries[hash]).getKey().equals(key)) {
                    entries[hash] = null;
                    size--;
                    return;
                }
                hash = (hash + 1) % entries.length;
            }
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private static class Entry {
        private final String key;
        private String value;

        public Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
