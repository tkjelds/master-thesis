@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final String name;
    private final long id;

    // All mutable character state is guarded by this character's monitor.
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attack power and defense must be nonnegative");
        }

        this.name = name;
        this.id = allocateId();
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("character IDs are exhausted");
            }

            long allocated = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return allocated;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0,
                            false,
                            snapshotWhileLocked(),
                            other.snapshotWhileLocked());
                }

                long rawDamage = (long) attackPower - (long) other.defense;
                int damage = rawDamage <= 0L
                        ? 0
                        : (int) Math.min(rawDamage, (long) other.health);
                boolean causedDeath = damage == other.health;

                Progress newProgress = null;
                if (causedDeath) {
                    // Validate the reward completely before committing damage.
                    newProgress = calculateProgress(10L);
                }

                other.health -= damage;
                if (causedDeath) {
                    applyProgress(newProgress);
                }

                return new EncounterResult(
                        damage,
                        causedDeath,
                        snapshotWhileLocked(),
                        other.snapshotWhileLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = Math.min(100, health + 10);
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("a dead character cannot gain experience");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException("experience amount must be nonnegative");
            }

            Progress progress = calculateProgress(amount);
            applyProgress(progress);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotWhileLocked();
        }
    }

    private Snapshot snapshotWhileLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience, health > 0);
    }

    private Progress calculateProgress(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }

        long totalExperience = experience + amount;
        long levelsGained = totalExperience / 100L;
        long newLevel = (long) level + levelsGained;
        long newAttackPower = (long) attackPower + levelsGained * 2L;
        long newDefense = (long) defense + levelsGained;

        if (newLevel > Integer.MAX_VALUE
                || newAttackPower > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("level or combat statistic overflow");
        }

        return new Progress(
                (int) newLevel,
                (int) newAttackPower,
                (int) newDefense,
                totalExperience % 100L);
    }

    private void applyProgress(Progress progress) {
        level = progress.level;
        attackPower = progress.attackPower;
        defense = progress.defense;
        experience = progress.experience;
    }

    private static final class Progress {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private Progress(int level, int attackPower, int defense, long experience) {
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
                long experience,
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
