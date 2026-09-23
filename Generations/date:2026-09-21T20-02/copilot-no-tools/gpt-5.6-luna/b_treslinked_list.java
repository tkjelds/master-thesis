public final class b_treslinked_list<E> {
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

 private static final class Node<T> {
     private final T value;
     private Node<T> next;
     private Node<T> previous;

     private Node(T value) {
         this.value = value;
     }
 }

 public b_treslinked_list(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     this.capacity = capacity;
 }

 public synchronized int capacity() {
     return capacity;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == capacity;
 }

 public void put(E value) throws InterruptedException {
     addLast(value);
 }

 public synchronized boolean offer(E value) {
     requireNonNull(value);

     if (size == capacity) {
         return false;
     }

     linkLast(value);
     notifyAll();
     return true;
 }

 public synchronized void addLast(E value) throws InterruptedException {
     requireNonNull(value);

     while (size == capacity) {
         wait();
     }

     linkLast(value);
     notifyAll();
 }

 public synchronized void addFirst(E value) throws InterruptedException {
     requireNonNull(value);

     while (size == capacity) {
         wait();
     }

     linkFirst(value);
     notifyAll();
 }

 public E take() throws InterruptedException {
     return removeFirst();
 }

 public synchronized E poll() {
     if (size == 0) {
         return null;
     }

     E value = unlink(head);
     notifyAll();
     return value;
 }

 public synchronized E removeFirst() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     E value = unlink(head);
     notifyAll();
     return value;
 }

 public synchronized E removeLast() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     E value = unlink(tail);
     notifyAll();
     return value;
 }

 public synchronized E peekFirst() {
     return head == null ? null : head.value;
 }

 public synchronized E peekLast() {
     return tail == null ? null : tail.value;
 }

 public synchronized E get(int index) {
     return nodeAt(index).value;
 }

 public synchronized E set(int index, E value) {
     requireNonNull(value);

     Node<E> node = nodeAt(index);
     E previous = node.value;

     Node<E> replacement = new Node<E>(value);
     replacement.previous = node.previous;
     replacement.next = node.next;

     if (node.previous == null) {
         head = replacement;
     } else {
         node.previous.next = replacement;
     }

     if (node.next == null) {
         tail = replacement;
     } else {
         node.next.previous = replacement;
     }

     return previous;
 }

 public synchronized E remove(int index) {
     E value = unlink(nodeAt(index));
     notifyAll();
     return value;
 }

 public synchronized boolean removeValue(E value) {
     requireNonNull(value);

     Node<E> current = head;
     while (current != null) {
         if (sameValue(current.value, value)) {
             unlink(current);
             notifyAll();
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized boolean contains(E value) {
     requireNonNull(value);

     Node<E> current = head;
     while (current != null) {
         if (sameValue(current.value, value)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> current = head;
     int index = 0;

     while (current != null) {
         result[index++] = current.value;
         current = current.next;
     }

     return result;
 }

 public synchronized void clear() {
     head = null;
     tail = null;
     size = 0;
     notifyAll();
 }

 private void linkFirst(E value) {
     Node<E> node = new Node<E>(value);
     node.next = head;

     if (head == null) {
         tail = node;
     } else {
         head.previous = node;
     }

     head = node;
     size++;
 }

 private void linkLast(E value) {
     Node<E> node = new Node<E>(value);
     node.previous = tail;

     if (tail == null) {
         head = node;
     } else {
         tail.next = node;
     }

     tail = node;
     size++;
 }

 private E unlink(Node<E> node) {
     Node<E> previous = node.previous;
     Node<E> next = node.next;

     if (previous == null) {
         head = next;
     } else {
         previous.next = next;
     }

     if (next == null) {
         tail = previous;
     } else {
         next.previous = previous;
     }

     node.previous = null;
     node.next = null;
     size--;

     return node.value;
 }

 private Node<E> nodeAt(int index) {
     if (index < 0 || index >= size) {
         throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
     }

     if (index < size / 2) {
         Node<E> current = head;
         for (int i = 0; i < index; i++) {
             current = current.next;
         }
         return current;
     }

     Node<E> current = tail;
     for (int i = size - 1; i > index; i--) {
         current = current.previous;
     }
     return current;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new NullPointerException("Null values are not supported");
     }
 }

 private static boolean sameValue(Object first, Object second) {
     return first == second || first.equals(second);
 }

}
