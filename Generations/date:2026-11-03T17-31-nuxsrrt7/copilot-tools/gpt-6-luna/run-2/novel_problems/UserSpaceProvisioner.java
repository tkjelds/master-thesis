@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum UserState {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public static final class UserSnapshot {
        public final UserState status;
        public final long generation;
        public final String[] manifest;

        private UserSnapshot(UserState status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = copy(manifest);
        }

        public String[] getManifest() {
            return copy(manifest);
        }
    }

    public static final class MetricsSnapshot {
        public final int pendingCount;
        public final int installingCount;
        public final int activeCount;
        public final int failedCount;
        public final int totalUsers;

        private MetricsSnapshot(int pendingCount, int installingCount,
                                int activeCount, int failedCount, int totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }
    }

    private static final class User {
        private final String name;
        private String[] configuration;
        private UserState state = UserState.PENDING;
        private long generation;
        private String[] manifest;
        private Throwable failure;
        private CallbackFrame callbacks;

        private User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private static final class CallbackFrame {
        private final Thread thread;
        private CallbackFrame next;

        private CallbackFrame(Thread thread, CallbackFrame next) {
            this.thread = thread;
            this.next = next;
        }
    }

    private final Object gate = new Object();
    private final Installer installer;
    private User[] users = new User[8];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        validateConfiguration(configuration);
        String[] copiedConfiguration = copy(configuration);

        synchronized (gate) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            ensureCapacity();
            users[userCount++] = new User(user, copiedConfiguration);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);

        User target;
        long generation;
        String[] configuration;
        CallbackFrame frame;

        synchronized (gate) {
            target = requireUser(user);
            rejectRecursiveCallback(target);

            if (target.state == UserState.ACTIVE) {
                return copy(target.manifest);
            }
            if (target.state == UserState.FAILED) {
                rethrow(target.failure);
            }
            if (target.state == UserState.INSTALLING) {
                long joinedGeneration = target.generation;
                while (target.generation == joinedGeneration
                        && target.state == UserState.INSTALLING) {
                    gate.wait();
                }
                if (target.generation != joinedGeneration) {
                    throw superseded(user, joinedGeneration);
                }
                if (target.state == UserState.ACTIVE) {
                    return copy(target.manifest);
                }
                if (target.state == UserState.FAILED) {
                    rethrow(target.failure);
                }
                throw new IllegalStateException("Installation attempt ended unexpectedly");
            }

            generation = target.generation;
            configuration = copy(target.configuration);
            target.state = UserState.INSTALLING;
            frame = addCallback(target);
        }

        return performInstallation(target, generation, configuration, frame);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        validateConfiguration(configuration);
        String[] copiedConfiguration = copy(configuration);

        User target;
        long generation;
        CallbackFrame frame;

        synchronized (gate) {
            target = requireUser(user);
            rejectRecursiveCallback(target);

            if (target.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow: " + user);
            }

            generation = target.generation + 1;
            target.generation = generation;
            target.configuration = copiedConfiguration;
            target.manifest = null;
            target.failure = null;
            target.state = UserState.INSTALLING;
            frame = addCallback(target);
            gate.notifyAll();
        }

        return performInstallation(target, generation, copy(copiedConfiguration), frame);
    }

    public UserSnapshot verify(String user) {
        validateUser(user);

        synchronized (gate) {
            User target = requireUser(user);
            return new UserSnapshot(target.state, target.generation, target.manifest);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (gate) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;

            for (int i = 0; i < userCount; i++) {
                switch (users[i].state) {
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

            return new MetricsSnapshot(pending, installing, active, failed, userCount);
        }
    }

    private String[] performInstallation(User user, long generation,
                                         String[] configuration, CallbackFrame frame) {
        String[] manifest = null;
        Throwable failure = null;

        try {
            String[] installed = installer.install(user.name, configuration);
            if (installed == null) {
                failure = new IllegalStateException("Installer returned a null manifest");
            } else {
                manifest = copy(installed);
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            failure = error;
        }

        synchronized (gate) {
            removeCallback(user, frame);

            if (user.generation != generation || user.state != UserState.INSTALLING) {
                gate.notifyAll();
                throw superseded(user.name, generation);
            }

            if (failure != null) {
                user.manifest = null;
                user.failure = failure;
                user.state = UserState.FAILED;
                gate.notifyAll();
                rethrow(failure);
            }

            user.manifest = manifest;
            user.failure = null;
            user.state = UserState.ACTIVE;
            gate.notifyAll();
            return copy(manifest);
        }
    }

    private User findUser(String name) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(name)) {
                return users[i];
            }
        }
        return null;
    }

    private User requireUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }

    private void ensureCapacity() {
        if (userCount < users.length) {
            return;
        }

        int newLength = users.length <= Integer.MAX_VALUE / 2
                ? users.length * 2 : Integer.MAX_VALUE;
        if (newLength <= userCount) {
            throw new IllegalStateException("Too many registered users");
        }

        User[] expanded = new User[newLength];
        for (int i = 0; i < userCount; i++) {
            expanded[i] = users[i];
        }
        users = expanded;
    }

    private CallbackFrame addCallback(User user) {
        CallbackFrame frame = new CallbackFrame(Thread.currentThread(), user.callbacks);
        user.callbacks = frame;
        return frame;
    }

    private void removeCallback(User user, CallbackFrame frame) {
        CallbackFrame previous = null;
        CallbackFrame current = user.callbacks;
        while (current != null) {
            if (current == frame) {
                if (previous == null) {
                    user.callbacks = current.next;
                } else {
                    previous.next = current.next;
                }
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private void rejectRecursiveCallback(User user) {
        Thread currentThread = Thread.currentThread();
        for (CallbackFrame frame = user.callbacks; frame != null; frame = frame.next) {
            if (frame.thread == currentThread) {
                throw new IllegalStateException(
                        "Installer callbacks cannot activate or reinstall their own user");
            }
        }
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must not be null or empty");
        }
    }

    private static void validateConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("Configuration entries must not be null");
            }
        }
    }

    private static String[] copy(String[] values) {
        return values == null ? null : values.clone();
    }

    private static IllegalStateException superseded(String user, long generation) {
        return new IllegalStateException(
                "Installation attempt for user " + user
                        + " at generation " + generation + " was superseded");
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Installation failed", failure);
    }
}
