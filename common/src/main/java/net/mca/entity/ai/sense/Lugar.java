package net.mca.entity.ai.sense;

/**
 * Where the body is, as a person would put it: out under the sky, under a roof at the level of the land (a house,
 * an overhang, a bridge), or down in the rock (a tunnel, a cave, a mine). Judged by {@link Entorno} from daylight,
 * what the eye saw overhead and how far below the land the villager has gone, never from the world's heightmap
 * alone.
 */
public enum Lugar {
    AFUERA,
    BAJO_TECHO,
    BAJO_TIERRA;

    /** Spanish words for the chat and the logs. */
    public String palabra() {
        return switch (this) {
            case AFUERA -> "afuera";
            case BAJO_TECHO -> "bajo techo";
            case BAJO_TIERRA -> "bajo tierra";
        };
    }
}
