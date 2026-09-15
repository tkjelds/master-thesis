public final class ConcurrentLinkedQueue<T> {
    private static final class Node<E> {
        private final E item;
        private Node<E> next;

     private Node(E item) {
         this.item = item;
     }
 }

 private Node<T> head;
 private Node<T> tail;
 private int size;

 public synchronized void enqueue(T item) {
     if (item == null) {
         throw new IllegalArgumentException("Queue elements cannot be null");
     }

     Node<T> node = new Node<T>(item);

     if (tail == null) {
         head = node;
         tail = node;
     } else {
         tail.next = node;
         tail = node;
     }

     size++;
     notifyAll();
 }

 public synchronized T dequeue() throws InterruptedException {
     while (head == null) {
         wait();
     }

     T item = head.item;
     head = head.next;
     size--;

     if (head == null) {
         tail = null;
     }

     return item;
 }

 public synchronized T tryDequeue() {
     if (head == null) {
         return null;
     }

     T item = head.item;
     head = head.next;
     size--;

     if (head == null) {
         tail = null;
     }

     return item;
 }

 public synchronized T peek() throws InterruptedException {
     while (head == null) {
         wait();
     }

     return head.item;
 }

 public synchronized T tryPeek() {
     return head == null ? null : head.item;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized int size() {
     return size;
 }

}
