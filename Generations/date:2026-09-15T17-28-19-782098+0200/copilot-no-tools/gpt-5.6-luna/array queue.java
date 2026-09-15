public final class ConcurrentArrayQueue<E> {
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

 public ConcurrentArrayQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized void enqueue(E element) throws InterruptedException {
     requireNonNull(element);

     while (count == elements.length) {
         wait();
     }

     elements[tail] = element;
     tail = (tail + 1) % elements.length;
     count++;
     notifyAll();
 }

 public synchronized boolean offer(E element) {
     requireNonNull(element);

     if (count == elements.length) {
         return false;
     }

     elements[tail] = element;
     tail = (tail + 1) % elements.length;
     count++;
     notifyAll();
     return true;
 }

 public synchronized E dequeue() throws InterruptedException {
     while (count == 0) {
         wait();
     }

     E element = elementAtHead();
     notifyAll();
     return element;
 }

 public synchronized E poll() {
     if (count == 0) {
         return null;
     }

     E element = elementAtHead();
     notifyAll();
     return element;
 }

 public synchronized E peek() {
     if (count == 0) {
         return null;
     }

     return elementAtHeadWithoutRemoval();
 }

 public synchronized int size() {
     return count;
 }

 public synchronized int capacity() {
     return elements.length;
 }

 public synchronized int remainingCapacity() {
     return elements.length - count;
 }

 public synchronized boolean isEmpty() {
     return count == 0;
 }

 public synchronized boolean isFull() {
     return count == elements.length;
 }

 public synchronized void clear() {
     for (int i = 0; i < elements.length; i++) {
         elements[i] = null;
     }

     head = 0;
     tail = 0;
     count = 0;
     notifyAll();
 }

 @SuppressWarnings("unchecked")
 private E elementAtHead() {
     E element = (E) elements[head];
     elements[head] = null;
     head = (head + 1) % elements.length;
     count--;
     return element;
 }

 @SuppressWarnings("unchecked")
 private E elementAtHeadWithoutRemoval() {
     return (E) elements[head];
 }

 private void requireNonNull(E element) {
     if (element == null) {
         throw new IllegalArgumentException("Null elements are not supported");
     }
 }

}
