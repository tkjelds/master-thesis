@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Node[] elements;
    private int head;
    private int tail;
    private int size;
    private final Object lock = new Object();

    public ConcurrentDeque() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.elements = new Node[capacity];
    }

    public void addFirst(Object item) {
        synchronized (lock) {
            if (size == elements.length) {
                expandCapacity();
            }
            Node newNode = new Node(item);
            if (head == 0) {
                head = elements.length - 1;
                tail = head;
            } else {
                head--;
            }
            elements[head] = newNode;
            size++;
        }
    }

    public void addLast(Object item) {
        synchronized (lock) {
            if (size == elements.length) {
                expandCapacity();
            }
            Node newNode = new Node(item);
            if (tail == elements.length - 1) {
                tail = 0;
            } else {
                tail++;
            }
            elements[tail] = newNode;
            size++;
        }
    }

    public Object removeFirst() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted", e);
                }
            }

            Object item = elements[head].item;
            if (head == tail) {
                head = 0;
                tail = 0;
                size = 0;
            } else if (head == elements.length - 1) {
                head = 0;
            } else {
                head++;
            }
            elements[head] = null; // Help GC
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public Object removeLast() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted", e);
                }
            }

            Object item = elements[tail].item;
            if (head == tail) {
                head = 0;
                tail = 0;
                size = 0;
            } else if (tail == 0) {
                tail = elements.length - 1;
            } else {
                tail--;
            }
            elements[tail] = null; // Help GC
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public Object getFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            return elements[head].item;
        }
    }

    public Object getLast() {
        synchronized (lock) {
            if (size == 0) return null;
            return elements[tail].item;
        }
    }

    public boolean contains(Object item) {
        synchronized (lock) {
            if (item == null) {
                for (int i = 0; i < size; i++) {
                    int index = (head + i) % elements.length;
                    if (elements[index].item == null) return true;
                }
            } else {
                for (int i = 0; i < size; i++) {
                    int index = (head + i) % elements.length;
                    if (item.equals(elements[index].item)) return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    private void expandCapacity() {
        Node[] newElements = new Node[elements.length * 2];
        if (head < tail) {
            System.arraycopy(elements, head, newElements, 0, size);
        } else {
            System.arraycopy(elements, head, newElements, 0, elements.length - head);
            System.arraycopy(elements, 0, newElements, elements.length - head, tail + 1);
        }
        head = 0;
        tail = size - 1;
        elements = newElements;
    }

    private static class Node {
        Object item;

        Node(Object item) {
            this.item = item;
        }
    }
}
