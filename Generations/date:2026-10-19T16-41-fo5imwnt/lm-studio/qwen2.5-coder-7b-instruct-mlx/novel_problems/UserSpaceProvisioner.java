
@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private interface Installer {
        String[] install(String user, String[] configuration);
    }

    private static final class User {
        private final String name;
        private volatile String[] configuration;
        private int generation = 0;
        private volatile State state = State.PENDING;
        private volatile String[] manifest;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration.clone();
        }

        User(String name) {
            this.name = name;
        }

        boolean isPending() {
            return state == State.PENDING;
        }

        boolean isInstalling() {
            return state == State.INSTALLING;
        }

        boolean isActive() {
            return state == State.ACTIVE;
        }

        boolean isFailed() {
            return state == State.FAILED;
        }

        synchronized void activate(Installer installer) throws InterruptedException {
            while (state != State.PENDING && state != State.INSTALLING) {
                wait();
            }
            if (state == State.PENDING) {
                state = State.INSTALLING;
                try {
                    manifest = installer.install(name, configuration.clone());
                    state = State.ACTIVE;
                    notifyAll();
                } catch (RuntimeException e) {
                    state = State.FAILED;
                    manifest = null;
                    notifyAll();
                    throw e;
                }
            }
        }

        synchronized void reinstall(String[] newConfiguration) {
            while (state == State.INSTALLING) {
                notifyAll();
            }
            state = State.PENDING;
            configuration = newConfiguration.clone();
            generation++;
        }

        synchronized String[] verify() {
            return manifest;
        }

        synchronized void snapshotMetrics(Counts counts) {
            switch (state) {
                case PENDING:
                    counts.pending++;
                    break;
                case INSTALLING:
                    counts.installing++;
                    break;
                case ACTIVE:
                    counts.active++;
                    break;
                case FAILED:
                    counts.failed++;
                    break;
            }
        }

        private enum State {
            PENDING, INSTALLING, ACTIVE, FAILED
        }
    }

    private final Object mutex = new Object();
    private final User[] users;
    private volatile int nextUserIndex = 0;

    public UserSpaceProvisioner(int capacity) {
        users = new User[capacity];
    }

    public synchronized void register(String user, String[] configuration) throws IllegalArgumentException {
        if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
            throw new IllegalArgumentException("Invalid user or configuration");
        }
        for (User u : users) {
            if (u != null && u.name.equals(user)) {
                throw new IllegalArgumentException("Duplicate user");
            }
        }
        users[nextUserIndex % users.length] = new User(user, configuration);
        nextUserIndex++;
    }

    public synchronized void activate(String user) throws IllegalArgumentException, InterruptedException {
        for (User u : users) {
            if (u != null && u.name.equals(user)) {
                u.activate(this::installer);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown user");
    }

    public synchronized void reinstall(String user, String[] configuration) throws IllegalArgumentException {
        for (User u : users) {
            if (u != null && u.name.equals(user)) {
                u.reinstall(configuration);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown user");
    }

    public synchronized String[] verify(String user) throws IllegalArgumentException {
        for (User u : users) {
            if (u != null && u.name.equals(user)) {
                return u.verify();
            }
        }
        throw new IllegalArgumentException("Unknown user");
    }

    public synchronized void snapshotMetrics(Counts counts) {
        for (User u : users) {
            if (u != null) {
                u.snapshotMetrics(counts);
            }
        }
    }

    private String[] installer(String user, String[] configuration) {
        try {
            Thread.sleep(1000); // Simulate installation
            return configuration.clone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Installation interrupted");
        }
    }

    public static class Counts {
        int pending = 0;
        int installing = 0;
        int active = 0;
        int failed = 0;
    }
}
