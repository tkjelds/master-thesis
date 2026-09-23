public final class DelayQueue<E> {
    private static final class Node<E> {
        final E value;
        final long deadline;
        final long sequence;
        Node<E> next;

     Node(E value, long deadline, long sequence) {
         this.value = value;
         this.deadline = deadline;
         this.sequence = sequence;
     }
 }

 private Node<E> head;
 private int size;
 private long nextSequence;

 public synchronized boolean offer(E value, long delayNanos) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     long now = System.nanoTime();
     long deadline = now + delayNanos;
     Node<E> node = new Node<E>(value, deadline, nextSequence++);

     if (head == null || comesBefore(node, head)) {
         node.next = head;
         head = node;
     } else {
         Node<E> current = head;
         while (current.next != null && !comesBefore(node, current.next)) {
             current = current.next;
         }
         node.next = current.next;
         current.next = node;
     }

     size++;
     notifyAll();
     return true;
 }

 public synchronized void put(E value, long delayNanos) {
     offer(value, delayNanos);
 }

 public synchronized E take() throws InterruptedException {
     for (;;) {
         if (head == null) {
             wait();
             continue;
         }

         long remaining = head.deadline - System.nanoTime();
         if (remaining > 0) {
             waitNanos(remaining);
             continue;
         }

         E value = head.value;
         head = head.next;
         size--;
         notifyAll();
         return value;
     }
 }

 public synchronized E poll() {
     if (head == null || head.deadline - System.nanoTime() > 0) {
         return null;
     }

     E value = head.value;
     head = head.next;
     size--;
     notifyAll();
     return value;
 }

 public synchronized E peek() {
     return head == null ? null : head.value;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     head = null;
     size = 0;
     notifyAll();
 }

 private static boolean comesBefore(Node<?> first, Node<?> second) {
     long difference = first.deadline - second.deadline;
     if (difference < 0) {
         return true;
     }
     if (difference > 0) {
         return false;
     }
     return first.sequence < second.sequence;
 }

 private static void waitNanos(long nanos) throws InterruptedException {
     long milliseconds = nanos / 1000000L;
     int remainder = (int) (nanos % 1000000L);

     if (milliseconds == 0L && remainder == 0) {
         return;
     }

     if (remainder == 0) {
         Thread.currentThread().join(milliseconds);
     } else {
         Thread.currentThread().join(milliseconds, remainder);
     }
 }

}
