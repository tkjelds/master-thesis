@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

 private static final class Node<E> {
     private final E value;
     private Node<E> next;

     private Node(E value) {
         this.value = value;
     }
 }

 public ConcurrentCollection() {
     this(Integer.MAX_VALUE);
 }

 public ConcurrentCollection(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }
     this.capacity = capacity;
 }

 public synchronized boolean add(E element) throws InterruptedException {
     requireNonNull(element);

     while (size == capacity) {
         wait();
     }

     Node<E> node = new Node<E>(element);
     if (tail == null) {
         head = node;
     } else {
         tail.next = node;
     }
     tail = node;
     size++;
     notifyAll();
     return true;
 }

 public synchronized boolean tryAdd(E element) {
     requireNonNull(element);

     if (size == capacity) {
         return false;
     }

     Node<E> node = new Node<E>(element);
     if (tail == null) {
         head = node;
     } else {
         tail.next = node;
     }
     tail = node;
     size++;
     notifyAll();
     return true;
 }

 public synchronized E remove() throws InterruptedException {
     while (head == null) {
         wait();
     }

     E value = removeHead();
     notifyAll();
     return value;
 }

 public synchronized E poll() {
     if (head == null) {
         return null;
     }

     E value = removeHead();
     notifyAll();
     return value;
 }

 public synchronized E peek() {
     return head == null ? null : head.value;
 }

 public synchronized boolean contains(Object element) {
     Node<E> current = head;
     while (current != null) {
         if (equal(current.value, element)) {
             return true;
         }
         current = current.next;
     }
     return false;
 }

 public synchronized boolean remove(Object element) {
     Node<E> previous = null;
     Node<E> current = head;

     while (current != null) {
         if (equal(current.value, element)) {
             if (previous == null) {
                 head = current.next;
             } else {
                 previous.next = current.next;
             }

             if (current == tail) {
                 tail = previous;
             }

             size--;
             notifyAll();
             return true;
         }

         previous = current;
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

 public synchronized int capacity() {
     return capacity;
 }

 public synchronized void clear() {
     head = null;
     tail = null;
     size = 0;
     notifyAll();
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> current = head;
     int index = 0;

     while (current != null) {
         result[index++] = current.value;
         current = current.next;
     }

     return result;
 }

 private E removeHead() {
     Node<E> removed = head;
     head = removed.next;
     removed.next = null;
     size--;

     if (head == null) {
         tail = null;
     }

     return removed.value;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new NullPointerException("Null elements are not supported");
     }
 }

 private static boolean equal(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

}
