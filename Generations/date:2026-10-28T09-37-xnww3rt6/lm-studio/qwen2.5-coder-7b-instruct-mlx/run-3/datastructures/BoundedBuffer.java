```java
public class BoundedBuffer {
    private final int[] buffer;
    private int takeIndex, putIndex, count;

    public BoundedBuffer(int capacity) {
        buffer = new int[capacity];
    }

    public synchronized void put(int item) throws InterruptedException {
        while (count == buffer.length)
            wait();
        buffer[putIndex] = item;
        putIndex = (putIndex + 1) % buffer.length;
        count++;
        notifyAll();
    }

    public synchronized int take() throws InterruptedException {
        while (count == 0)
            wait();
        int item = buffer[takeIndex];
        takeIndex = (takeIndex + 1) % buffer.length;
        count--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return count;
    }
}
```
