public final class Qlueue<E> {
    private static final int DEFAULT_CAPACITY = 16;

 private final int capacity;
 private Node<E> head;
 private Node<E> tail;
 private int size;

 private static final class Node<T> {
     private final T value;
     private Node<T> next;

     private Node(T value) {
         this.value = value;
     }
 }

 public Qlueue() {
     this(DEFAULT_CAPACITY);
 }

 public Qlueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
     }
     this.capacity = capacity;
 }

 public synchronized void put(E value) throws InterruptedException {
     requireNonNull(value);

     while (size == capacity) {
         wait();
     }

     append(value);
     notifyAll();
 }

 public synchronized boolean offer(E value) {
     requireNonNull(value);

     if (size == capacity) {
         return false;
     }

     append(value);
     notifyAll();
     return true;
 }

 public synchronized E take() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     E value = removeHead();
     notifyAll();
     return value;
 }

 public synchronized E poll() {
     if (size == 0) {
         return null;
     }

     E value = removeHead();
     notifyAll();
     return value;
 }

 public synchronized E peek() {
     return head == null ? null : head.value;
 }

 public synchronized void enqueue(E value) throws InterruptedException {
     put(value);
 }

 public synchronized boolean tryEnqueue(E value) {
     return offer(value);
 }

 public synchronized E dequeue() throws InterruptedException {
     return take();
 }

 public synchronized E tryDequeue() {
     return poll();
 }

 public synchronized int size() {
     return size;
 }

 public int capacity() {
     return capacity;
 }

 public synchronized int remainingCapacity() {
     return capacity - size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == capacity;
 }

 public synchronized void clear() {
     head = null;
     tail = null;
     size = 0;
     notifyAll();
 }

 private void append(E value) {
     Node<E> node = new Node<E>(value);

     if (tail == null) {
         head = node;
     } else {
         tail.next = node;
     }

     tail = node;
     size++;
 }

 private E removeHead() {
     Node<E> node = head;
     head = node.next;
     node.next = null;
     size--;

     if (head == null) {
         tail = null;
     }

     return node.value;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new IllegalArgumentException("null values are not supported");
     }
 }

}
