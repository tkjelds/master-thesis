@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static long nextId = 1L;
    private static boolean idsExhausted;

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
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attack power and defense must be nonnegative");
        }

        this.id = allocateId();
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
    }

    private static long allocateId() {
        synchronized (RPGCharacter.class) {
            if (idsExhausted) {
                throw new IllegalStateException("Character IDs exhausted");
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
            throw new IllegalArgumentException("A character cannot attack itself");
        }

        if (id < other.id) {
            synchronized (this) {
                synchronized (other) {
                    return resolveEncounterLocked(other);
                }
            }
        } else {
            synchronized (other) {
                synchronized (this) {
                    return resolveEncounterLocked(other);
                }
            }
        }
    }

    private EncounterResult resolveEncounterLocked(RPGCharacter other) {
        if (health == 0 || other.health == 0) {
            return new EncounterResult(
                    0, false, snapshotLocked(), other.snapshotLocked());
        }

        int difference = attackPower - other.defense;
        int damage = difference > 0
                ? Math.min(difference, other.health)
                : 0;
        boolean causedDeath = damage == other.health;

        Progress progress = null;
        if (causedDeath) {
            progress = calculateProgress(
                    level, experience, attackPower, defense, 10L);
        }

        other.health -= damage;
        if (progress != null) {
            level = progress.level;
            experience = progress.experience;
            attackPower = progress.attackPower;
            defense = progress.defense;
        }

        return new EncounterResult(
                damage, causedDeath, snapshotLocked(), other.snapshotLocked());
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
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
            }

            Progress progress = calculateProgress(
                    level, experience, attackPower, defense, amount);
            level = progress.level;
            experience = progress.experience;
            attackPower = progress.attackPower;
            defense = progress.defense;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience, health > 0);
    }

    private static Progress calculateProgress(
            int currentLevel,
            long currentExperience,
            int currentAttackPower,
            int currentDefense,
            long amount) {
        long levelGains = amount / 100L;
        long remainder = amount % 100L + currentExperience;
        levelGains += remainder / 100L;
        long newExperience = remainder % 100L;

        if (levelGains > (long) Integer.MAX_VALUE - currentLevel) {
            throw new ArithmeticException("Level overflow");
        }

        long newLevel = (long) currentLevel + levelGains;
        long newAttackPower = (long) currentAttackPower + levelGains * 2L;
        long newDefense = (long) currentDefense + levelGains;

        if (newAttackPower > Integer.MAX_VALUE) {
            throw new ArithmeticException("Attack power overflow");
        }
        if (newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("Defense overflow");
        }

        return new Progress(
                (int) newLevel,
                newExperience,
                (int) newAttackPower,
                (int) newDefense);
    }

    private static final class Progress {
        final int level;
        final long experience;
        final int attackPower;
        final int defense;

        Progress(int level, long experience, int attackPower, int defense) {
            this.level = level;
            this.experience = experience;
            this.attackPower = attackPower;
            this.defense = defense;
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
