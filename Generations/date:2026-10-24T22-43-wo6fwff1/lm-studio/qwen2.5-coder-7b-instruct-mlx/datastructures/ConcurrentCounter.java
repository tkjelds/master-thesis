```java
public class ConcurrentCounter {
    private int count;

    public ConcurrentCounter() {
        this.count = 0;
    }

    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}
```
