import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@UserSpaceProvisioner.ThreadSafe
class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private final String[] registeredUsers;
    private final String[][] pendingConfigurations;
    private final UserState userStates;
    private final AtomicInteger generationCounter;

    public UserSpaceProvisioner() {
        registeredUsers = new String[100]; // Fixed size for simplicity
        pendingConfigurations = new String[100][];
        userStates = new UserState(4); // PENDING, INSTALLING, ACTIVE, FAILED
        generationCounter = new AtomicInteger();
    }

    public String[] register(String user, String[] configuration) {
        synchronized (this) {
            if (user == null || user.isEmpty() || configuration == null || Arrays.asList(configuration).contains(null)) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }
            if (Arrays.asList(registeredUsers).contains(user)) {
                throw new IllegalStateException("User already registered");
            }
            int index = Arrays.asList(registeredUsers).indexOf(null);
            if (index == -1) {
                throw new IllegalStateException("No available slots for registration");
            }
            registeredUsers[index] = user;
            pendingConfigurations[index] = Arrays.copyOf(configuration, configuration.length);
            return new String[]{user, null}; // Return user and initial manifest
        }
    }

    public void activate(String user) throws InterruptedException {
        int index = Arrays.asList(registeredUsers).indexOf(user);
        if (index == -1) {
            throw new IllegalStateException("User not registered");
        }
        int generation = generationCounter.get();
        synchronized (this) {
            while (userStates.getState(index) != UserState.PENDING) {
                wait();
            }
            userStates.setState(index, UserState.INSTALLING);
        }
        try {
            doInstall(user, pendingConfigurations[index]);
            synchronized (this) {
                userStates.setState(index, UserState.ACTIVE);
                notifyAll();
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                userStates.setState(index, UserState.FAILED);
                notifyAll();
            }
            throw e;
        }
    }

    private void doInstall(String user, String[] configuration) {
        // Simulate installation
    }

    public void reinstall(String user, String[] configuration) throws InterruptedException {
        int index = Arrays.asList(registeredUsers).indexOf(user);
        if (index == -1) {
            throw new IllegalStateException("User not registered");
        }
        int generation = generationCounter.incrementAndGet();
        synchronized (this) {
            while (userStates.getState(index) != UserState.PENDING) {
                wait();
            }
            userStates.setState(index, UserState.INSTALLING);
        }
        try {
            doInstall(user, configuration);
            synchronized (this) {
                userStates.setState(index, UserState.ACTIVE);
                notifyAll();
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                userStates.setState(index, UserState.FAILED);
                notifyAll();
            }
            throw e;
        }
    }

    public String[] verify(String user) {
        int index = Arrays.asList(registeredUsers).indexOf(user);
        if (index == -1) {
            throw new IllegalStateException("User not registered");
        }
        synchronized (this) {
            while (userStates.getState(index) != UserState.ACTIVE) {
                wait();
            }
        }
        return Arrays.copyOf(pendingConfigurations[index], pendingConfigurations[index].length);
    }

    public int[] snapshotMetrics() {
        return new int[4]; // Placeholder for actual metrics
    }

    enum UserState {
        PENDING, INSTALLING, ACTIVE, FAILED;
    }
}
