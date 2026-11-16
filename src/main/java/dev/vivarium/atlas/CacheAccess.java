package dev.vivarium.atlas;

/** What the disk cache reads from / writes into for one sprite. */
public interface CacheAccess {
    int cacheBlockBytes();

    byte[] cacheBlocks();

    int cacheDeepCount();

    int cacheDeepLength(int i);

    int[] cacheDeep(int i);

    void cacheApply(byte[] blocks, int[][] deep);
}
