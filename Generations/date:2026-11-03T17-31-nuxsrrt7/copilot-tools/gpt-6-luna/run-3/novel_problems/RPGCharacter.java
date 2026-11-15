@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private int experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be between 1 and 100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack power and defense must be nonnegative");
        }

        this.name = name;
        this.id = allocateId();
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("No character IDs remain");
            }

            long allocatedId = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return allocatedId;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("Target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("A character cannot attack itself");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = this.id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (this.health == 0 || other.health == 0) {
                    return new EncounterResult(0, false, snapshotLocked(), other.snapshotLocked());
                }

                int damage = this.attackPower > other.defense
                        ? this.attackPower - other.defense
                        : 0;
                int resultingHealth = damage >= other.health ? 0 : other.health - damage;
                boolean causedDeath = other.health > 0 && resultingHealth == 0;

                Progress attackerProgress = causedDeath ? calculateProgress(10L) : null;

                other.health = resultingHealth;
                if (causedDeath) {
                    applyProgress(attackerProgress);
                }

                return new EncounterResult(
                        damage >= other.health + damage ? other.health + damage : damage,
                        causedDeath,
                        snapshotLocked(),
                        other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = health >= 90 ? 100 : health + 10;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Experience amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
            }
            applyProgress(calculateProgress(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name,
                id,
                health,
                attackPower,
                defense,
                level,
                experience,
                health > 0);
    }

    private Progress calculateProgress(long amount) {
        long accumulatedRemainder = (long) experience + amount % 100L;
        long levelsGained = amount / 100L + accumulatedRemainder / 100L;
        long newLevel = (long) level + levelsGained;
        long newAttackPower = (long) attackPower + levelsGained * 2L;
        long newDefense = (long) defense + levelsGained;

        if (newLevel > Integer.MAX_VALUE
                || newAttackPower > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("Experience would overflow character stats");
        }

        return new Progress(
                (int) newLevel,
                (int) newAttackPower,
                (int) newDefense,
                (int) (accumulatedRemainder % 100L));
    }

    private void applyProgress(Progress progress) {
        level = progress.level;
        attackPower = progress.attackPower;
        defense = progress.defense;
        experience = progress.experience;
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final int experience;
        public final boolean alive;

        private Snapshot(
                String name,
                long id,
                int health,
                int attackPower,
                int defense,
                int level,
                int experience,
                boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }
    }

    public static final class EncounterResult {
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int damage,
                boolean causedDeath,
                Snapshot attackerSnapshot,
                Snapshot targetSnapshot) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }
    }

    private static final class Progress {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final int experience;

        private Progress(int level, int attackPower, int defense, int experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }
}
