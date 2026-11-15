@ThreadSafeStack.ThreadSafe
public class ThreadSafeStack {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private static final int DEFAULT_CAPACITY = 10;

    public ThreadSafeStack() {
        this.elements = new Object[DEFAULT_CAPACITY];
        this.size = 0;
    }

    public void push(Object element) {
        synchronized (this) {
            ensureCapacity();
            elements[size++] = element;
            notifyAll();
        }
    }

    public Object pop() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Object element = elements[--size];
            notifyAll();
            return element;
        }
    }

    public Object peek() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            return elements[size - 1];
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    private void ensureCapacity() {
        if (size >= elements.length) {
            Object[] newElements = new Object[elements.length * 2];
            System.arraycopy(elements, 0, newElements, 0, size);
            elements = newElements;
        }
    }
}
