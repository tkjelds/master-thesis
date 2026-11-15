@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}


    private static class Node<E> {
        private final E data;
        private Node<E> next;

        private Node(E data) {
            this.data = data;
        }
    }

    private volatile Node<E> head;
    private volatile int size;
    private static final int INITIAL_CAPACITY = 10;
    private Node<E>[] table;

    public ConcurrentLinkedList() {
        this(INITIAL_CAPACITY);
    }

    public ConcurrentLinkedList(int initialCapacity) {
        this.head = null;
        this.size = 0;
        this.table = (Node<E>[]) new Node[initialCapacity];
    }

    private synchronized void ensureCapacity(int minCapacity) {
        while (table.length < minCapacity) {
            int newCapacity = table.length * 2;
            Node<E>[] newTable = (Node<E>[]) new Node[newCapacity];
            System.arraycopy(table, 0, newTable, 0, table.length);
            table = newTable;
        }
    }

    public synchronized void addFirst(E e) {
        ensureCapacity(1);
        Node<E> newNode = new Node<>(e);
        newNode.next = head;
        head = newNode;
        size++;
    }

    public synchronized void addLast(E e) {
        ensureCapacity(1);
        Node<E> newNode = new Node<>(e);
        
        if (head == null) {
            head = newNode;
        } else {
            Node<E> current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
        size++;
    }

    public synchronized E getFirst() {
        if (head == null) {
            return null;
        }
        E data = head.data;
        return data;
    }

    public synchronized E getLast() {
        if (head == null) {
            return null;
        }
        Node<E> current = head;
        while (current.next != null) {
            current = current.next;
        }
        return current.data;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return head == null;
    }

    public synchronized void removeFirst() throws InterruptedException {
        while (head == null) {
            wait();
        }
        Node<E> oldHead = head;
        head = oldHead.next;
        if (head == null) {
            // The list is now empty, release reference to help GC
            oldHead = null;
        }
        size--;
        notifyAll();
    }

    public synchronized void removeLast() throws InterruptedException {
        if (head == null) {
            throw new RuntimeException("List is empty");
        }
        
        if (head.next == null) {
            // Only one element
            head = null;
        } else {
            Node<E> current = head;
            Node<E> prev = head;
            while (prev.next != null) {
                prev = prev.next;
            }
            prev.next = null;
        }
        size--;
        notifyAll();
    }

    public synchronized boolean removeAtIndex(int index) throws InterruptedException {
        if (head == null) {
            throw new RuntimeException("List is empty");
        }
        
        if (index == 0) {
            removeFirst();
            return true;
        }
        
        if (index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }

        Node<E> current = head;
        Node<E> prev = null;
        int currentPos = 0;
        
        while (current != null) {
            if (currentPos == index) {
                break;
            }
            prev = current;
            current = current.next;
            currentPos++;
        }

        if (prev == null) {
            throw new RuntimeException("Failed to remove at index " + index);
        }

        E removed = current.data;
        prev.next = current.next;
        size--;
        
        return true;
    }

    public synchronized E removeAtZero() throws InterruptedException {
        if (head == null) {
            throw new RuntimeException("List is empty");
        }
        
        Node<E> temp = head;
        head = temp.next;
        size--;
        return temp.data;
    }

    public synchronized E removeLastElement() throws InterruptedException {
        if (head == null) {
            throw new RuntimeException("List is empty");
        }

        if (head.next == null) {
            Node<E> removed = head;
            head = null;
            size--;
            return removed.data;
        }

        Node<E> current = head;
        while (current.next.next != null) {
            current = current.next;
        }

        Node<E> removed = current.next;
        current.next = null;
        size--;
        return removed.data;
    }

    public synchronized void remove(E e) throws InterruptedException {
        if (head == null) {
            throw new RuntimeException("List is empty");
        }
        
        if (head.data.equals(e)) {
            removeFirst();
            return;
        }

        Node<E> current = head;
        Node<E> prev = head;
        
        while (current.next != null) {
            if (current.next.data.equals(e)) {
                prev.next = current.next.next;
                size--;
                return;
            }
            prev = current;
            current = current.next;
        }
    }

    public synchronized void clear() {
        while (true) {
            removeFirst();
        }
    }

    public synchronized void display() {
        Node<E> current = head;
        while (current != null) {
            System.out.print(current.data + " -> ");
            current = current.next;
        }
        System.out.println("null");
    }

    public static void main(String[] args) {
        ConcurrentLinkedList<Integer> list = new ConcurrentLinkedList<>();
        
        Thread t1 = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                list.addLast(i);
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 5; i >= 1; i--) {
                list.addFirst(i);
            }
        });
        
        Thread t3 = new Thread(() -> {
            while (!list.isEmpty()) {
                try {
                    list.removeFirst();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("Final size: " + list.size());
        list.display();
    }
}
