```java
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VerifiableMailbox {
    private final int capacity;
    private final List<Message> messages;
    private long nextId = 1;
    private Set<String> strongValidators;
    private Set<String> weakValidators;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.messages = new ArrayList<>();
        this.strongValidators = new HashSet<>();
        this.weakValidators = new HashSet<>();
    }

    public synchronized long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0 || content.length > capacity) {
            return -1;
        }

        String validator = draft ? "W-" + nextId : "S-" + nextId;
        long id = nextId++;

        byte[] copiedContent = content.clone();
        messages.add(new Message(id, validator, copiedContent));

        strongValidators.add(validator);
        weakValidators.add(validator);

        while (getOccupancy() > capacity) {
            prune(messages.get(0).id);
        }

        return id;
    }

    public synchronized Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0 || validator == null) {
            return null;
        }

        for (Message msg : messages) {
            if (msg.id == messageId && validator.equals(msg.validator)) {
                return new Message(msg.id, msg.validator, msg.content.clone());
            }
        }

        return null;
    }

    public synchronized long prune(long throughId) {
        if (throughId < 0) {
            return 0;
        }

        int removed = 0;
        while (!messages.isEmpty() && messages.get(0).id <= throughId) {
            Message msg = messages.remove(0);
            strongValidators.remove(msg.validator);
            weakValidators.remove(msg.validator);
            removed++;
        }

        return removed;
    }

    public synchronized MailboxHeader snapshotHeader() {
        List<Long> orderedIds = new ArrayList<>();
        for (Message msg : messages) {
            orderedIds.add(msg.id);
        }

        return new MailboxHeader(capacity, getOccupancy(), orderedIds,
                new HashSet<>(strongValidators), new HashSet<>(weakValidators));
    }

    private synchronized int getOccupancy() {
        int total = 0;
        for (Message msg : messages) {
            total += msg.content.length;
        }
        return total;
    }

    private static class Message {
        final long id;
        final String validator;
        final byte[] content;

        public Message(long id, String validator, byte[] content) {
            this.id = id;
            this.validator = validator;
            this.content = content;
        }
    }

    public static class MailboxHeader {
        final int capacity;
        final int occupancy;
        final List<Long> orderedIds;
        final Set<String> strongValidators;
        final Set<String> weakValidators;

        public MailboxHeader(int capacity, int occupancy, List<Long> orderedIds,
                             Set<String> strongValidators, Set<String> weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.orderedIds = new ArrayList<>(orderedIds);
            this.strongValidators = new HashSet<>(strongValidators);
            this.weakValidators = new HashSet<>(weakValidators);
        }

        @Override
        public String toString() {
            return "MailboxHeader{" +
                    "capacity=" + capacity +
                    ", occupancy=" + occupancy +
                    ", orderedIds=" + orderedIds +
                    ", strongValidators=" + strongValidators +
                    ", weakValidators=" + weakValidators +
                    '}';
        }
    }
}
```
