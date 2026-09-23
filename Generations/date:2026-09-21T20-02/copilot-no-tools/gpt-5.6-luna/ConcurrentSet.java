public class ConcurrentSet<E> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

 private Node<E>[] buckets;
 private int size;
 private int threshold;

 public ConcurrentSet() {
     this(DEFAULT_CAPACITY);
 }

 @SuppressWarnings("unchecked")
 public ConcurrentSet(int initialCapacity) {
     if (initialCapacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }

     int capacity = 1;
     while (capacity < initialCapacity) {
         capacity <<= 1;
         if (capacity <= 0) {
             throw new IllegalArgumentException("Capacity is too large");
         }
     }

     buckets = (Node<E>[]) new Node<?>[capacity];
     threshold = (int) (capacity * LOAD_FACTOR);
     if (threshold == 0) {
         threshold = 1;
     }
 }

 public synchronized boolean add(E value) {
     int index = bucketIndex(value, buckets.length);
     Node<E> current = buckets[index];

     while (current != null) {
         if (valuesEqual(current.value, value)) {
             return false;
         }
         current = current.next;
     }

     buckets[index] = new Node<E>(value, buckets[index]);
     size++;

     if (size > threshold) {
         resize();
     }

     return true;
 }

 public synchronized boolean remove(E value) {
     int index = bucketIndex(value, buckets.length);
     Node<E> current = buckets[index];
     Node<E> previous = null;

     while (current != null) {
         if (valuesEqual(current.value, value)) {
             if (previous == null) {
                 buckets[index] = current.next;
             } else {
                 previous.next = current.next;
             }

             size--;
             return true;
         }

         previous = current;
         current = current.next;
     }

     return false;
 }

 public synchronized boolean contains(E value) {
     int index = bucketIndex(value, buckets.length);
     Node<E> current = buckets[index];

     while (current != null) {
         if (valuesEqual(current.value, value)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 @SuppressWarnings("unchecked")
 public synchronized void clear() {
     buckets = (Node<E>[]) new Node<?>[DEFAULT_CAPACITY];
     threshold = (int) (DEFAULT_CAPACITY * LOAD_FACTOR);
     size = 0;
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     int position = 0;

     for (int i = 0; i < buckets.length; i++) {
         Node<E> current = buckets[i];
         while (current != null) {
             result[position++] = current.value;
             current = current.next;
         }
     }

     return result;
 }

 private void resize() {
     @SuppressWarnings("unchecked")
     Node<E>[] newBuckets = (Node<E>[]) new Node<?>[buckets.length << 1];

     for (int i = 0; i < buckets.length; i++) {
         Node<E> current = buckets[i];

         while (current != null) {
             Node<E> next = current.next;
             int index = bucketIndex(current.value, newBuckets.length);
             current.next = newBuckets[index];
             newBuckets[index] = current;
             current = next;
         }
     }

     buckets = newBuckets;
     threshold = (int) (newBuckets.length * LOAD_FACTOR);
     if (threshold == 0) {
         threshold = 1;
     }
 }

 private static int bucketIndex(Object value, int length) {
     int hash = value == null ? 0 : value.hashCode();
     hash ^= hash >>> 16;
     return hash & (length - 1);
 }

 private static boolean valuesEqual(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static final class Node<T> {
     private final T value;
     private Node<T> next;

     private Node(T value, Node<T> next) {
         this.value = value;
         this.next = next;
     }
 }

}
