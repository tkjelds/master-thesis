```java
public class ArrayQueue {
    private final int[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        elements = new int[capacity];
        head = 0;
        tail = 0;
        count = 0;
    }

    public synchronized void enqueue(int item) throws InterruptedException {
        while (count == elements.length) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        count++;
        notifyAll();
    }

    public synchronized int dequeue() throws InterruptedException {
        while (count == 0) {
            wait();
        }
        int item = elements[head];
        head = (head + 1) % elements.length;
        count--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return count;
    }
}
```
