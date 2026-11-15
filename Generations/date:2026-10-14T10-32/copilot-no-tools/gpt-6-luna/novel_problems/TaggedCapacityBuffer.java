public final class TaggedCapacityBuffer {
    public static final class Message {
        public final long id;
        public final byte[] payload;
        public final String[] tags;

     private Message(long id, byte[] payload, String[] tags) {
         this.id = id;
         this.payload = payload.clone();
         this.tags = tags.clone();
     }

     public long getId() {
         return id;
     }

     public byte[] getPayload() {
         return payload.clone();
     }

     public String[] getTags() {
         return tags.clone();
     }
 }

 public static final class Snapshot {
     public final Message[] messages;
     public final int occupancy;
     public final int capacity;
     public final boolean closed;

     private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
         this.messages = new Message[messages.length];
         for (int i = 0; i < messages.length; i++) {
             Message message = messages[i];
             this.messages[i] = new Message(message.id, message.payload, message.tags);
         }
         this.occupancy = occupancy;
         this.capacity = capacity;
         this.closed = closed;
     }

     public Message[] getMessages() {
         Message[] copy = new Message[messages.length];
         for (int i = 0; i < messages.length; i++) {
             Message message = messages[i];
             copy[i] = new Message(message.id, message.payload, message.tags);
         }
         return copy;
     }

     public int getOccupancy() {
         return occupancy;
     }

     public int getCapacity() {
         return capacity;
     }

     public boolean isClosed() {
         return closed;
     }
 }

 private static final class Entry {
     final long id;
     final byte[] payload;
     final String[] tags;
     Entry next;

     Entry(long id, byte[] payload, String[] tags) {
         this.id = id;
         this.payload = payload;
         this.tags = tags;
     }

     int length() {
         return payload.length;
     }
 }

 private static long nextMessageId = 1;
 private static long nextLockOrder = 1;

 private final int capacity;
 private final long lockOrder;
 private Entry head;
 private Entry tail;
 private int occupancy;
 private boolean closed;

 public TaggedCapacityBuffer(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }

     this.capacity = capacity;
     synchronized (TaggedCapacityBuffer.class) {
         if (nextLockOrder <= 0) {
             throw new IllegalStateException("Buffer lock-order space exhausted");
         }
         this.lockOrder = nextLockOrder;
         nextLockOrder = nextLockOrder == Long.MAX_VALUE ? 0 : nextLockOrder + 1;
     }
 }

 public long offer(byte[] payload, String[] tags, String[] evictionTags) {
     validateInputs(payload, tags);
     validateTags(evictionTags);

     byte[] payloadCopy = payload.clone();
     String[] tagsCopy = tags.clone();
     String[] evictionTagsCopy = evictionTags.clone();

     synchronized (this) {
         if (closed) {
             throw new IllegalStateException("Buffer is closed");
         }

         long id = allocateMessageId();
         Entry entry = new Entry(id, payloadCopy, tagsCopy);

         while (occupancy > capacity - payloadCopy.length) {
             Entry evicted = removeOldestSharingAny(evictionTagsCopy);
             if (evicted == null) {
                 break;
             }
         }
         while (occupancy > capacity - payloadCopy.length) {
             removeHead();
         }

         append(entry);
         occupancy += payloadCopy.length;
         notifyAll();
         return id;
     }
 }

 public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
     validateInputs(payload, tags);

     byte[] payloadCopy = payload.clone();
     String[] tagsCopy = tags.clone();

     synchronized (this) {
         while (!closed && occupancy > capacity - payloadCopy.length) {
             wait();
         }
         if (closed) {
             throw new IllegalStateException("Buffer is closed");
         }

         long id = allocateMessageId();
         Entry entry = new Entry(id, payloadCopy, tagsCopy);
         append(entry);
         occupancy += payloadCopy.length;
         notifyAll();
         return id;
     }
 }

 public Message takeMatching(String tag) throws InterruptedException {
     if (tag == null) {
         throw new NullPointerException("tag");
     }

     synchronized (this) {
         while (true) {
             Entry previous = null;
             Entry current = head;
             while (current != null && !hasTag(current.tags, tag)) {
                 previous = current;
                 current = current.next;
             }

             if (current != null) {
                 if (previous == null) {
                     head = current.next;
                 } else {
                     previous.next = current.next;
                 }
                 if (tail == current) {
                     tail = previous;
                 }
                 current.next = null;
                 occupancy -= current.length();
                 notifyAll();
                 return new Message(current.id, current.payload, current.tags);
             }

             if (closed) {
                 return null;
             }
             wait();
         }
     }
 }

 public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
     if (destination == null) {
         throw new NullPointerException("destination");
     }
     if (tag == null) {
         throw new NullPointerException("tag");
     }
     if (destination == this) {
         throw new IllegalArgumentException("Destination must be a different buffer");
     }

     TaggedCapacityBuffer first = lockOrder < destination.lockOrder ? this : destination;
     TaggedCapacityBuffer second = first == this ? destination : this;

     synchronized (first) {
         synchronized (second) {
             if (closed || destination.closed) {
                 return false;
             }

             Entry previous = null;
             Entry current = head;
             while (current != null && !hasTag(current.tags, tag)) {
                 previous = current;
                 current = current.next;
             }
             if (current == null || destination.occupancy > destination.capacity - current.length())
{
                 return false;
             }

             if (previous == null) {
                 head = current.next;
             } else {
                 previous.next = current.next;
             }
             if (tail == current) {
                 tail = previous;
             }

             current.next = null;
             occupancy -= current.length();
             destination.append(current);
             destination.occupancy += current.length();

             notifyAll();
             destination.notifyAll();
             return true;
         }
     }
 }

 public void close() {
     synchronized (this) {
         if (!closed) {
             closed = true;
             notifyAll();
         }
     }
 }

 public Snapshot snapshot() {
     synchronized (this) {
         int count = 0;
         for (Entry current = head; current != null; current = current.next) {
             count++;
         }

         Message[] messages = new Message[count];
         int index = 0;
         for (Entry current = head; current != null; current = current.next) {
             messages[index++] = new Message(current.id, current.payload, current.tags);
         }
         return new Snapshot(messages, occupancy, capacity, closed);
     }
 }

 private static void validateInputs(byte[] payload, String[] tags) {
     if (payload == null) {
         throw new NullPointerException("payload");
     }
     if (tags == null) {
         throw new NullPointerException("tags");
     }
     if (payload.length == 0) {
         throw new IllegalArgumentException("Payload must not be empty");
     }
     validateTags(tags);
 }

 private static void validateTags(String[] tags) {
     if (tags == null) {
         throw new NullPointerException("tags");
     }
     for (int i = 0; i < tags.length; i++) {
         if (tags[i] == null) {
             throw new NullPointerException("Tag must not be null");
         }
     }
 }

 private static boolean hasTag(String[] tags, String tag) {
     for (int i = 0; i < tags.length; i++) {
         if (tags[i].equals(tag)) {
             return true;
         }
     }
     return false;
 }

 private static boolean sharesAnyTag(String[] tags, String[] candidates) {
     for (int i = 0; i < tags.length; i++) {
         for (int j = 0; j < candidates.length; j++) {
             if (tags[i].equals(candidates[j])) {
                 return true;
             }
         }
     }
     return false;
 }

 private Entry removeOldestSharingAny(String[] tags) {
     Entry previous = null;
     Entry current = head;
     while (current != null && !sharesAnyTag(current.tags, tags)) {
         previous = current;
         current = current.next;
     }
     if (current == null) {
         return null;
     }

     if (previous == null) {
         head = current.next;
     } else {
         previous.next = current.next;
     }
     if (tail == current) {
         tail = previous;
     }
     current.next = null;
     occupancy -= current.length();
     return current;
 }

 private void removeHead() {
     Entry removed = head;
     head = removed.next;
     if (head == null) {
         tail = null;
     }
     removed.next = null;
     occupancy -= removed.length();
 }

 private void append(Entry entry) {
     entry.next = null;
     if (tail == null) {
         head = entry;
     } else {
         tail.next = entry;
     }
     tail = entry;
 }

 private static long allocateMessageId() {
     synchronized (TaggedCapacityBuffer.class) {
         if (nextMessageId <= 0) {
             throw new IllegalStateException("Message ID space exhausted");
         }
         long id = nextMessageId;
         nextMessageId = nextMessageId == Long.MAX_VALUE ? 0 : nextMessageId + 1;
         return id;
     }
 }

}
