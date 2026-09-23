public final class ConcurrentQueue<E> {
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

 public ConcurrentQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized void put(E element) throws InterruptedException {
     if (element == null) {
         throw new IllegalArgumentException("Null elements are not supported");
     }

     while (count == elements.length) {
         wait();
     }

     elements[tail] = element;
     tail = (tail + 1) % elements.length;
     count++;
     notifyAll();
 }

 public synchronized boolean offer(E element) {
     if (element == null) {
         throw new IllegalArgumentException("Null elements are not supported");
     }

     if (count == elements.length) {
         return false;
     }

     elements[tail] = element;
     tail = (tail + 1) % elements.length;
     count++;
     notifyAll();
     return true;
 }

 @SuppressWarnings("unchecked")
 public synchronized E take() throws InterruptedException {
     while (count == 0) {
         wait();
     }

     E element = (E) elements[head];
     elements[head] = null;
     head = (head + 1) % elements.length;
     count--;
     notifyAll();
     return element;
 }

 @SuppressWarnings("unchecked")
 public synchronized E poll() {
     if (count == 0) {
         return null;
     }

     E element = (E) elements[head];
     elements[head] = null;
     head = (head + 1) % elements.length;
     count--;
     notifyAll();
     return element;
 }

 @SuppressWarnings("unchecked")
 public synchronized E peek() {
     if (count == 0) {
         return null;
     }
     return (E) elements[head];
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

}
