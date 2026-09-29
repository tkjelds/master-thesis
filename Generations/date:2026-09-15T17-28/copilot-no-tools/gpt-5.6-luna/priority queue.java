public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    private final Object[] heap;
    private int size;

 public ConcurrentPriorityQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     heap = new Object[capacity];
 }

 public synchronized void put(E element) throws InterruptedException {
     requireNonNull(element);

     while (size == heap.length) {
         wait();
     }

     heap[size] = element;
     siftUp(size);
     size++;
     notifyAll();
 }

 public synchronized boolean offer(E element) {
     requireNonNull(element);

     if (size == heap.length) {
         return false;
     }

     heap[size] = element;
     siftUp(size);
     size++;
     notifyAll();
     return true;
 }

 public synchronized E take() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     E result = removeRoot();
     notifyAll();
     return result;
 }

 public synchronized E poll() {
     if (size == 0) {
         return null;
     }

     E result = removeRoot();
     notifyAll();
     return result;
 }

 public synchronized E peek() {
     if (size == 0) {
         return null;
     }
     return elementAt(0);
 }

 public synchronized int size() {
     return size;
 }

 public synchronized int capacity() {
     return heap.length;
 }

 public synchronized int remainingCapacity() {
     return heap.length - size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == heap.length;
 }

 public synchronized void clear() {
     for (int i = 0; i < size; i++) {
         heap[i] = null;
     }
     size = 0;
     notifyAll();
 }

 private void siftUp(int index) {
     Object value = heap[index];

     while (index > 0) {
         int parent = (index - 1) / 2;
         Object parentValue = heap[parent];

         if (compare(value, parentValue) >= 0) {
             break;
         }

         heap[index] = parentValue;
         index = parent;
     }

     heap[index] = value;
 }

 private void siftDown(int index) {
     Object value = heap[index];
     int half = size / 2;

     while (index < half) {
         int left = index * 2 + 1;
         int right = left + 1;
         int smallerChild = left;

         if (right < size && compare(heap[right], heap[left]) < 0) {
             smallerChild = right;
         }

         if (compare(value, heap[smallerChild]) <= 0) {
             break;
         }

         heap[index] = heap[smallerChild];
         index = smallerChild;
     }

     heap[index] = value;
 }

 private E removeRoot() {
     E result = elementAt(0);
     int lastIndex = size - 1;
     Object last = heap[lastIndex];

     heap[lastIndex] = null;
     size--;

     if (size > 0) {
         heap[0] = last;
         siftDown(0);
     }

     return result;
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) heap[index];
 }

 @SuppressWarnings("unchecked")
 private int compare(Object first, Object second) {
     return ((E) first).compareTo((E) second);
 }

 private void requireNonNull(E element) {
     if (element == null) {
         throw new NullPointerException("Null elements are not supported");
     }
 }

}
