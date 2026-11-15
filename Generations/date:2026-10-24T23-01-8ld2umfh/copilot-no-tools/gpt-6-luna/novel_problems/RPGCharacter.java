@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}

    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final String name;
    private final long id;
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.length() == 0) {
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
        synchronized (RPGCharacter.class) {
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
            throw new IllegalArgumentException("target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(0, false, snapshotLocked(), other.snapshotLocked());
                }

                int damage = attackPower > other.defense
                        ? attackPower - other.defense
                        : 0;
                int targetHealth = damage >= other.health
                        ? 0
                        : other.health - damage;
                boolean causedDeath = targetHealth == 0;

                Progress attackerProgress = causedDeath ? calculateProgress(10L) : null;
                Snapshot attackerSnapshot = causedDeath
                        ? new Snapshot(name, id, health, attackerProgress.attackPower,
                                attackerProgress.defense, attackerProgress.level,
                                attackerProgress.experience)
                        : snapshotLocked();
                Snapshot targetSnapshot = new Snapshot(other.name, other.id, targetHealth,
                        other.attackPower, other.defense, other.level, other.experience);
                EncounterResult result = new EncounterResult(
                        damage, causedDeath, attackerSnapshot, targetSnapshot);

                other.health = targetHealth;
                if (causedDeath) {
                    attackPower = attackerProgress.attackPower;
                    defense = attackerProgress.defense;
                    level = attackerProgress.level;
                    experience = attackerProgress.experience;
                }
                return result;
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
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("a dead character cannot gain experience");
            }

            Progress progress = calculateProgress(amount);
            attackPower = progress.attackPower;
            defense = progress.defense;
            level = progress.level;
            experience = progress.experience;
        }
    }

    private Progress calculateProgress(long amount) {
        long levelsGained = amount / 100L
                + (amount % 100L + experience) / 100L;
        int remainingExperience = (int) ((amount % 100L + experience) % 100L);

        if (levelsGained > Integer.MAX_VALUE - (long) level
                || levelsGained > (Integer.MAX_VALUE - (long) attackPower) / 2L
                || levelsGained > Integer.MAX_VALUE - (long) defense) {
            throw new ArithmeticException("level or character statistics overflow");
        }

        int newLevel = level + (int) levelsGained;
        int newAttackPower = attackPower + (int) (levelsGained * 2L);
        int newDefense = defense + (int) levelsGained;
        return new Progress(newAttackPower, newDefense, newLevel, remainingExperience);
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience);
    }

    private static final class Progress {
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;

        private Progress(int attackPower, int defense, int level, long experience) {
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
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

        private Snapshot(String name, long id, int health, int attackPower, int defense,
                         int level, long experience) {
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
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(int damage, boolean causedDeath,
                                Snapshot attackerSnapshot, Snapshot targetSnapshot) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getDamage() {
            return damage;
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
