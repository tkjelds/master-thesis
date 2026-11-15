@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public static final class UserSnapshot {
        public final State status;
        public final long generation;
        public final String[] manifest;

        private UserSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = copyArray(manifest);
        }

        public State getStatus() {
            return status;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copyArray(manifest);
        }
    }

    public static final class MetricsSnapshot {
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int totalUsers;

        private MetricsSnapshot(int pending, int installing, int active,
                                int failed, int totalUsers) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = totalUsers;
        }

        public int getPending() {
            return pending;
        }

        public int getInstalling() {
            return installing;
        }

        public int getActive() {
            return active;
        }

        public int getFailed() {
            return failed;
        }

        public int getTotalUsers() {
            return totalUsers;
        }
    }

    private static final class UserRecord {
        private final String name;
        private String[] configuration;
        private State state;
        private long generation;
        private String[] manifest;
        private Throwable failure;
        private UserRecord next;

        private UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.state = State.PENDING;
            this.generation = 0L;
        }
    }

    private static final class CallbackFrame {
        private final Thread thread;
        private final UserRecord user;
        private CallbackFrame next;

        private CallbackFrame(Thread thread, UserRecord user) {
            this.thread = thread;
            this.user = user;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private UserRecord users;
    private CallbackFrame callbacks;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            UserRecord record = new UserRecord(user, copiedConfiguration);
            record.next = users;
            users = record;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);

        UserRecord record;
        long generation;
        String[] configuration;

        synchronized (monitor) {
            record = requireUser(user);
            rejectRecursiveCallback(record);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == State.INSTALLING) {
                generation = record.generation;
                while (record.generation == generation
                        && record.state == State.INSTALLING) {
                    monitor.wait();
                }
                if (record.generation != generation) {
                    throw superseded(user);
                }
                if (record.state == State.ACTIVE) {
                    return copyArray(record.manifest);
                }
                if (record.state == State.FAILED) {
                    throwFailure(record.failure);
                }
                throw new IllegalStateException("Installation ended in an invalid state");
            }

            generation = record.generation;
            record.state = State.INSTALLING;
            record.manifest = null;
            record.failure = null;
            configuration = copyArray(record.configuration);
        }

        return runInstallation(record, generation, configuration);
    }

    public String[] reinstall(String user, String[] configuration)
            throws InterruptedException {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        UserRecord record;
        long generation;
        synchronized (monitor) {
            record = requireUser(user);
            rejectRecursiveCallback(record);

            if (record.generation == Long.MAX_VALUE) {
                throw new ArithmeticException("User generation overflow: " + user);
            }

            generation = record.generation + 1L;
            record.configuration = copiedConfiguration;
            record.generation = generation;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;
            monitor.notifyAll();
        }

        return runInstallation(record, generation, copyArray(copiedConfiguration));
    }

    public UserSnapshot verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            UserRecord record = requireUser(user);
            return new UserSnapshot(record.state, record.generation,
                    record.state == State.ACTIVE ? record.manifest : null);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (monitor) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            int total = 0;

            for (UserRecord record = users; record != null; record = record.next) {
                total++;
                switch (record.state) {
                    case PENDING:
                        pending++;
                        break;
                    case INSTALLING:
                        installing++;
                        break;
                    case ACTIVE:
                        active++;
                        break;
                    case FAILED:
                        failed++;
                        break;
                    default:
                        throw new IllegalStateException("Unknown user state");
                }
            }

            return new MetricsSnapshot(pending, installing, active, failed, total);
        }
    }

    private String[] runInstallation(UserRecord record, long generation,
                                     String[] configuration) {
        CallbackFrame frame = new CallbackFrame(Thread.currentThread(), record);
        synchronized (monitor) {
            frame.next = callbacks;
            callbacks = frame;
        }

        String[] manifest = null;
        Throwable failure = null;
        try {
            String[] installed = installer.install(record.name, configuration);
            if (installed == null) {
                failure = new IllegalStateException("Installer returned a null manifest");
            } else {
                manifest = copyArray(installed);
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            failure = error;
        } finally {
            synchronized (monitor) {
                removeCallbackFrame(frame);
            }
        }

        synchronized (monitor) {
            if (record.generation != generation) {
                monitor.notifyAll();
                throw superseded(record.name);
            }
            if (record.state != State.INSTALLING) {
                throw new IllegalStateException("Installation ended in an invalid state");
            }
            if (failure != null) {
                record.failure = failure;
                record.manifest = null;
                record.state = State.FAILED;
                monitor.notifyAll();
                throwFailure(failure);
            }

            record.manifest = manifest;
            record.failure = null;
            record.state = State.ACTIVE;
            monitor.notifyAll();
            return copyArray(manifest);
        }
    }

    private void rejectRecursiveCallback(UserRecord user) {
        Thread current = Thread.currentThread();
        for (CallbackFrame frame = callbacks; frame != null; frame = frame.next) {
            if (frame.thread == current && frame.user == user) {
                throw new IllegalStateException(
                        "Installer callbacks may not activate or reinstall their own user");
            }
        }
    }

    private void removeCallbackFrame(CallbackFrame target) {
        CallbackFrame previous = null;
        CallbackFrame current = callbacks;
        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    callbacks = current.next;
                } else {
                    previous.next = current.next;
                }
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private UserRecord findUser(String user) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.name.equals(user)) {
                return record;
            }
        }
        return null;
    }

    private UserRecord requireUser(String user) {
        UserRecord record = findUser(user);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        return record;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = copyArray(configuration);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
        }
        return copy;
    }

    private static String[] copyArray(String[] source) {
        if (source == null) {
            return null;
        }
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static IllegalStateException superseded(String user) {
        return new IllegalStateException(
                "Installation for user '" + user + "' was superseded");
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Installation failed");
    }
}
