public class ConcurrentList<T> {
    private final Object[] elements;
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

     elements[size] = element;
     size++;
     notifyAll();
 }

 public synchronized void add(int index, T element) throws InterruptedException {
     if (index < 0 || index > size) {
         throw new IndexOutOfBoundsException("Index: " + index);
     }

     while (size == elements.length) {
         wait();
     }

     for (int i = size; i > index; i--) {
         elements[i] = elements[i - 1];
     }

     elements[index] = element;
     size++;
     notifyAll();
 }

 public synchronized T removeFirst() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     T element = elementAt(0);
     removeAt(0);
     notifyAll();
     return element;
 }

 public synchronized T remove(int index) {
     checkElementIndex(index);

     T element = elementAt(index);
     removeAt(index);
     notifyAll();
     return element;
 }

 public synchronized T get(int index) {
     checkElementIndex(index);
     return elementAt(index);
 }

 public synchronized T set(int index, T element) {
     checkElementIndex(index);

     T previous = elementAt(index);
     elements[index] = element;
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
         elements[i] = null;
     }

     size = 0;
     notifyAll();
 }

 private void checkElementIndex(int index) {
     if (index < 0 || index >= size) {
         throw new IndexOutOfBoundsException("Index: " + index);
     }
 }

 @SuppressWarnings("unchecked")
 private T elementAt(int index) {
     return (T) elements[index];
 }

 private void removeAt(int index) {
     for (int i = index; i < size - 1; i++) {
         elements[i] = elements[i + 1];
     }

     size--;
     elements[size] = null;
 }

}
