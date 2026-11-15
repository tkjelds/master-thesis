public final class DelayQueue<E> {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4L;

 private static final class Entry<E> {
     final E value;
     final long deadline;

     Entry(E value, long deadline) {
         this.value = value;
         this.deadline = deadline;
     }
 }

 private final Object[] heap;
 private int size;

 public DelayQueue() {
     this(16);
 }

 public DelayQueue(int initialCapacity) {
     if (initialCapacity < 1) {
         throw new IllegalArgumentException("initialCapacity must be positive");
     }
     heap = new Object[initialCapacity];
 }

 public boolean offer(E value, long delayMillis) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     synchronized (this) {
         long delayNanos = toNanos(delayMillis);
         Entry<E> entry = new Entry<E>(value, System.nanoTime() + delayNanos);
         ensureCapacity();

         int index = size++;
         while (index > 0) {
             int parent = (index - 1) >>> 1;
             Entry<E> parentEntry = entryAt(parent);
             if (!isEarlier(entry.deadline, parentEntry.deadline)) {
                 break;
             }
             heap[index] = parentEntry;
             index = parent;
         }
         heap[index] = entry;
         notifyAll();
         return true;
     }
 }

 public E take() throws InterruptedException {
     synchronized (this) {
         while (true) {
             while (size == 0) {
                 wait();
             }

             Entry<E> first = entryAt(0);
             long remaining = first.deadline - System.nanoTime();
             if (remaining <= 0) {
                 removeHead();
                 return first.value;
             }

             long millis = remaining / NANOS_PER_MILLI;
             int nanos = (int) (remaining % NANOS_PER_MILLI);
             wait(millis, nanos);
         }
     }
 }

 public E poll() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }

         Entry<E> first = entryAt(0);
         if (first.deadline - System.nanoTime() > 0) {
             return null;
         }

         removeHead();
         return first.value;
     }
 }

 public E peek() {
     synchronized (this) {
         return size == 0 ? null : entryAt(0).value;
     }
 }

 public int size() {
     synchronized (this) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (this) {
         return size == 0;
     }
 }

 public void clear() {
     synchronized (this) {
         for (int i = 0; i < size; i++) {
             heap[i] = null;
         }
         size = 0;
         notifyAll();
     }
 }

 private void ensureCapacity() {
     if (size < heap.length) {
         return;
     }

     int newCapacity = heap.length <= Integer.MAX_VALUE / 2
             ? heap.length * 2
             : Integer.MAX_VALUE;
     if (newCapacity <= heap.length) {
         throw new OutOfMemoryError("DelayQueue capacity exceeded");
     }

     Object[] expanded = new Object[newCapacity];
     for (int i = 0; i < size; i++) {
         expanded[i] = heap[i];
     }
     System.arraycopy(expanded, 0, heap, 0, size);
 }

 private Entry<E> entryAt(int index) {
     return (Entry<E>) heap[index];
 }

 private void removeHead() {
     int lastIndex = --size;
     Entry<E> last = entryAt(lastIndex);
     heap[lastIndex] = null;

     if (lastIndex == 0) {
         return;
     }

     int index = 0;
     while (true) {
         int left = index * 2 + 1;
         if (left >= size) {
             break;
         }

         int right = left + 1;
         int earlierChild = left;
         if (right < size
                 && isEarlier(entryAt(right).deadline, entryAt(left).deadline)) {
             earlierChild = right;
         }

         Entry<E> child = entryAt(earlierChild);
         if (!isEarlier(child.deadline, last.deadline)) {
             break;
         }

         heap[index] = child;
         index = earlierChild;
     }
     heap[index] = last;
 }

 private static long toNanos(long delayMillis) {
     if (delayMillis <= 0) {
         return 0;
     }
     if (delayMillis > MAX_DELAY_NANOS / NANOS_PER_MILLI) {
         return MAX_DELAY_NANOS;
     }
     return delayMillis * NANOS_PER_MILLI;
 }

 private static boolean isEarlier(long firstDeadline, long secondDeadline) {
     return firstDeadline - secondDeadline < 0;
 }

}
