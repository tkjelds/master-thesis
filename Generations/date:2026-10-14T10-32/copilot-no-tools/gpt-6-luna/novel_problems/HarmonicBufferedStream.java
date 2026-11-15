public final class HarmonicBufferedStream {
    public interface ChunkSource {
        byte[] read();
        void close();
    }

 public interface CompletionListener {
     void complete(Result result);
 }

 public enum State {
     OPEN,
     READING,
     COMPLETING,
     CLOSED,
     FAILED
 }

 public static final class Snapshot {
     public final byte[] buffer;
     public final long byteCount;
     public final int phase;
     public final State state;

     private Snapshot(byte[] buffer, long byteCount, int phase, State state) {
         this.buffer = buffer;
         this.byteCount = byteCount;
         this.phase = phase;
         this.state = state;
     }

     public byte[] getBuffer() {
         return copy(buffer);
     }

     public long getByteCount() {
         return byteCount;
     }

     public int getPhase() {
         return phase;
     }

     public State getState() {
         return state;
     }
 }

 public static final class Result {
     public final byte[] buffer;
     public final long byteCount;
     public final int phase;
     public final Throwable failure;

     private Result(byte[] buffer, long byteCount, int phase, Throwable failure) {
         this.buffer = buffer;
         this.byteCount = byteCount;
         this.phase = phase;
         this.failure = failure;
     }

     public byte[] getBuffer() {
         return copy(buffer);
     }

     public long getByteCount() {
         return byteCount;
     }

     public int getPhase() {
         return phase;
     }

     public Throwable getFailure() {
         return failure;
     }
 }

 private final Object lock = new Object();
 private ChunkSource source;
 private CompletionListener listener;
 private byte[] buffer = new byte[0];
 private long byteCount;
 private int phase;
 private State state = State.OPEN;
 private boolean closeRequested;

 public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
     if (source == null) {
         throw new NullPointerException("source");
     }
     if (listener == null) {
         throw new NullPointerException("listener");
     }
     this.source = source;
     this.listener = listener;
 }

 public byte[] readChunk() throws InterruptedException {
     ChunkSource activeSource;

     synchronized (lock) {
         while (state == State.READING) {
             lock.wait();
         }
         if (state != State.OPEN) {
             return null;
         }
         state = State.READING;
         activeSource = source;
     }

     byte[] sourceChunk = null;
     byte[] retainedChunk = null;
     byte[] deliveredChunk = null;
     Throwable readFailure = null;

     try {
         sourceChunk = activeSource.read();
         if (sourceChunk != null) {
             retainedChunk = copy(sourceChunk);
             deliveredChunk = copy(retainedChunk);
         }
     } catch (RuntimeException | Error failure) {
         readFailure = failure;
     }

     ChunkSource completionSource = null;
     CompletionListener completionListener = null;
     boolean complete = false;

     synchronized (lock) {
         if (readFailure == null && retainedChunk != null) {
             try {
                 append(retainedChunk);
             } catch (RuntimeException | Error failure) {
                 readFailure = failure;
             }
         }

         if (readFailure != null || sourceChunk == null || closeRequested) {
             state = State.COMPLETING;
             completionSource = source;
             completionListener = listener;
             complete = true;
         } else {
             state = State.OPEN;
         }
         lock.notifyAll();
     }

     if (complete) {
         finishCompletion(completionSource, completionListener, readFailure);
     }

     return deliveredChunk;
 }

 public void close() {
     ChunkSource completionSource;
     CompletionListener completionListener;

     synchronized (lock) {
         if (state == State.READING) {
             closeRequested = true;
             return;
         }
         if (state != State.OPEN) {
             return;
         }

         closeRequested = true;
         state = State.COMPLETING;
         completionSource = source;
         completionListener = listener;
         lock.notifyAll();
     }

     finishCompletion(completionSource, completionListener, null);
 }

 public Snapshot snapshot() {
     synchronized (lock) {
         return new Snapshot(copy(buffer), byteCount, phase, state);
     }
 }

 private void append(byte[] chunk) {
     int required = buffer.length + chunk.length;
     if (required < 0) {
         throw new OutOfMemoryError("Buffered stream is too large");
     }

     if (required > buffer.length) {
         int capacity = buffer.length == 0 ? 16 : buffer.length;
         while (capacity < required) {
             if (capacity > Integer.MAX_VALUE / 2) {
                 capacity = Integer.MAX_VALUE;
             } else {
                 capacity *= 2;
             }
             if (capacity < required && capacity == Integer.MAX_VALUE) {
                 throw new OutOfMemoryError("Buffered stream is too large");
             }
         }

         byte[] expanded = new byte[capacity];
         for (int i = 0; i < buffer.length; i++) {
             expanded[i] = buffer[i];
         }
         buffer = expanded;
     }

     for (int i = 0; i < chunk.length; i++) {
         buffer[byteCountAsIndex() + i] = chunk[i];
     }

     byteCount += chunk.length;
     for (int i = 0; i < chunk.length; i++) {
         phase = (phase + (chunk[i] & 0xff)) % 360;
     }
 }

 private int byteCountAsIndex() {
     return (int) byteCount;
 }

 private void finishCompletion(
         ChunkSource completionSource,
         CompletionListener completionListener,
         Throwable initialFailure) {
     Throwable failure = initialFailure;

     try {
         completionSource.close();
     } catch (RuntimeException | Error closeFailure) {
         failure = combine(failure, closeFailure);
     }

     try {
         byte[] completedBuffer;
         long completedByteCount;
         int completedPhase;
         synchronized (lock) {
             completedBuffer = copy(buffer);
             completedByteCount = byteCount;
             completedPhase = phase;
         }

         Result result = new Result(
                 completedBuffer,
                 completedByteCount,
                 completedPhase,
                 failure);
         try {
             completionListener.complete(result);
         } catch (RuntimeException | Error listenerFailure) {
             failure = combine(failure, listenerFailure);
         }
     } catch (RuntimeException | Error completionFailure) {
         failure = combine(failure, completionFailure);
     } finally {
         synchronized (lock) {
             source = null;
             listener = null;
             state = failure == null ? State.CLOSED : State.FAILED;
             lock.notifyAll();
         }
     }

     rethrow(failure);
 }

 private static Throwable combine(Throwable earlier, Throwable later) {
     if (earlier == null) {
         return later;
     }
     if (earlier != later) {
         earlier.addSuppressed(later);
     }
     return earlier;
 }

 private static void rethrow(Throwable failure) {
     if (failure instanceof RuntimeException) {
         throw (RuntimeException) failure;
     }
     if (failure instanceof Error) {
         throw (Error) failure;
     }
 }

 private static byte[] copy(byte[] source) {
     byte[] result = new byte[source.length];
     for (int i = 0; i < source.length; i++) {
         result[i] = source[i];
     }
     return result;
 }

}
