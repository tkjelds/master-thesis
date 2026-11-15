@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1;
    private static boolean idsExhausted = false;

    private final Object lock = new Object();
    private final String name;
    private final long id;

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
            throw new IllegalArgumentException("name must not be empty");
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
        this.experience = 0;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("character IDs exhausted");
            }
            long id = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return id;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = first == this ? other : this;
        synchronized (first.lock) {
            synchronized (second.lock) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(0, false, snapshotLocked(), other.snapshotLocked());
                }

                int potentialDamage = attackPower > other.defense
                        ? attackPower - other.defense : 0;
                int damage = potentialDamage < other.health ? potentialDamage : other.health;
                boolean causedDeath = damage == other.health;

                if (causedDeath) {
                    addExperienceLocked(10);
                }
                other.health -= damage;

                return new EncounterResult(
                        damage, causedDeath, snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (lock) {
            if (health > 0) {
                health = health > 90 ? 100 : health + 10;
            }
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (lock) {
            if (health == 0) {
                throw new IllegalStateException("a dead character cannot gain experience");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("experience must be nonnegative");
            }
            addExperienceLocked(amount);
        }
    }

    private void addExperienceLocked(long amount) {
        long remainder = experience + amount % 100;
        long levelsGained = amount / 100 + remainder / 100;
        long newExperience = remainder % 100;

        if (levelsGained > Integer.MAX_VALUE - level
                || levelsGained > (Integer.MAX_VALUE - attackPower) / 2
                || levelsGained > Integer.MAX_VALUE - defense) {
            throw new ArithmeticException("level or attribute overflow");
        }

        int gained = (int) levelsGained;
        level += gained;
        attackPower += gained * 2;
        defense += gained;
        experience = newExperience;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience, health > 0);
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
                String name, long id, int health, int attackPower, int defense,
                int level, long experience, boolean alive) {
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
        public final Snapshot attacker;
        public final Snapshot target;

        private EncounterResult(
                int damage, boolean causedDeath, Snapshot attacker, Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
