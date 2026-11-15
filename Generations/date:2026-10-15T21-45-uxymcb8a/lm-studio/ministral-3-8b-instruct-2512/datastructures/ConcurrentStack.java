@ConcurrentStack.ThreadSafe
public class ConcurrentStack {
    @interface ThreadSafe {}

    private static class Node {
        final Object item;
        Node next;

        Node(Object item, Node next) {
            this.item = item;
            this.next = next;
        }
    }

    private volatile Node top;
    private final int capacity;

    public ConcurrentStack() {
        this(10);
    }

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void push(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }

        Node newNode = new Node(item, null);

        while (true) {
            Node currentTop = top;
            if (currentTop != null && count() >= capacity) {
                // Wait until space is available
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            newNode.next = currentTop;
            if (compareAndSetTop(currentTop, newNode)) {
                return;
            }
        }
    }

    public Object pop() {
        while (true) {
            Node currentTop = top;
            if (currentTop == null && count() <= 0) {
                // Wait until stack is not empty
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }

            if (currentTop != null && compareAndSetTop(currentTop, currentTop.next)) {
                Object item = currentTop.item;
                return item;
            }
        }
    }

    public boolean isEmpty() {
        return top == null && count() <= 0;
    }

    public int size() {
        return count();
    }

    private int count() {
        int count = 0;
        Node current = top;
        while (current != null) {
            count++;
            current = current.next;
        }
        return count;
    }

    private boolean compareAndSetTop(Node expect, Node update) {
        while (true) {
            Node current = top;
            if (current == expect) {
                top = update;
                return true;
            }
        }
    }

    public void notifyPush() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void notifyPop() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void clear() {
        while (true) {
            Node currentTop = top;
            if (currentTop == null && count() <= 0) {
                return;
            }

            if (compareAndSetTop(currentTop, null)) {
                return;
            }
        }
    }

    public Object peek() {
        while (true) {
            Node currentTop = top;
            if (currentTop == null && count() <= 0) {
                return null;
            }

            if (compareAndSetTop(currentTop, currentTop)) { // Just read
                return currentTop.item;
            }
        }
    }

    public void pushAll(ConcurrentStack other) {
        if (other == null || other.top == null) return;

        Node current = other.top;
        while (current != null) {
            push(current.item);
            current = current.next;
        }
    }

    public ConcurrentStack clone() {
        ConcurrentStack clone = new ConcurrentStack(capacity);
        Node current = top;
        while (current != null) {
            clone.push(current.item);
            current = current.next;
        }
        return clone;
    }

    public void transferTo(ConcurrentStack other) {
        if (other == null || other.capacity <= 0) return;

        while (!isEmpty()) {
            Object item = pop();
            other.push(item);
        }
    }

    public void ensureCapacity(int minCapacity) {
        if (minCapacity <= 0 || minCapacity < capacity) return;

        while (count() >= capacity && !isEmpty()) {
            pop();
        }
    }

    public void trimToSize() {
        while (count() > capacity && !isEmpty()) {
            pop();
        }
    }

    public void pushAll(Object[] items) {
        if (items == null || items.length <= 0) return;

        for (Object item : items) {
            if (item != null) {
                push(item);
            }
        }
    }

    public Object[] toArray() {
        int size = count();
        Object[] array = new Object[size];
        Node current = top;
        int index = 0;

        while (current != null) {
            array[index++] = current.item;
            current = current.next;
        }

        return array;
    }
}
