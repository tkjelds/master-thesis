@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}

    private long value;

 public ConcurrentCounter() {
     this(0L);
 }

 public ConcurrentCounter(long initialValue) {
     value = initialValue;
 }

 public synchronized void increment() {
     value++;
 }

 public synchronized void decrement() {
     value--;
 }

 public synchronized void add(long amount) {
     value += amount;
 }

 public synchronized void subtract(long amount) {
     value -= amount;
 }

 public synchronized long get() {
     return value;
 }

 public synchronized long getAndIncrement() {
     long previous = value;
     value++;
     return previous;
 }

 public synchronized long incrementAndGet() {
     value++;
     return value;
 }

 public synchronized long getAndDecrement() {
     long previous = value;
     value--;
     return previous;
 }

 public synchronized long decrementAndGet() {
     value--;
     return value;
 }

 public synchronized long getAndAdd(long amount) {
     long previous = value;
     value += amount;
     return previous;
 }

 public synchronized long addAndGet(long amount) {
     value += amount;
     return value;
 }

 public synchronized void reset() {
     value = 0L;
 }

 public synchronized void set(long newValue) {
     value = newValue;
 }

 public synchronized boolean compareAndSet(long expectedValue, long newValue) {
     if (value != expectedValue) {
         return false;
     }

     value = newValue;
     return true;
 }

}
