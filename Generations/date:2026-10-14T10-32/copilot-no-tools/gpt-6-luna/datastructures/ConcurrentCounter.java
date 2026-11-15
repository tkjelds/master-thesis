public final class ConcurrentCounter {
    private final Object lock = new Object();
    private long value;

 public ConcurrentCounter() {
     this(0L);
 }

 public ConcurrentCounter(long initialValue) {
     value = initialValue;
 }

 public long incrementAndGet() {
     synchronized (lock) {
         value++;
         return value;
     }
 }

 public long decrementAndGet() {
     synchronized (lock) {
         value--;
         return value;
     }
 }

 public long addAndGet(long delta) {
     synchronized (lock) {
         value += delta;
         return value;
     }
 }

 public long get() {
     synchronized (lock) {
         return value;
     }
 }

 public void reset(long newValue) {
     synchronized (lock) {
         value = newValue;
     }
 }

}
