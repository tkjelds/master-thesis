import java.util.concurrent.atomic.AtomicLong;

@RPGCharacter.ThreadSafe
class RPGCharacter {
    @interface ThreadSafe {}

    private static final AtomicLong idGenerator = new AtomicLong(1);

    private final long id;
    private final String name;
    private volatile int health;
    private volatile int attackPower;
    private volatile int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be in the range 1..100");
        }
        if (attackPower < 0) {
            throw new IllegalArgumentException("Attack power cannot be negative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("Defense cannot be negative");
        }

        this.id = idGenerator.getAndIncrement();
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (this == null || that == null || this.health <= 0 || that.health <= 0) {
            return new EncounterResult(this, null, false);
        }

        synchronized (this) {
            if (this.health <= 0 || that.health <= 0) {
                return new EncounterResult(this, null, false);
            }

            int damage = Math.max(0, this.attackPower - that.defense);
            if (damage > 0) {
                synchronized (that) {
                    if (this.health <= 0 || that.health <= 0) {
                        return new EncounterResult(this, null, false);
                    }

                    that.health -= damage;
                    if (that.health < 0) {
                        that.health = 0;
                        this.experience += 10;
                    }
                }

                gainExperience(10);
            }
        }

        return new EncounterResult(this, that, this.health == 0);
    }

    public int heal() {
        if (this.health <= 0) {
            return this.health;
        }

        synchronized (this) {
            if (this.health <= 0) {
                return this.health;
            }

            int newHealth = Math.min(this.health + 10, 100);
            this.health = newHealth;
            return newHealth;
        }
    }

    public void gainExperience(long amount) {
        if (this.health <= 0) {
            throw new IllegalStateException("Cannot gain experience on a dead character");
        }

        synchronized (this) {
            if (this.health <= 0) {
                throw new IllegalStateException("Cannot gain experience on a dead character");
            }

            this.experience += amount;
            int levels = (int) (this.experience / 100);
            if (levels > this.level) {
                int newLevel = this.level + levels;
                long newExperience = this.experience % 100;

                for (int i = this.level; i < newLevel; i++) {
                    this.attackPower += 2;
                    this.defense += 1;
                }

                this.level = newLevel;
                this.experience = newExperience;
            }
        }
    }

    public RPGCharacterSnapshot snapshot() {
        return new RPGCharacterSnapshot(this);
    }

    @Override
    public String toString() {
        return "RPGCharacter{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", health=" + health +
                ", attackPower=" + attackPower +
                ", defense=" + defense +
                ", level=" + level +
                ", experience=" + experience +
                ", alive=" + (health > 0) +
                '}';
    }

    public static class EncounterResult {
        private final RPGCharacter attacker;
        private final RPGCharacter defender;
        private final boolean isDefenderDead;

        public EncounterResult(RPGCharacter attacker, RPGCharacter defender, boolean isDefenderDead) {
            this.attacker = attacker;
            this.defender = defender;
            this.isDefenderDead = isDefenderDead;
        }

        public RPGCharacter getAttacker() {
            return attacker;
        }

        public RPGCharacter getDefender() {
            return defender;
        }

        public boolean isDefenderDead() {
            return isDefenderDead;
        }
    }

    public static class RPGCharacterSnapshot {
        private final long id;
        private final String name;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        public RPGCharacterSnapshot(RPGCharacter character) {
            this.id = character.id;
            this.name = character.name;
            this.health = character.health;
            this.attackPower = character.attackPower;
            this.defense = character.defense;
            this.level = character.level;
            this.experience = character.experience;
            this.alive = character.health > 0;
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
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
}
