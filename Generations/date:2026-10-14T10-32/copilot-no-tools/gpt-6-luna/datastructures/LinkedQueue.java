public final class LinkedQueue<E> {
    private static final class Node<T> {
        private final T item;
        private Node<T> next;

     private Node(T item) {
         this.item = item;
     }
 }

 private final Object lock = new Object();
 private Node<E> head;
 private Node<E> tail;
 private int size;

 /**
  * Adds an item to this unbounded queue. Null items are not allowed.
  */
 public void enqueue(E item) {
     if (item == null) {
         throw new NullPointerException("item");
     }

     synchronized (lock) {
         Node<E> node = new Node<>(item);
         if (tail == null) {
             head = node;
         } else {
             tail.next = node;
         }
         tail = node;
         size++;
         lock.notifyAll();
     }
 }

 /**
  * Removes and returns the next item, waiting while the queue is empty.
  * Throws InterruptedException if interrupted while waiting.
  */
 public E dequeue() throws InterruptedException {
     synchronized (lock) {
         while (head == null) {
             lock.wait();
         }
         return removeHead();
     }
 }

 /**
  * Removes and returns the next item, or null if the queue is empty.
  */
 public E poll() {
     synchronized (lock) {
         if (head == null) {
             return null;
         }
         return removeHead();
     }
 }

 /**
  * Returns the next item without removing it, or null if the queue is empty.
  */
 public E peek() {
     synchronized (lock) {
         return head == null ? null : head.item;
     }
 }

 public int size() {
     synchronized (lock) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return head == null;
     }
 }

 private E removeHead() {
     Node<E> first = head;
     head = first.next;
     if (head == null) {
         tail = null;
     }
     size--;
     return first.item;
 }

}
