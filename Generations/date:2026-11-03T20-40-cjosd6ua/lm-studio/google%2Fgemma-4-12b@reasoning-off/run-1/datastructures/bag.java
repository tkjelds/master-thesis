class Bag<T> {
    private final T[] items;
    private int size;
    private final int capacity;

    @SuppressWarnings("unchecked")
    public Bag(int capacity) {
        this.capacity = capacity;
        this.items = (T[]) new Object[capacity];
        this.size = 0;
    }

    /**
     * Adds an item to the bag. Blocks if the bag is full.
     */
    public void add(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot add null item");
        }
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }
            items[size] = item;
            size++;
            this.notifyAll();
        }
    }

    /**
     * Removes and returns an item from the bag. Blocks if the bag is empty.
     */
    public T remove() throws InterruptedException {
        synchronized (this) {
            while (size == size == 0) {
                this.wait();
            }
            T item = items[size - 1];
            items[size - 1] = null; // Clear reference for GC
            size--;
            this.notifyAll();
            return item;
        }
    }

    /**
     * Returns the current size of the bag.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    /**
     * Returns true if the bag is empty.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    /**
     * Returns true if the bag is full.
     */
    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    public static void main(String[] args) {
        // Example usage logic could be placed here.
    }
}
