public final class DictionaryBag<K, V> {
    private static final int DEFAULT_BUCKET_COUNT = 16;
    private static final int UNBOUNDED = -1;

 private final Entry<K, V>[] buckets;
 private final int maximumEntries;

 private int entryCount;
 private int distinctKeyCount;

 @SuppressWarnings("unchecked")
 public DictionaryBag() {
     this.buckets = (Entry<K, V>[]) new Entry[DEFAULT_BUCKET_COUNT];
     this.maximumEntries = UNBOUNDED;
 }

 @SuppressWarnings("unchecked")
 public DictionaryBag(int maximumEntries) {
     if (maximumEntries == 0 || maximumEntries < UNBOUNDED) {
         throw new IllegalArgumentException("maximumEntries must be positive or -1");
     }

     int bucketCount = DEFAULT_BUCKET_COUNT;
     if (maximumEntries > bucketCount) {
         bucketCount = 1;
         while (bucketCount < maximumEntries && bucketCount < (1 << 30)) {
             bucketCount <<= 1;
         }
     }

     this.buckets = (Entry<K, V>[]) new Entry[bucketCount];
     this.maximumEntries = maximumEntries;
 }

 public synchronized boolean put(K key, V value) {
     while (isFull()) {
         try {
             wait();
         } catch (InterruptedException exception) {
             Thread.currentThread().interrupt();
             return false;
         }
     }

     addEntry(key, value);
     notifyAll();
     return true;
 }

 public synchronized boolean add(K key, V value) {
     return put(key, value);
 }

 public synchronized boolean offer(K key, V value) {
     if (isFull()) {
         return false;
     }

     addEntry(key, value);
     notifyAll();
     return true;
 }

 public synchronized Item<K, V> take() {
     while (entryCount == 0) {
         try {
             wait();
         } catch (InterruptedException exception) {
             Thread.currentThread().interrupt();
             return null;
         }
     }

     for (int index = 0; index < buckets.length; index++) {
         Entry<K, V> entry = buckets[index];
         if (entry != null) {
             buckets[index] = entry.next;
             entryCount--;

             if (buckets[index] == null) {
                 distinctKeyCount--;
             }

             notifyAll();
             return new Item<K, V>(entry.key, entry.value);
         }
     }

     throw new IllegalStateException("DictionaryBag invariant violated");
 }

 public synchronized boolean remove(K key, V value) {
     int index = bucketIndex(key);
     Entry<K, V> previous = null;
     Entry<K, V> current = buckets[index];

     while (current != null) {
         if (equalsKey(current.key, key) && equalsValue(current.value, value)) {
             if (previous == null) {
                 buckets[index] = current.next;
             } else {
                 previous.next = current.next;
             }

             entryCount--;
             if (buckets[index] == null) {
                 distinctKeyCount--;
             }

             notifyAll();
             return true;
         }

         previous = current;
         current = current.next;
     }

     return false;
 }

 public synchronized int removeAll(K key) {
     int index = bucketIndex(key);
     Entry<K, V> current = buckets[index];
     int removed = 0;

     while (current != null) {
         if (equalsKey(current.key, key)) {
             removed++;
         }
         current = current.next;
     }

     if (removed == 0) {
         return 0;
     }

     Entry<K, V> retainedHead = null;
     Entry<K, V> retainedTail = null;
     current = buckets[index];

     while (current != null) {
         Entry<K, V> next = current.next;
         if (!equalsKey(current.key, key)) {
             current.next = null;
             if (retainedHead == null) {
                 retainedHead = current;
             } else {
                 retainedTail.next = current;
             }
             retainedTail = current;
         }
         current = next;
     }

     buckets[index] = retainedHead;
     entryCount -= removed;
     distinctKeyCount--;
     notifyAll();
     return removed;
 }

 public synchronized boolean contains(K key, V value) {
     Entry<K, V> current = buckets[bucketIndex(key)];

     while (current != null) {
         if (equalsKey(current.key, key) && equalsValue(current.value, value)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized boolean containsKey(K key) {
     Entry<K, V> current = buckets[bucketIndex(key)];

     while (current != null) {
         if (equalsKey(current.key, key)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized int count(K key) {
     int count = 0;
     Entry<K, V> current = buckets[bucketIndex(key)];

     while (current != null) {
         if (equalsKey(current.key, key)) {
             count++;
         }
         current = current.next;
     }

     return count;
 }

 public synchronized Object[] getAll(K key) {
     int count = count(key);
     Object[] values = new Object[count];
     int position = 0;
     Entry<K, V> current = buckets[bucketIndex(key)];

     while (current != null) {
         if (equalsKey(current.key, key)) {
             values[position++] = current.value;
         }
         current = current.next;
     }

     return values;
 }

 public synchronized int size() {
     return entryCount;
 }

 public synchronized int distinctKeys() {
     return distinctKeyCount;
 }

 public synchronized int capacity() {
     return maximumEntries;
 }

 public synchronized boolean isEmpty() {
     return entryCount == 0;
 }

 public synchronized boolean isFull() {
     return maximumEntries != UNBOUNDED && entryCount >= maximumEntries;
 }

 public synchronized void clear() {
     for (int index = 0; index < buckets.length; index++) {
         buckets[index] = null;
     }

     entryCount = 0;
     distinctKeyCount = 0;
     notifyAll();
 }

 public synchronized Object[] snapshot() {
     Object[] values = new Object[entryCount];
     int position = 0;

     for (int index = 0; index < buckets.length; index++) {
         Entry<K, V> current = buckets[index];
         while (current != null) {
             values[position++] = new Item<K, V>(current.key, current.value);
             current = current.next;
         }
     }

     return values;
 }

 private void addEntry(K key, V value) {
     int index = bucketIndex(key);
     if (buckets[index] == null) {
         distinctKeyCount++;
     }

     buckets[index] = new Entry<K, V>(key, value, buckets[index]);
     entryCount++;
 }

 private int bucketIndex(K key) {
     int hash = key == null ? 0 : key.hashCode();
     hash ^= hash >>> 16;
     return (hash & 0x7fffffff) % buckets.length;
 }

 private static boolean equalsKey(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static boolean equalsValue(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static final class Entry<K, V> {
     private final K key;
     private final V value;
     private Entry<K, V> next;

     private Entry(K key, V value, Entry<K, V> next) {
         this.key = key;
         this.value = value;
         this.next = next;
     }
 }

 public static final class Item<K, V> {
     private final K key;
     private final V value;

     public Item(K key, V value) {
         this.key = key;
         this.value = value;
     }

     public K getKey() {
         return key;
     }

     public V getValue() {
         return value;
     }
 }

}
