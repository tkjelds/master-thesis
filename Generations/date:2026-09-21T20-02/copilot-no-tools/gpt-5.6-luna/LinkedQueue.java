public class LinkedQueue<T> {
    private static final class Node<E> {
        private final E value;
        private Node<E> next;

     private Node(E value) {
         this.value = value;
     }
 }

 private final int capacity;
 private Node<T> head;
 private Node<T> tail;
 private int size;

 public LinkedQueue() {
     this.capacity = 0;
 }

 public LinkedQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     this.capacity = capacity;
 }

 public synchronized void enqueue(T value) throws InterruptedException {
     requireValue(value);

     while (isFull()) {
         wait();
     }

     append(value);
     notifyAll();
 }

 public synchronized boolean offer(T value) {
     requireValue(value);

     if (isFull()) {
         return false;
     }

     append(value);
     notifyAll();
     return true;
 }

 public synchronized T dequeue() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     T value = removeFirst();
     notifyAll();
     return value;
 }

 public synchronized T poll() {
     if (size == 0) {
         return null;
     }

     T value = removeFirst();
     notifyAll();
     return value;
 }

 public synchronized T peek() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     return head.value;
 }

 public synchronized T peekImmediate() {
     return head == null ? null : head.value;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return capacity > 0 && size == capacity;
 }

 public synchronized int size() {
     return size;
 }

 public int capacity() {
     return capacity;
 }

 private void append(T value) {
     Node<T> node = new Node<T>(value);

     if (tail == null) {
         head = node;
         tail = node;
     } else {
         tail.next = node;
         tail = node;
     }

     size++;
 }

 private T removeFirst() {
     Node<T> node = head;
     head = node.next;
     node.next = null;
     size--;

     if (head == null) {
         tail = null;
     }

     return node.value;
 }

 private static void requireValue(Object value) {
     if (value == null) {
         throw new IllegalArgumentException("Null values are not supported");
     }
 }

}
