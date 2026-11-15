import java.util.*;
import java.io.*;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private final Map<String, User> users = new HashMap<>();
    private final int MAX_GENERATION;

    public UserSpaceProvisioner(int maxGeneration) {
        this.MAX_GENERATION = maxGeneration;
    }

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    private static class User {
        volatile State state = State.PENDING;
        volatile int generation;
        volatile String[] manifest;

        User(int initialGeneration, String[] configuration) {
            this.generation = initialGeneration;
            this.manifest = Arrays.copyOf(configuration, configuration.length);
        }
    }

    private enum State {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public synchronized void register(String user, String[] configuration) throws IllegalArgumentException {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("Invalid username");
        if (configuration == null || configuration.length == 0) throw new IllegalArgumentException("Invalid configuration");

        if (users.containsKey(user)) throw new IllegalStateException("Duplicate user registration");

        users.put(user, new User(1, configuration));
    }

    public synchronized String[] activate(String user) throws IllegalStateException {
        User u = users.get(user);
        if (u == null || !u.state.equals(State.PENDING)) throw new IllegalStateException("Unknown user or not in PENDING state");

        synchronized (u) {
            if (!u.state.equals(State.PENDING)) return u.manifest;

            u.state = State.INSTALLING;
        }

        Installer installer = new Installer() {
            @Override
            public String[] install(String user, String[] configuration) {
                return Arrays.copyOf(configuration, configuration.length);
            }
        };

        try {
            String[] newManifest = installer.install(user, u.manifest);
            if (newManifest == null || newManifest.length == 0) throw new IllegalStateException("Invalid manifest");

            synchronized (u) {
                if (!u.state.equals(State.INSTALLING)) return u.manifest;

                u.state = State.ACTIVE;
                u.manifest = newManifest;
            }

            return newManifest;
        } catch (RuntimeException e) {
            synchronized (u) {
                if (!u.state.equals(State.INSTALLING)) return u.manifest;

                u.state = State.FAILED;
            }

            throw e;
        } finally {
            notifyAll();
        }
    }

    public synchronized void reinstall(String user, String[] configuration) throws IllegalStateException {
        User u = users.get(user);
        if (u == null || !u.state.equals(State.ACTIVE)) throw new IllegalStateException("Unknown user or not in ACTIVE state");

        int nextGeneration = u.generation + 1;
        if (nextGeneration > MAX_GENERATION) throw new IllegalStateException("Generation overflow");

        synchronized (u) {
            if (!u.state.equals(State.ACTIVE)) return;

            u.state = State.INSTALLING;
        }

        Installer installer = new Installer() {
            @Override
            public String[] install(String user, String[] configuration) {
                return Arrays.copyOf(configuration, configuration.length);
            }
        };

        try {
            String[] newManifest = installer.install(user, u.manifest);
            if (newManifest == null || newManifest.length == 0) throw new IllegalStateException("Invalid manifest");

            synchronized (u) {
                if (!u.state.equals(State.INSTALLING)) return;

                u.generation = nextGeneration;
                u.manifest = newManifest;
                u.state = State.ACTIVE;
            }

        } catch (RuntimeException e) {
            synchronized (u) {
                if (!u.state.equals(State.INSTALLING)) return;

                u.state = State.STARTED;
            }

            throw e;
        } finally {
            notifyAll();
        }
    }

    public synchronized String[] verify(String user) throws IllegalStateException {
        User u = users.get(user);
        if (u == null || (u.state != State.ACTIVE && u.state != State.FAILED)) throw new IllegalStateException("Unknown user or not in ACTIVE/FAILED state");

        return Arrays.copyOf(u.manifest, u.manifest.length);
    }

    public synchronized void snapshotMetrics(int[] stateCounts) {
        for (User u : users.values()) {
            switch (u.state) {
                case PENDING: stateCounts[0]++; break;
                case INSTALLING: stateCounts[1]++; break;
                case ACTIVE: stateCounts[2]++; break;
                case FAILED: stateCounts[3]++; break;
            }
        }

        stateCounts[4] = users.size();
    }
}
