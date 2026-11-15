@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final int MAX_INT = 2147483647;
    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_HEALTH = 100;

    private static final Monitor ID_MONITOR = new Monitor();
    private static long nextId = 1;
    private static boolean idsExhausted;

    private final Monitor monitor = new Monitor();
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

            long allocated = nextId;
            if (nextId == MAX_LONG) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return allocated;
        }
    }

    /**
     * Encounters acquire both private monitors in increasing ID order.
     * Operations block only on monitor acquisition; no condition waits are needed.
     */
    public AttackResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("Self-attacks are not allowed");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first.monitor) {
            synchronized (second.monitor) {
                if (health == 0 || other.health == 0) {
                    return new AttackResult(
                            0, false, snapshotLocked(), other.snapshotLocked());
                }

                int damage = attackPower > other.defense
                        ? attackPower - other.defense
                        : 0;
                if (damage > other.health) {
                    damage = other.health;
                }

                boolean causedDeath = damage == other.health;

                // Validate the entire reward before committing any damage.
                ExperienceChange reward = causedDeath
                        ? planExperienceLocked(10L)
                        : null;

                other.health -= damage;
                if (reward != null) {
                    applyExperienceLocked(reward);
                }

                return new AttackResult(
                        damage, causedDeath,
                        snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (monitor) {
            if (health == 0) {
                return 0;
            }
            health = health >= MAX_HEALTH - 10
                    ? MAX_HEALTH
                    : health + 10;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "Experience amount must be nonnegative");
        }

        synchronized (monitor) {
            if (health == 0) {
                throw new IllegalStateException(
                        "Dead characters cannot gain experience");
            }

            ExperienceChange change = planExperienceLocked(amount);
            applyExperienceLocked(change);
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    private ExperienceChange planExperienceLocked(long amount) {
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

        return new ExperienceChange(
                level + (int) levelsGained,
                attackPower + (int) (levelsGained * 2L),
                defense + (int) levelsGained,
                total % 100L);
    }

    private void applyExperienceLocked(ExperienceChange change) {
        level = change.level;
        attackPower = change.attackPower;
        defense = change.defense;
        experience = change.experience;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense,
                level, experience, health > 0);
    }

    private static final class Monitor {
    }

    private static final class ExperienceChange {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private ExperienceChange(
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
    }

    public static final class AttackResult {
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attacker;
        public final Snapshot target;

        private AttackResult(
                int damage,
                boolean causedDeath,
                Snapshot attacker,
                Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
