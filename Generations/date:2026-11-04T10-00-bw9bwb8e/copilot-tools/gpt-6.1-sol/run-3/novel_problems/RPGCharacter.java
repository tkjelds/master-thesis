@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    public static final int MAX_HEALTH = 100;

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final Object lock = new Object();
    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private int level = 1;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("Health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException(
                    "Attack power and defense must be nonnegative");
        }

        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.id = allocateId();
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("Character IDs exhausted");
            }

            long result = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return result;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("target");
        }
        if (other == this) {
            throw new IllegalArgumentException("Self-attacks are not allowed");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0, false, snapshotLocked(), other.snapshotLocked());
                }

                int damage = attackPower > other.defense
                        ? attackPower - other.defense
                        : 0;
                if (damage > other.health) {
                    damage = other.health;
                }

                boolean causedDeath = damage == other.health;

                // Validate the reward before committing any damage.
                Progress reward = causedDeath ? planExperienceLocked(10L) : null;

                other.health -= damage;
                if (reward != null) {
                    applyProgressLocked(reward);
                }

                return new EncounterResult(
                        damage, causedDeath,
                        snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (lock) {
            if (health != 0) {
                int missing = MAX_HEALTH - health;
                health += missing < 10 ? missing : 10;
            }
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException(
                    "Experience amount must be nonnegative");
        }

        synchronized (lock) {
            if (health == 0) {
                throw new IllegalStateException(
                        "A dead character cannot gain experience");
            }

            Progress progress = planExperienceLocked(amount);
            applyProgressLocked(progress);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private Progress planExperienceLocked(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("Experience overflow");
        }

        long total = experience + amount;
        long levelsGained = total / 100L;

        if (levelsGained > Integer.MAX_VALUE - level) {
            throw new ArithmeticException("Level overflow");
        }
        if (levelsGained > (Integer.MAX_VALUE - attackPower) / 2L) {
            throw new ArithmeticException("Attack power overflow");
        }
        if (levelsGained > Integer.MAX_VALUE - defense) {
            throw new ArithmeticException("Defense overflow");
        }

        return new Progress(
                level + (int) levelsGained,
                attackPower + (int) (levelsGained * 2L),
                defense + (int) levelsGained,
                total % 100L);
    }

    private void applyProgressLocked(Progress progress) {
        level = progress.level;
        attackPower = progress.attackPower;
        defense = progress.defense;
        experience = progress.experience;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience);
    }

    private static final class Progress {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private Progress(
                int level, int attackPower, int defense, long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        private Snapshot(
                String name,
                long id,
                int health,
                int attackPower,
                int defense,
                int level,
                long experience) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = health > 0;
        }
    }

    public static final class EncounterResult {
        public final int appliedDamage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int appliedDamage,
                boolean causedDeath,
                Snapshot attackerSnapshot,
                Snapshot targetSnapshot) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }
    }
}
