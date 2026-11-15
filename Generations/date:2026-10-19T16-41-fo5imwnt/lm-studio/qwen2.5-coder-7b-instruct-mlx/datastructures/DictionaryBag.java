@DictionaryBag.ThreadSafe
public class DictionaryBag {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 10;
    private String[] elements;
    private int size;

    public DictionaryBag() {
        this.elements = new String[DEFAULT_CAPACITY];
        this.size = 0;
    }

    public synchronized void add(String element) {
        if (size == elements.length) {
            resize();
        }
        elements[size++] = element;
    }

    public synchronized String remove() {
        if (size == 0) {
            throw new IllegalStateException("Cannot remove from an empty bag");
        }
        String element = elements[0];
        for (int i = 1; i < size; i++) {
            elements[i - 1] = elements[i];
        }
        elements[--size] = null;
        return element;
    }

    public synchronized boolean contains(String element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void resize() {
        int newCapacity = elements.length * 2;
        String[] newElements = new String[newCapacity];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
