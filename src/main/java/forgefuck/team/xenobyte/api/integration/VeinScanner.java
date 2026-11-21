package forgefuck.team.xenobyte.api.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sinthoras.visualprospecting.Utils;
import com.sinthoras.visualprospecting.database.ClientCache;
import com.sinthoras.visualprospecting.database.DimensionNameResolver;
import com.sinthoras.visualprospecting.database.OreVeinPosition;
import com.sinthoras.visualprospecting.database.VeinSource;
import com.sinthoras.visualprospecting.database.veintypes.VeinType;
import com.sinthoras.visualprospecting.database.veintypes.VeinTypeCaching;

import gregtech.api.interfaces.IOreMaterial;
import gregtech.common.ores.OreInfo;
import gregtech.common.ores.OreManager;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Client-side GregTech ore vein detection for VisualProspecting.
 *
 * <p>The server sends the client every block of every chunk in view distance, including the
 * natural GT ores deep underground. This class reads them straight from the client's chunk data
 * and identifies the ore vein of each vein-center chunk with the same two-pass algorithm that
 * VisualProspecting uses on the server to rebuild its cache from region files
 * ({@code ChunkAnalysis} and {@code DetailedChunkAnalysis}), then stores the results in VP's
 * client cache, so they show up on JourneyMap / Xaero exactly like prospected veins.
 *
 * <p>Results are stored as {@link VeinSource#RESCAN}, VP's lowest-trust source: they never
 * replace data the server has sent, and real server data replaces them.
 *
 * <p>This is the only class touching VisualProspecting / GregTech types. It must only be loaded
 * when both mods are present.
 */
public class VeinScanner {

    /** Ore-vein-center neighbours, used to strip ores that spilled over from neighbouring veins. */
    private static final int[][] NEIGHBOR_OFFSETS = { { -3, 3 }, { 0, 3 }, { 3, 3 }, { 3, 0 }, { 3, -3 }, { 0, -3 }, { -3, -3 }, { -3, 0 } };
    private static final int WORLD_HEIGHT = 256;

    private final Map<Long, Cell> cells = new HashMap<Long, Cell>();
    private World world;
    private int dimensionId;
    private String dimensionName;

    public int scannedChunks, identifiedVeins, reportedVeins;
    public long scanNanos;

    private static class Cell {
        final int chunkX, chunkZ;
        boolean scanned, resolved, reported;
        /** True when the fast single-vein pass matched; only then veinBlockY is meaningful. */
        boolean singleMatch;
        int veinBlockY;
        VeinType vein = VeinType.NO_VEIN;
        /** Per-height ore counts, kept only for chunks that need the detailed pass. */
        Map<IOreMaterial, Integer>[] oresPerY;

        Cell(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public void reset() {
        cells.clear();
        world = null;
        scannedChunks = identifiedVeins = reportedVeins = 0;
        scanNanos = 0;
    }

    public static boolean isWorldSupported(World world) {
        return VeinTypeCaching.hasVeinsInDimension(DimensionNameResolver.resolve(world.provider.dimensionId));
    }

    /**
     * Scans up to {@code budget} not yet scanned vein-center chunks around the player (closest first),
     * resolves overlapping veins whose neighbours are known, and stores new results in VP's client cache.
     *
     * @return number of veins newly handed to VisualProspecting in this call
     */
    public int tick(World world, double playerX, double playerZ, int radiusChunks, int budget, boolean silent) {
        if (world != this.world || world.provider.dimensionId != dimensionId) {
            reset();
            this.world = world;
            this.dimensionId = world.provider.dimensionId;
            this.dimensionName = DimensionNameResolver.resolve(dimensionId);
        }
        int playerChunkX = Utils.coordBlockToChunk((int) Math.floor(playerX));
        int playerChunkZ = Utils.coordBlockToChunk((int) Math.floor(playerZ));
        List<Cell> pending = new ArrayList<Cell>();
        int minX = Utils.mapToCenterOreChunkCoord(playerChunkX - radiusChunks);
        int minZ = Utils.mapToCenterOreChunkCoord(playerChunkZ - radiusChunks);
        for (int cx = minX; cx <= playerChunkX + radiusChunks; cx += 3) {
            for (int cz = minZ; cz <= playerChunkZ + radiusChunks; cz += 3) {
                Cell cell = cell(cx, cz);
                if (!cell.scanned && isLoaded(cx, cz)) {
                    pending.add(cell);
                }
            }
        }
        final int pcx = playerChunkX, pcz = playerChunkZ;
        pending.sort((a, b) -> Integer.compare(dist2(a, pcx, pcz), dist2(b, pcx, pcz)));
        long start = System.nanoTime();
        for (int i = 0; i < pending.size() && i < budget; i++) {
            scan(pending.get(i));
        }
        resolveAmbiguous();
        scanNanos += System.nanoTime() - start;
        return report(silent);
    }

    /** Vein type this scanner identified for the vein-center chunk containing the given chunk, or null if not scanned yet. */
    public String identifiedVein(int chunkX, int chunkZ) {
        Cell cell = cells.get(key(Utils.mapToCenterOreChunkCoord(chunkX), Utils.mapToCenterOreChunkCoord(chunkZ)));
        return cell == null || !cell.scanned ? null : cell.vein.name;
    }

    public int pendingAmbiguous() {
        int n = 0;
        for (Cell cell : cells.values()) {
            if (cell.scanned && !cell.singleMatch && !cell.resolved) n++;
        }
        return n;
    }

    private static int dist2(Cell c, int x, int z) {
        int dx = c.chunkX - x, dz = c.chunkZ - z;
        return dx * dx + dz * dz;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private Cell cell(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        Cell cell = cells.get(k);
        if (cell == null) {
            cells.put(k, cell = new Cell(chunkX, chunkZ));
        }
        return cell;
    }

    private boolean isLoaded(int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
        return chunk != null && !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded;
    }

    // ------------------------------------------------------------------------------------------
    // Pass 1: fast single-vein match (VisualProspecting ChunkAnalysis)
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void scan(Cell cell) {
        Chunk chunk = world.getChunkFromChunkCoords(cell.chunkX, cell.chunkZ);
        Map<IOreMaterial, Integer>[] oresPerY = new Map[WORLD_HEIGHT];
        Map<IOreMaterial, Integer> oreCounts = new HashMap<IOreMaterial, Integer>();
        int minVeinBlockY = WORLD_HEIGHT;
        for (ExtendedBlockStorage ebs : chunk.getBlockStorageArray()) {
            if (ebs == null || ebs.isEmpty()) continue;
            int baseY = ebs.getYLocation();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = ebs.getBlockByExtId(x, y, z);
                        if (block == Blocks.air) continue;
                        int meta = ebs.getExtBlockMetadata(x, y, z);
                        IOreMaterial ore = naturalOre(block, meta);
                        if (ore == null) continue;
                        int blockY = baseY + y;
                        if (oresPerY[blockY] == null) {
                            oresPerY[blockY] = new HashMap<IOreMaterial, Integer>();
                        }
                        add(oresPerY[blockY], ore, 1);
                        add(oreCounts, ore, 1);
                        if (blockY < minVeinBlockY) minVeinBlockY = blockY;
                    }
                }
            }
        }
        cell.scanned = true;
        scannedChunks++;
        if (oreCounts.isEmpty()) {
            cell.singleMatch = true; // nothing to resolve
            return;
        }
        IOreMaterial mostCommonOre = dominant(oreCounts);
        VeinType matched = VeinType.NO_VEIN;
        boolean single = oreCounts.size() <= 4;
        if (single) {
            int matches = 0;
            for (VeinType vein : VeinTypeCaching.getVeinTypesForOre(mostCommonOre)) {
                if (vein.containsAllFoundOres(oreCounts.keySet(), dimensionName, mostCommonOre, minVeinBlockY)) {
                    matches++;
                    matched = vein;
                }
            }
            if (matches == 0) {
                // The most common ore can be the vein's in-between or sporadic ore, which VP doesn't index:
                // accept any vein whose complete ore set is exactly what was found.
                for (VeinType vein : candidates(oreCounts.keySet())) {
                    if (vein.containsAllFoundOres(oreCounts.keySet(), dimensionName, vein.primaryOre, minVeinBlockY)) {
                        matches++;
                        matched = vein;
                    }
                }
            }
            single = matches == 1;
        }
        if (single) {
            cell.singleMatch = true;
            cell.vein = matched;
            cell.veinBlockY = minVeinBlockY;
            identifiedVeins++;
        } else {
            cell.oresPerY = oresPerY;
        }
    }

    private static Set<IOreMaterial> veinOres;

    /**
     * Every ore material that is part of some GT ore mix. Natural ores of other materials (e.g. other mods' ores
     * that GT unifies, when their own world generation is enabled) can never belong to a vein and would only
     * skew the dominant-ore detection, so they are ignored.
     */
    private static Set<IOreMaterial> veinOres() {
        if (veinOres == null) {
            Set<IOreMaterial> ores = new HashSet<IOreMaterial>();
            for (VeinType vein : VeinTypeCaching.getVeinTypes()) {
                for (IOreMaterial ore : new IOreMaterial[] { vein.primaryOre, vein.secondaryOre, vein.inBetweenOre, vein.sporadicOre }) {
                    if (ore != null) ores.add(ore);
                }
            }
            veinOres = ores;
        }
        return veinOres;
    }

    private static IOreMaterial naturalOre(Block block, int meta) {
        try (OreInfo<IOreMaterial> info = OreManager.getOreInfo(block, meta)) {
            if (info == null || info.isSmall || !info.isNatural || info.material == null) return null;
            return veinOres().contains(info.material) ? info.material : null;
        }
    }

    /** Vein types having any of the given ores as primary or secondary ore. */
    private static Set<VeinType> candidates(Collection<IOreMaterial> ores) {
        Set<VeinType> veins = new java.util.LinkedHashSet<VeinType>();
        for (IOreMaterial ore : ores) {
            veins.addAll(VeinTypeCaching.getVeinTypesForOre(ore));
        }
        return veins;
    }

    private static void add(Map<IOreMaterial, Integer> map, IOreMaterial ore, int n) {
        Integer old = map.get(ore);
        map.put(ore, old == null ? n : old + n);
    }

    private static IOreMaterial dominant(Map<IOreMaterial, Integer> counts) {
        IOreMaterial best = null;
        int highest = -1;
        for (Map.Entry<IOreMaterial, Integer> e : counts.entrySet()) {
            if (e.getValue() > highest) {
                highest = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------
    // Pass 2: overlapping veins (VisualProspecting DetailedChunkAnalysis)
    // ------------------------------------------------------------------------------------------

    private void resolveAmbiguous() {
        List<Cell> ambiguous = new ArrayList<Cell>();
        for (Cell cell : cells.values()) {
            if (cell.scanned && !cell.singleMatch && !cell.resolved) ambiguous.add(cell);
        }
        for (Cell cell : ambiguous) {
            if (!neighborsSettled(cell)) continue;
            cell.vein = detailedMatch(cell);
            cell.resolved = true;
            cell.oresPerY = null;
            if (cell.vein != VeinType.NO_VEIN) identifiedVeins++;
        }
    }

    /**
     * All neighbouring vein-center chunks are scanned (fast pass done) or not loaded on this client.
     * Loaded neighbours outside the scan radius are scanned on demand, so edge cells don't wait forever.
     */
    private boolean neighborsSettled(Cell cell) {
        for (int[] offset : NEIGHBOR_OFFSETS) {
            int nx = cell.chunkX + offset[0], nz = cell.chunkZ + offset[1];
            Cell neighbor = cell(nx, nz);
            if (!neighbor.scanned && isLoaded(nx, nz)) {
                scan(neighbor);
            }
        }
        return true;
    }

    private VeinType detailedMatch(Cell cell) {
        Map<IOreMaterial, Integer>[] oresPerY = cell.oresPerY;
        // Remove ores that spilled over from neighbouring veins.
        for (int[] offset : NEIGHBOR_OFFSETS) {
            Cell neighbor = cells.get(key(cell.chunkX + offset[0], cell.chunkZ + offset[1]));
            if (neighbor == null || !neighbor.singleMatch || neighbor.vein == VeinType.NO_VEIN) continue;
            boolean atCoordinateAxis = offset[0] == 0 || offset[1] == 0;
            boolean canOverlap = atCoordinateAxis ? neighbor.vein.canOverlapIntoNeighborOreChunkAtCoordinateAxis() : neighbor.vein.canOverlapIntoNeighborOreChunk();
            if (!canOverlap) continue;
            for (int layer = 0; layer < VeinType.veinHeight; layer++) {
                int blockY = neighbor.veinBlockY + layer;
                if (blockY >= WORLD_HEIGHT) break;
                if (oresPerY[blockY] != null) {
                    for (IOreMaterial ore : neighbor.vein.getOresAtLayer(layer)) {
                        oresPerY[blockY].remove(ore);
                    }
                }
            }
        }
        Map<IOreMaterial, Integer> allOres = new HashMap<IOreMaterial, Integer>();
        for (Map<IOreMaterial, Integer> level : oresPerY) {
            if (level == null) continue;
            for (Map.Entry<IOreMaterial, Integer> e : level.entrySet()) {
                add(allOres, e.getKey(), e.getValue());
            }
        }
        if (allOres.isEmpty()) return VeinType.NO_VEIN;
        IOreMaterial dominantOre = dominant(allOres);
        Set<VeinType> matched = new HashSet<VeinType>();
        for (VeinType vein : VeinTypeCaching.getVeinTypesForOre(dominantOre)) {
            if (vein.matchesWithSpecificPrimaryOrSecondary(allOres.keySet(), dimensionName, dominantOre)) {
                matched.add(vein);
            }
        }
        if (matched.isEmpty()) {
            // Dominant ore may be an in-between / sporadic ore: try every vein whose complete ore set is present.
            for (VeinType vein : candidates(allOres.keySet())) {
                if (vein.matchesWithSpecificPrimaryOrSecondary(allOres.keySet(), dimensionName, vein.primaryOre)) {
                    matched.add(vein);
                }
            }
        }
        if (matched.size() == 1) {
            return matched.iterator().next();
        }
        if (matched.size() >= 2) {
            matched.removeIf(vein -> !generatedAtAnyHeight(oresPerY, vein));
            return matched.size() == 1 ? matched.iterator().next() : VeinType.NO_VEIN;
        }
        if (ClientCache.instance.getOreVein(dimensionId, cell.chunkX, cell.chunkZ).veinType != VeinType.NO_VEIN) {
            return VeinType.NO_VEIN; // already known, don't guess
        }
        return matchWithOneMissingOre(allOres.keySet(), dominantOre);
    }

    private VeinType matchWithOneMissingOre(Collection<IOreMaterial> foundOres, IOreMaterial dominantOre) {
        VeinType onlyMatch = VeinType.NO_VEIN;
        for (VeinType vein : VeinTypeCaching.getVeinTypesForOre(dominantOre)) {
            if (vein.matchesWithOneMissingOre(foundOres, dimensionName, dominantOre)) {
                if (onlyMatch != VeinType.NO_VEIN) return VeinType.NO_VEIN;
                onlyMatch = vein;
            }
        }
        return onlyMatch;
    }

    private static boolean generatedAtAnyHeight(Map<IOreMaterial, Integer>[] oresPerY, VeinType vein) {
        for (int blockY = vein.minBlockY; blockY < vein.maxBlockY; blockY++) {
            if (generatedAtHeight(oresPerY, vein, blockY)) return true;
        }
        return false;
    }

    private static boolean generatedAtHeight(Map<IOreMaterial, Integer>[] oresPerY, VeinType vein, int blockY) {
        for (int layer = 0; layer < VeinType.veinHeight; layer++) {
            int y = blockY + layer;
            if (y >= WORLD_HEIGHT || oresPerY[y] == null || !oresPerY[y].keySet().containsAll(vein.getOresAtLayer(layer))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Output to VisualProspecting
    // ------------------------------------------------------------------------------------------

    private int report(boolean silent) {
        List<OreVeinPosition> veins = new ArrayList<OreVeinPosition>();
        for (Cell cell : cells.values()) {
            if (cell.reported || !cell.scanned || (!cell.singleMatch && !cell.resolved)) continue;
            cell.reported = true;
            if (cell.vein == VeinType.NO_VEIN) continue;
            if (ClientCache.instance.getOreVein(dimensionId, cell.chunkX, cell.chunkZ).veinType == cell.vein) continue;
            veins.add(new OreVeinPosition(dimensionId, cell.chunkX, cell.chunkZ, cell.vein, false, VeinSource.RESCAN));
        }
        if (!veins.isEmpty()) {
            ClientCache.instance.putOreVeins(veins, silent);
            reportedVeins += veins.size();
        }
        return veins.size();
    }

}
