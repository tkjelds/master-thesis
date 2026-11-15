@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    public static final int MAX_HEALTH = 100;

    private static final int MAX_INT = 2147483647;
    private static final long MAX_LONG = 9223372036854775807L;
    private static final Object ID_MONITOR = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final Object monitor = new Object();
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

        this.id = allocateId();
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
    }

    private static long allocateId() {
        synchronized (ID_MONITOR) {
            if (idsExhausted) {
                throw new IllegalStateException("Character IDs exhausted");
            }

            long result = nextId;
            if (nextId == MAX_LONG) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return result;
        }
    }

    /**
     * Waits only for intrinsic monitors, always acquired in increasing ID order.
     * Arithmetic failure leaves both characters unchanged.
     */
    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("target");
        }
        if (other == this) {
            throw new IllegalArgumentException("Self-attacks are not allowed");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first.monitor) {
            synchronized (second.monitor) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0, false, snapshotLocked(), other.snapshotLocked());
                }

                int damage = attackPower > other.defense
                        ? attackPower - other.defense : 0;
                int appliedDamage = damage < other.health ? damage : other.health;
                int remainingHealth = other.health - appliedDamage;
                boolean causedDeath = remainingHealth == 0;

                // Validate the entire reward before committing any damage.
                Progression reward = causedDeath
                        ? prepareExperienceLocked(10L) : null;

                other.health = remainingHealth;
                if (reward != null) {
                    applyProgressionLocked(reward);
                }

                return new EncounterResult(
                        appliedDamage, causedDeath,
                        snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (monitor) {
            if (health != 0) {
                health = health > MAX_HEALTH - 10 ? MAX_HEALTH : health + 10;
            }
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (monitor) {
            if (health == 0) {
                throw new IllegalStateException(
                        "Dead characters cannot gain experience");
            }
            if (amount < 0) {
                throw new IllegalArgumentException(
                        "Experience amount must be nonnegative");
            }
            applyProgressionLocked(prepareExperienceLocked(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    private Progression prepareExperienceLocked(long amount) {
        if (amount > MAX_LONG - experience) {
            throw new ArithmeticException("Experience overflow");
        }

        long total = experience + amount;
        long levelsGained = total / 100L;

        if (levelsGained > MAX_INT - level) {
            throw new ArithmeticException("Level overflow");
        }
        if (levelsGained > (MAX_INT - attackPower) / 2L) {
            throw new ArithmeticException("Attack power overflow");
        }
        if (levelsGained > MAX_INT - defense) {
            throw new ArithmeticException("Defense overflow");
        }

        return new Progression(
                level + (int) levelsGained,
                attackPower + (int) (levelsGained * 2L),
                defense + (int) levelsGained,
                total % 100L);
    }

    private void applyProgressionLocked(Progression progression) {
        level = progression.level;
        attackPower = progression.attackPower;
        defense = progression.defense;
        experience = progression.experience;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience);
    }

    private static final class Progression {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private Progression(
                int level, int attackPower, int defense, long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }

    public static final class Snapshot {
        private final String name;
        private final long id;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        private Snapshot(
                String name, long id, int health, int attackPower,
                int defense, int level, long experience) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = health > 0;
        }

        public String getName() {
            return name;
        }

        public long getId() {
            return id;
        }

        public int getHealth() {
            return health;
        }

        public int getAttackPower() {
            return attackPower;
        }

        public int getDefense() {
            return defense;
        }

        public int getLevel() {
            return level;
        }

        public long getExperience() {
            return experience;
        }

        public boolean isAlive() {
            return alive;
        }
    }

    public static final class EncounterResult {
        private final int appliedDamage;
        private final boolean causedDeath;
        private final Snapshot attackerSnapshot;
        private final Snapshot targetSnapshot;

        private EncounterResult(
                int appliedDamage, boolean causedDeath,
                Snapshot attackerSnapshot, Snapshot targetSnapshot) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getAppliedDamage() {
            return appliedDamage;
        }

        public boolean causedDeath() {
            return causedDeath;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }
}
