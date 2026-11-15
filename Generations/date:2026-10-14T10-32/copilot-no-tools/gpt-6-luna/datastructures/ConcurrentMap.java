/**

 - A hash map whose public operations are thread-safe.
 -
 - Null keys and values are not permitted. Operations do not wait for capacity:
 - the map grows as needed, subject to available memory. Keys must keep the same
 - hash code and equality behavior while stored in the map.
  */
 public final class ConcurrentMap<K, V> {
  private static final int DEFAULT_CAPACITY = 16;
  private static final int MAXIMUM_CAPACITY = 1 << 30;
  private final Object lock = new Object();
  private Object[] table;
  private int size;
  private int threshold;
  private static final class Node<K, V> {
  final int hash;
  final K key;
  V value;
  Node<K, V> next;
  Node(int hash, K key, V value, Node<K, V> next) {
      this.hash = hash;
      this.key = key;
      this.value = value;
      this.next = next;
  }
  }
  public ConcurrentMap() {
  this(DEFAULT_CAPACITY);
  }
  public ConcurrentMap(int initialCapacity) {
  if (initialCapacity < 1 || initialCapacity > MAXIMUM_CAPACITY) {
      throw new IllegalArgumentException("initialCapacity must be between 1 and " +
MAXIMUM_CAPACITY);
  }
  int capacity = 1;
  while (capacity < initialCapacity) {
      capacity <<= 1;
  }
  table = new Object[capacity];
  threshold = capacity - (capacity >>> 2);
  }
  /**
  - Adds or replaces a mapping and returns its previous value, or null if absent.
    */
   public V put(K key, V value) {
   requireNonNull(key, "key");
   requireNonNull(value, "value");
   int hash = spread(key.hashCode());
   synchronized (lock) {
   int index = hash & (table.length - 1);
   Node<K, V> node = bucketAt(index);
   while (node != null) {
       if (node.hash == hash && key.equals(node.key)) {
           V previous = node.value;
           node.value = value;
           return previous;
       }
       node = node.next;
   }
   if (size >= threshold && table.length < MAXIMUM_CAPACITY) {
       resize();
       index = hash & (table.length - 1);
   }
   table[index] = new Node<K, V>(hash, key, value, bucketAt(index));
   size++;
   return null;
   }
  }
  /**
  - Returns the value associated with the key, or null if the key is absent.
    */
   public V get(K key) {
   requireNonNull(key, "key");
   int hash = spread(key.hashCode());
   synchronized (lock) {
   Node<K, V> node = bucketAt(hash & (table.length - 1));
   while (node != null) {
       if (node.hash == hash && key.equals(node.key)) {
           return node.value;
       }
       node = node.next;
   }
   return null;
   }
  }
  public boolean containsKey(K key) {
      requireNonNull(key, "key");
      int hash = spread(key.hashCode());
   synchronized (lock) {
       Node<K, V> node = bucketAt(hash & (table.length - 1));
       while (node != null) {
           if (node.hash == hash && key.equals(node.key)) {
               return true;
           }
           node = node.next;
       }
       return false;
   }
  }
  /**
  - Removes the mapping and returns its value, or null if the key is absent.
    */
   public V remove(K key) {
   requireNonNull(key, "key");
   int hash = spread(key.hashCode());
   synchronized (lock) {
   int index = hash & (table.length - 1);
   Node<K, V> previous = null;
   Node<K, V> node = bucketAt(index);
   while (node != null) {
       if (node.hash == hash && key.equals(node.key)) {
           if (previous == null) {
               table[index] = node.next;
           } else {
               previous.next = node.next;
           }
           size--;
           return node.value;
       }
       previous = node;
       node = node.next;
   }
   return null;
   }
  }
  public int size() {
      synchronized (lock) {
          return size;
      }
  }
  public boolean isEmpty() {
      synchronized (lock) {
          return size == 0;
      }
  }
  public void clear() {
      synchronized (lock) {
          table = new Object[table.length];
          size = 0;
          threshold = table.length - (table.length >>> 2);
      }
  }
  private void resize() {
      int oldCapacity = table.length;
      int newCapacity = oldCapacity << 1;
      Object[] newTable = new Object[newCapacity];
   for (int i = 0; i < oldCapacity; i++) {
       Node<K, V> node = bucketAt(i);
       while (node != null) {
           Node<K, V> next = node.next;
           int index = node.hash & (newCapacity - 1);
           node.next = bucketAt(newTable, index);
           newTable[index] = node;
           node = next;
       }
   }

   table = newTable;
   threshold = newCapacity - (newCapacity >>> 2);
  }
  @SuppressWarnings("unchecked")
  private Node<K, V> bucketAt(int index) {
      return (Node<K, V>) table[index];
  }
  @SuppressWarnings("unchecked")
  private Node<K, V> bucketAt(Object[] source, int index) {
      return (Node<K, V>) source[index];
  }
  private static int spread(int hash) {
      return hash ^ (hash >>> 16);
  }
  private static void requireNonNull(Object value, String name) {
      if (value == null) {
          throw new NullPointerException(name + " must not be null");
      }
  }

}
