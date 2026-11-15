public final class DictionaryBag<K, V> {
    private static final int DEFAULT_BUCKET_COUNT = 16;
    private static final int UNBOUNDED = -1;

 private final Object monitor = new Object();
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

     this.buckets = (Entry<K, V>[]) new Entry[DEFAULT_BUCKET_COUNT];
     this.maximumEntries = maximumEntries;
 }

 public boolean put(K key, V value) {
     synchronized (monitor) {
         while (isFullLocked()) {
             try {
                 monitor.wait();
             } catch (InterruptedException exception) {
                 Thread.currentThread().interrupt();
                 return false;
             }
         }

         addEntryLocked(key, value);
         monitor.notifyAll();
         return true;
     }
 }

 public boolean add(K key, V value) {
     return put(key, value);
 }

 public boolean offer(K key, V value) {
     synchronized (monitor) {
         if (isFullLocked()) {
             return false;
         }

         addEntryLocked(key, value);
         monitor.notifyAll();
         return true;
     }
 }

 public Item<K, V> take() {
     synchronized (monitor) {
         while (entryCount == 0) {
             try {
                 monitor.wait();
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

                 if (!containsKeyLocked(index, entry.key)) {
                     distinctKeyCount--;
                 }

                 monitor.notifyAll();
                 return new Item<K, V>(entry.key, entry.value);
             }
         }

         throw new IllegalStateException("DictionaryBag invariant violated");
     }
 }

 public boolean remove(K key, V value) {
     synchronized (monitor) {
         int index = bucketIndex(key);
         Entry<K, V> previous = null;
         Entry<K, V> current = buckets[index];

         while (current != null) {
             if (equal(current.key, key) && equal(current.value, value)) {
                 if (previous == null) {
                     buckets[index] = current.next;
                 } else {
                     previous.next = current.next;
                 }

                 entryCount--;
                 if (!containsKeyLocked(index, key)) {
                     distinctKeyCount--;
                 }

                 monitor.notifyAll();
                 return true;
             }

             previous = current;
             current = current.next;
         }

         return false;
     }
 }

 public int removeAll(K key) {
     synchronized (monitor) {
         int index = bucketIndex(key);
         Entry<K, V> previous = null;
         Entry<K, V> current = buckets[index];
         int removed = 0;

         while (current != null) {
             Entry<K, V> next = current.next;
             if (equal(current.key, key)) {
                 if (previous == null) {
                     buckets[index] = next;
                 } else {
                     previous.next = next;
                 }
                 removed++;
             } else {
                 previous = current;
             }
             current = next;
         }

         if (removed != 0) {
             entryCount -= removed;
             distinctKeyCount--;
             monitor.notifyAll();
         }

         return removed;
     }
 }

 public boolean contains(K key, V value) {
     synchronized (monitor) {
         Entry<K, V> current = buckets[bucketIndex(key)];
         while (current != null) {
             if (equal(current.key, key) && equal(current.value, value)) {
                 return true;
             }
             current = current.next;
         }
         return false;
     }
 }

 public boolean containsKey(K key) {
     synchronized (monitor) {
         return containsKeyLocked(bucketIndex(key), key);
     }
 }

 public int count(K key) {
     synchronized (monitor) {
         int count = 0;
         Entry<K, V> current = buckets[bucketIndex(key)];

         while (current != null) {
             if (equal(current.key, key)) {
                 count++;
             }
             current = current.next;
         }

         return count;
     }
 }

 public Object[] getAll(K key) {
     synchronized (monitor) {
         int index = bucketIndex(key);
         int count = 0;
         Entry<K, V> current = buckets[index];

         while (current != null) {
             if (equal(current.key, key)) {
                 count++;
             }
             current = current.next;
         }

         Object[] values = new Object[count];
         int position = 0;
         current = buckets[index];

         while (current != null) {
             if (equal(current.key, key)) {
                 values[position++] = current.value;
             }
             current = current.next;
         }

         return values;
     }
 }

 public int size() {
     synchronized (monitor) {
         return entryCount;
     }
 }

 public int distinctKeys() {
     synchronized (monitor) {
         return distinctKeyCount;
     }
 }

 public int capacity() {
     return maximumEntries;
 }

 public boolean isEmpty() {
     synchronized (monitor) {
         return entryCount == 0;
     }
 }

 public boolean isFull() {
     synchronized (monitor) {
         return isFullLocked();
     }
 }

 public void clear() {
     synchronized (monitor) {
         for (int index = 0; index < buckets.length; index++) {
             buckets[index] = null;
         }

         entryCount = 0;
         distinctKeyCount = 0;
         monitor.notifyAll();
     }
 }

 public Object[] snapshot() {
     synchronized (monitor) {
         Object[] items = new Object[entryCount];
         int position = 0;

         for (int index = 0; index < buckets.length; index++) {
             Entry<K, V> current = buckets[index];
             while (current != null) {
                 items[position++] = new Item<K, V>(current.key, current.value);
                 current = current.next;
             }
         }

         return items;
     }
 }

 private void addEntryLocked(K key, V value) {
     int index = bucketIndex(key);
     if (!containsKeyLocked(index, key)) {
         distinctKeyCount++;
     }

     buckets[index] = new Entry<K, V>(key, value, buckets[index]);
     entryCount++;
 }

 private boolean containsKeyLocked(int index, K key) {
     Entry<K, V> current = buckets[index];
     while (current != null) {
         if (equal(current.key, key)) {
             return true;
         }
         current = current.next;
     }
     return false;
 }

 private boolean isFullLocked() {
     return maximumEntries != UNBOUNDED && entryCount >= maximumEntries;
 }

 private int bucketIndex(K key) {
     int hash = key == null ? 0 : key.hashCode();
     hash ^= hash >>> 16;
     return (hash & 0x7fffffff) % buckets.length;
 }

 private static boolean equal(Object first, Object second) {
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
