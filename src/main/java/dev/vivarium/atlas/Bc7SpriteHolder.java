package dev.vivarium.atlas;

import javax.annotation.Nullable;

/** Duck interface mixed into {@code SpriteContents}. */
public interface Bc7SpriteHolder {
    @Nullable
    Bc7SpriteData vivarium$data();

    void vivarium$setData(@Nullable Bc7SpriteData data);
}
