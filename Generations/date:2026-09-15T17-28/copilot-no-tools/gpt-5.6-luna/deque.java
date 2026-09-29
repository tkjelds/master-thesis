public final class ConcurrentDeque<T> {
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

 public ConcurrentDeque(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized int size() {
     return count;
 }

 public synchronized int capacity() {
     return elements.length;
 }

 public synchronized boolean isEmpty() {
     return count == 0;
 }

 public synchronized boolean isFull() {
     return count == elements.length;
 }

 public synchronized boolean offerFirst(T value) {
     requireNonNull(value);

     if (count == elements.length) {
         return false;
     }

     head = decrement(head);
     elements[head] = value;
     count++;
     notifyAll();
     return true;
 }

 public synchronized boolean offerLast(T value) {
     requireNonNull(value);

     if (count == elements.length) {
         return false;
     }

     elements[tail] = value;
     tail = increment(tail);
     count++;
     notifyAll();
     return true;
 }

 public synchronized void putFirst(T value) throws InterruptedException {
     requireNonNull(value);

     while (count == elements.length) {
         wait();
     }

     head = decrement(head);
     elements[head] = value;
     count++;
     notifyAll();
 }

 public synchronized void putLast(T value) throws InterruptedException {
     requireNonNull(value);

     while (count == elements.length) {
         wait();
     }

     elements[tail] = value;
     tail = increment(tail);
     count++;
     notifyAll();
 }

 public synchronized T pollFirst() {
     if (count == 0) {
         return null;
     }

     T value = valueAt(head);
     elements[head] = null;
     head = increment(head);
     count--;
     notifyAll();
     return value;
 }

 public synchronized T pollLast() {
     if (count == 0) {
         return null;
     }

     tail = decrement(tail);
     T value = valueAt(tail);
     elements[tail] = null;
     count--;
     notifyAll();
     return value;
 }

 public synchronized T takeFirst() throws InterruptedException {
     while (count == 0) {
         wait();
     }

     T value = valueAt(head);
     elements[head] = null;
     head = increment(head);
     count--;
     notifyAll();
     return value;
 }

 public synchronized T takeLast() throws InterruptedException {
     while (count == 0) {
         wait();
     }

     tail = decrement(tail);
     T value = valueAt(tail);
     elements[tail] = null;
     count--;
     notifyAll();
     return value;
 }

 public synchronized T peekFirst() {
     if (count == 0) {
         return null;
     }
     return valueAt(head);
 }

 public synchronized T peekLast() {
     if (count == 0) {
         return null;
     }
     return valueAt(decrement(tail));
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

 private int increment(int index) {
     return index == elements.length - 1 ? 0 : index + 1;
 }

 private int decrement(int index) {
     return index == 0 ? elements.length - 1 : index - 1;
 }

 @SuppressWarnings("unchecked")
 private T valueAt(int index) {
     return (T) elements[index];
 }

 private void requireNonNull(T value) {
     if (value == null) {
         throw new NullPointerException("Null values are not supported");
     }
 }

}
