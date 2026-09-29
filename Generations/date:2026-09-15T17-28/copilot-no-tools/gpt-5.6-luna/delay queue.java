public final class ConcurrentDelayQueue<E extends ConcurrentDelayQueue.Delayed> {
    public interface Delayed {
        long getDelayNanos();
    }

 private Object[] heap;
 private int size;

 public ConcurrentDelayQueue() {
     heap = new Object[16];
 }

 public synchronized boolean offer(E element) {
     requireElement(element);
     ensureCapacity(size + 1);
     heap[size] = element;
     siftUp(size);
     size++;
     notifyAll();
     return true;
 }

 public synchronized void put(E element) {
     offer(element);
 }

 public synchronized E peek() {
     if (size == 0) {
         return null;
     }
     return elementAt(0);
 }

 public synchronized E poll() {
     if (size == 0 || elementAt(0).getDelayNanos() > 0) {
         return null;
     }
     return removeAt(0);
 }

 public synchronized E take() throws InterruptedException {
     for (;;) {
         if (size == 0) {
             wait();
             continue;
         }

         E first = elementAt(0);
         long delay = first.getDelayNanos();

         if (delay <= 0) {
             return removeAt(0);
         }

         waitNanos(delay);
     }
 }

 public synchronized E poll(long timeoutNanos) throws InterruptedException {
     if (timeoutNanos < 0) {
         throw new IllegalArgumentException("timeoutNanos must not be negative");
     }

     long start = System.nanoTime();

     for (;;) {
         if (size != 0) {
             E first = elementAt(0);
             long delay = first.getDelayNanos();

             if (delay <= 0) {
                 return removeAt(0);
             }

             long elapsed = System.nanoTime() - start;
             long remaining = timeoutNanos - elapsed;

             if (remaining <= 0) {
                 return null;
             }

             waitNanos(delay < remaining ? delay : remaining);
         } else {
             long elapsed = System.nanoTime() - start;
             long remaining = timeoutNanos - elapsed;

             if (remaining <= 0) {
                 return null;
             }

             waitNanos(remaining);
         }
     }
 }

 public synchronized boolean remove(Object target) {
     if (target == null) {
         return false;
     }

     for (int i = 0; i < size; i++) {
         if (target.equals(heap[i])) {
             removeAt(i);
             return true;
         }
     }

     return false;
 }

 public synchronized void clear() {
     for (int i = 0; i < size; i++) {
         heap[i] = null;
     }
     size = 0;
     notifyAll();
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized int remainingCapacity() {
     return Integer.MAX_VALUE;
 }

 private void requireElement(E element) {
     if (element == null) {
         throw new NullPointerException("element");
     }
 }

 private void ensureCapacity(int requiredCapacity) {
     if (requiredCapacity <= heap.length) {
         return;
     }

     int newCapacity = heap.length << 1;
     if (newCapacity < requiredCapacity || newCapacity < 0) {
         newCapacity = requiredCapacity;
     }

     Object[] expanded = new Object[newCapacity];
     for (int i = 0; i < size; i++) {
         expanded[i] = heap[i];
     }
     heap = expanded;
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) heap[index];
 }

 private E removeAt(int index) {
     E removed = elementAt(index);
     int lastIndex = --size;

     if (index == lastIndex) {
         heap[lastIndex] = null;
     } else {
         heap[index] = heap[lastIndex];
         heap[lastIndex] = null;
         siftDown(index);
         if (heap[index] != null && less(index, parent(index))) {
             siftUp(index);
         }
     }

     notifyAll();
     return removed;
 }

 private void siftUp(int index) {
     while (index > 0) {
         int parent = parent(index);
         if (!less(index, parent)) {
             break;
         }
         swap(index, parent);
         index = parent;
     }
 }

 private void siftDown(int index) {
     for (;;) {
         int left = (index << 1) + 1;
         if (left >= size) {
             return;
         }

         int right = left + 1;
         int smallest = left;

         if (right < size && less(right, left)) {
             smallest = right;
         }

         if (!less(smallest, index)) {
             return;
         }

         swap(index, smallest);
         index = smallest;
     }
 }

 private boolean less(int first, int second) {
     return elementAt(first).getDelayNanos() < elementAt(second).getDelayNanos();
 }

 private int parent(int index) {
     return (index - 1) >>> 1;
 }

 private void swap(int first, int second) {
     Object temporary = heap[first];
     heap[first] = heap[second];
     heap[second] = temporary;
 }

 private void waitNanos(long nanos) throws InterruptedException {
     long millis = nanos / 1_000_000L;
     int extraNanos = (int) (nanos % 1_000_000L);

     if (millis == 0 && extraNanos == 0) {
         extraNanos = 1;
     }

     wait(millis, extraNanos);
 }

}
