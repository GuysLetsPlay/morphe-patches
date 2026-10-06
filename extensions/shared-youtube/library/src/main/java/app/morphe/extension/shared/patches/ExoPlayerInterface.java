package app.morphe.extension.shared.patches;

/**
 * Interface to use obfuscated methods.
 */
public interface ExoPlayerInterface {
    // Method is added during patching.
    void patch_setPlaybackParameters(float speed, float pitch);
    // Method is added during patching.
    void patch_pause();
}
