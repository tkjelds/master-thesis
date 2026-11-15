@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private static final String PENDING = "PENDING";
    private static final String INSTALLING = "INSTALLING";
    private static final String ACTIVE = "ACTIVE";
    private static final String FAILED = "FAILED";

    private static class UserState {
        volatile String status;
        volatile long generation;
        volatile String[] manifest;
        volatile RuntimeException failure;

        UserState(String status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = manifest == null ? new String[0] : manifest.clone();
        }
    }

    private final UserState[] states;
    private volatile long nextGeneration = 0;

    public UserSpaceProvisioner() {
        states = new UserState[16]; // Initial capacity
    }

    public interface Installer {
        String[] install(String user, String[] configuration);
        void register(String user, String[] configuration);
        void activate(String user);
        void reinstall(String user, String[] configuration);
        String verify(String user);
        void snapshotMetrics();
    }

    private final class UserSpaceProvisionerImpl implements Installer {
        @Override
        public String[] install(String user, String[] configuration) {
            UserState state = findUser(user);
            if (state == null || !PENDING.equals(state.status)) {
                throw new IllegalStateException("Invalid user state");
            }

            try {
                String[] manifest = installer.install(user, configuration);
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!PENDING.equals(state.status)) {
                        throw new IllegalStateException("Install superseded");
                    }
                    state.manifest = manifest == null ? new String[0] : manifest.clone();
                    state.status = ACTIVE;
                }
            } catch (RuntimeException e) {
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!PENDING.equals(state.status)) {
                        throw new IllegalStateException("Install superseded");
                    }
                    state.failure = e;
                    state.status = FAILED;
                }
            }

            return state.manifest.clone();
        }

        @Override
        public void register(String user, String[] configuration) {
            if (user == null || user.isEmpty() || configuration == null) {
                throw new IllegalArgumentException("Invalid arguments");
            }

            synchronized (UserSpaceProvisionerImpl.this) {
                if (findUser(user) != null) {
                    throw new IllegalArgumentException("Duplicate user");
                }

                long generation = nextGeneration++;
                UserState state = new UserState(PENDING, generation, configuration.clone());
                expandStatesIfNeeded(user);
                states[hash(user)] = state;
            }
        }

        @Override
        public void activate(String user) {
            UserState state = findUser(user);
            if (state == null || !PENDING.equals(state.status)) {
                throw new IllegalStateException("Invalid user state");
            }

            long generation = state.generation;
            String[] config = state.manifest.clone();

            synchronized (UserSpaceProvisionerImpl.this) {
                if (!PENDING.equals(state.status)) {
                    throw new IllegalStateException("Activation superseded");
                }

                state.status = INSTALLING;
            }

            try {
                String[] manifest = install(user, config);
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!INSTALLING.equals(state.status)) {
                        throw new IllegalStateException("Activation superseded");
                    }
                    state.manifest = manifest;
                    state.status = ACTIVE;
                }
            } catch (RuntimeException e) {
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!INSTALLING.equals(state.status)) {
                        throw new IllegalStateException("Activation superseded");
                    }
                    state.failure = e;
                    state.status = FAILED;
                }
            }
        }

        @Override
        public void reinstall(String user, String[] configuration) {
            UserState state = findUser(user);
            if (state == null || !ACTIVE.equals(state.status)) {
                throw new IllegalStateException("Invalid user state");
            }

            synchronized (UserSpaceProvisionerImpl.this) {
                if (!ACTIVE.equals(state.status)) {
                    throw new IllegalStateException("Invalid user state");
                }

                long generation = nextGeneration++;
                UserState newState = new UserState(INSTALLING, generation,
                    configuration == null ? new String[0] : configuration.clone());
                expandStatesIfNeeded(user);
                states[hash(user)] = newState;
            }

            try {
                String[] manifest = install(user, configuration);
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!INSTALLING.equals(newState.status)) {
                        throw new IllegalStateException("Reinstall superseded");
                    }
                    newState.manifest = manifest;
                    newState.status = ACTIVE;
                }
            } catch (RuntimeException e) {
                synchronized (UserSpaceProvisionerImpl.this) {
                    if (!INSTALLING.equals(newState.status)) {
                        throw new IllegalStateException("Reinstall superseded");
                    }
                    newState.failure = e;
                    newState.status = FAILED;
                }
            }
        }

        @Override
        public String verify(String user) {
            UserState state = findUser(user);
            if (state == null || !ACTIVE.equals(state.status)) {
                throw new IllegalStateException("Invalid user state");
            }

            return state.manifest.clone();
        }

        @Override
        public void snapshotMetrics() {
            // Implementation would count states and users
        }

        private UserState findUser(String user) {
            synchronized (UserSpaceProvisionerImpl.this) {
                return states[hash(user)];
            }
        }

        private void expandStatesIfNeeded(String user) {
            int index = hash(user);
            if (index >= states.length) {
                UserState[] newStates = new UserState[states.length * 2];
                System.arraycopy(states, 0, newStates, 0, states.length);
                states = newStates;
            }
        }

        private int hash(String user) {
            return Math.abs(user.hashCode()) % states.length;
        }
    }

    private final Installer installer = new UserSpaceProvisionerImpl();
}
