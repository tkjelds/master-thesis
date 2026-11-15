@ConcurrentList.ThreadSafe
public final class ConcurrentList<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int size;

 public ConcurrentList(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized void add(T element) throws InterruptedException {
     while (size == elements.length) {
         wait();
     }

     int tail = (head + size) % elements.length;
     elements[tail] = element;
     size++;
     notifyAll();
 }

 public synchronized boolean offer(T element) {
     if (size == elements.length) {
         return false;
     }

     int tail = (head + size) % elements.length;
     elements[tail] = element;
     size++;
     notifyAll();
     return true;
 }

 public synchronized T removeFirst() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     T element = elementAt(head);
     elements[head] = null;
     head = (head + 1) % elements.length;
     size--;
     notifyAll();
     return element;
 }

 public synchronized T poll() {
     if (size == 0) {
         return null;
     }

     T element = elementAt(head);
     elements[head] = null;
     head = (head + 1) % elements.length;
     size--;
     notifyAll();
     return element;
 }

 public synchronized T get(int index) {
     checkIndex(index);
     return elementAt((head + index) % elements.length);
 }

 public synchronized T set(int index, T element) {
     checkIndex(index);

     int physicalIndex = (head + index) % elements.length;
     T previous = elementAt(physicalIndex);
     elements[physicalIndex] = element;
     return previous;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized int capacity() {
     return elements.length;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == elements.length;
 }

 public synchronized void clear() {
     for (int i = 0; i < size; i++) {
         elements[(head + i) % elements.length] = null;
     }

     head = 0;
     size = 0;
     notifyAll();
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];

     for (int i = 0; i < size; i++) {
         result[i] = elements[(head + i) % elements.length];
     }

     return result;
 }

 @SuppressWarnings("unchecked")
 private T elementAt(int index) {
     return (T) elements[index];
 }

 private void checkIndex(int index) {
     if (index < 0 || index >= size) {
         throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
     }
 }

}
