package com.worldzip.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip / unzip a Minecraft world folder. Replaces the folder with a {@code .zip} and the reverse.
 * Validates zip-slip, zip bombs, and that {@code level.dat} is present before treating a file as a world.
 */
public final class WorldArchive {

    public static final String ZIP_EXTENSION = ".zip";
    public static final String LEVEL_DAT = "level.dat";
    public static final String ICON_PNG = "icon.png";
    public static final String SESSION_LOCK = "session.lock";
    public static final String ZIP_PART_SUFFIX = ".zip.part";
    public static final String UNZIP_PART_SUFFIX = ".unzip.part";
    /** Icon PNGs extracted from zips so vanilla's favicon loader can read a real path. */
    public static final String ICON_CACHE_DIR = ".worldzip-icons";

    /** Hard cap on uncompressed payload (a huge world, not a zip bomb). */
    public static final long MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024 * 1024;
    public static final int MAX_ENTRIES = 500_000;
    public static final int MAX_COMPRESSION_RATIO = 200;
    public static final long MAX_LEVEL_DAT_BYTES = 16L * 1024 * 1024;
    public static final long MAX_ICON_BYTES = 512L * 1024;
    /** Confirm extra wording above this folder size (2 GiB). */
    public static final long LARGE_WORLD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final ArchiveProgress NO_PROGRESS = new ArchiveProgress();

    private WorldArchive() {}

    public static boolean isZipFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(ZIP_EXTENSION);
    }

    public static boolean isWorldFolder(Path path) {
        return path != null && Files.isDirectory(path) && Files.isRegularFile(path.resolve(LEVEL_DAT));
    }

    /**
     * @return {@code true} for a {@code *.zip.part} file or {@code *.unzip.part} folder — leftovers
     *     from a zip/unzip that never finished (crash, force-quit). Never true for a finished {@code .zip}.
     */
    public static boolean isOrphanTemp(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(ZIP_PART_SUFFIX) || name.endsWith(UNZIP_PART_SUFFIX);
    }

    /**
     * Best-effort removal of an orphaned temp file/folder. Safe to call on a path that no longer exists.
     */
    public static void deleteOrphanTemp(Path path) {
        try {
            if (Files.isDirectory(path)) {
                deleteRecursive(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * @return {@code true} if another process holds {@code session.lock} (world currently open).
     *     A leftover unlocked lock file from a crash does not count.
     */
    public static boolean isSessionLockHeld(Path worldDir) {
        Path lock = worldDir.resolve(SESSION_LOCK);
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock fileLock = channel.tryLock()) {
            return fileLock == null;
        } catch (IOException e) {
            return true;
        }
    }

    public static Path unzipDestination(Path zip) {
        Path zipPath = zip.toAbsolutePath().normalize();
        Path parent = zipPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Zip has no parent folder");
        }
        return parent.resolve(stripZipExtension(zipPath.getFileName().toString()));
    }

    public static String stripZipExtension(String fileName) {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(ZIP_EXTENSION)) {
            return fileName.substring(0, fileName.length() - ZIP_EXTENSION.length());
        }
        return fileName;
    }

    /**
     * Bytes that would be written into a zip (same skip rules as {@link #zipReplace}).
     */
    public static long sourceBytes(Path worldDir) throws IOException {
        Path root = worldDir.toAbsolutePath().normalize();
        long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = unixPath(root.relativize(file));
                if (shouldSkipWhenZipping(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = unixPath(root.relativize(dir));
                if (shouldSkipWhenZipping(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    public static WorldPeek peek(Path zip) throws WorldArchiveException {
        if (!isZipFile(zip)) {
            throw new WorldArchiveException("Not a .zip file");
        }
        ScanResult result = scan(zip, false);
        return new WorldPeek(zip, worldNameFromZip(zip), result.wrapperFolder());
    }

    /**
     * Like {@link #peek(Path)}, but also returns {@code level.dat} / {@code icon.png} bytes read during
     * the same pass over the zip's central directory.
     */
    public static WorldPeekResult peekWithLevelDat(Path zip) throws WorldArchiveException {
        if (!isZipFile(zip)) {
            throw new WorldArchiveException("Not a .zip file");
        }
        ScanResult result = scan(zip, true);
        if (result.levelDat() == null) {
            throw new WorldArchiveException("Not a Minecraft world archive (no level.dat)");
        }
        WorldPeek peek = new WorldPeek(zip, worldNameFromZip(zip), result.wrapperFolder());
        return new WorldPeekResult(peek, result.levelDat(), result.iconPng(), result.uncompressedBytes());
    }

    public static ZipResult zipReplace(Path worldDir) throws WorldArchiveException {
        return zipReplace(worldDir, NEVER_CANCELLED, NO_PROGRESS);
    }

    /**
     * Zip {@code worldDir} to {@code worldDir.getFileName() + ".zip"} in the same parent, then delete the folder.
     * Writes a temp zip first so a failed zip does not destroy the world.
     * Already-compressed files ({@code .mca}, {@code .png}, gzip {@code .dat}, …) are stored, not re-deflated.
     */
    public static ZipResult zipReplace(Path worldDir, BooleanSupplier cancelled, ArchiveProgress progress) throws WorldArchiveException {
        Path dir = worldDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new WorldArchiveException("World folder does not exist");
        }
        if (!Files.isRegularFile(dir.resolve(LEVEL_DAT))) {
            throw new WorldArchiveException("Not a Minecraft world (missing level.dat)");
        }
        Path parent = dir.getParent();
        if (parent == null) {
            throw new WorldArchiveException("World folder has no parent");
        }
        Path zip = parent.resolve(dir.getFileName().toString() + ZIP_EXTENSION);
        if (Files.exists(zip)) {
            throw new WorldArchiveException("A zip with this name already exists");
        }
        Path tempZip = parent.resolve(dir.getFileName().toString() + ZIP_PART_SUFFIX);
        long sourceBytes;
        try {
            sourceBytes = sourceBytes(dir);
            progress.reset(sourceBytes);
            Files.deleteIfExists(tempZip);
            zipDirectory(dir, tempZip, cancelled, progress);
            scan(tempZip, false);
            Files.move(tempZip, zip, StandardCopyOption.REPLACE_EXISTING);
        } catch (WorldArchiveException e) {
            deleteTempFile(tempZip);
            throw e;
        } catch (IOException e) {
            deleteTempFile(tempZip);
            throw e instanceof CancelledException ? WorldArchiveException.cancelled() : new WorldArchiveException("Could not zip world", e);
        }
        long zipBytes;
        try {
            zipBytes = Files.size(zip);
        } catch (IOException e) {
            zipBytes = 0L;
        }
        try {
            deleteRecursive(dir);
        } catch (IOException e) {
            throw new WorldArchiveException("World was zipped to " + zip.getFileName() + " but the folder could not be removed", e);
        }
        return new ZipResult(zip, sourceBytes, zipBytes);
    }

    public static Path unzipReplace(Path zip) throws WorldArchiveException {
        return unzipReplace(zip, NEVER_CANCELLED, NO_PROGRESS);
    }

    /**
     * Unzip {@code name.zip} into {@code name/} next to it, then delete the zip.
     * Extracts into a temp folder first so a bad zip does not wipe a good world.
     */
    public static Path unzipReplace(Path zip, BooleanSupplier cancelled, ArchiveProgress progress) throws WorldArchiveException {
        Path zipPath = zip.toAbsolutePath().normalize();
        if (!isZipFile(zipPath)) {
            throw new WorldArchiveException("Not a .zip file");
        }
        ScanResult layout = scan(zipPath, false);
        Path parent = zipPath.getParent();
        if (parent == null) {
            throw new WorldArchiveException("Zip has no parent folder");
        }
        String folderName = stripZipExtension(zipPath.getFileName().toString());
        Path dest = parent.resolve(folderName);
        if (Files.exists(dest)) {
            throw new WorldArchiveException("Cannot unzip: folder already exists: " + dest.getFileName());
        }
        Path tempDir = parent.resolve(folderName + UNZIP_PART_SUFFIX);
        progress.reset(layout.uncompressedBytes());
        try {
            if (Files.exists(tempDir)) {
                deleteRecursive(tempDir);
            }
            Files.createDirectories(tempDir);
            extract(zipPath, tempDir, layout.wrapperFolder(), cancelled, progress);
            if (!Files.isRegularFile(tempDir.resolve(LEVEL_DAT))) {
                throw new WorldArchiveException("Unzipped files are not a Minecraft world (missing level.dat)");
            }
            Files.move(tempDir, dest);
        } catch (WorldArchiveException e) {
            deleteTempDir(tempDir);
            throw e;
        } catch (IOException e) {
            deleteTempDir(tempDir);
            throw new WorldArchiveException("Could not unzip world", e);
        }
        try {
            Files.delete(zipPath);
        } catch (IOException e) {
            throw new WorldArchiveException("World was unzipped to " + dest.getFileName() + " but the zip could not be removed", e);
        }
        return dest;
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException, WorldArchiveException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new WorldArchiveException("Payload is too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void zipDirectory(Path worldDir, Path zipFile, BooleanSupplier cancelled, ArchiveProgress progress) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream fileOut = Files.newOutputStream(zipFile);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.setLevel(Deflater.BEST_SPEED);
            Path root = worldDir.toAbsolutePath().normalize();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = unixPath(root.relativize(file));
                    if (shouldSkipWhenZipping(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (shouldStore(relative)) {
                        writeStoredEntry(zipOut, file, relative, buffer, cancelled, progress);
                    } else {
                        writeDeflatedEntry(zipOut, file, relative, buffer, cancelled, progress);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = unixPath(root.relativize(dir));
                    if (shouldSkipWhenZipping(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void writeStoredEntry(
        ZipOutputStream zipOut,
        Path file,
        String relative,
        byte[] buffer,
        BooleanSupplier cancelled,
        ArchiveProgress progress
    ) throws IOException {
        CrcAndSize crcAndSize = crcAndSize(file, buffer, cancelled);
        ZipEntry entry = new ZipEntry(relative);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(crcAndSize.size);
        entry.setCompressedSize(crcAndSize.size);
        entry.setCrc(crcAndSize.crc);
        zipOut.putNextEntry(entry);
        copyWithProgress(file, zipOut, buffer, cancelled, progress);
        zipOut.closeEntry();
    }

    private static void writeDeflatedEntry(
        ZipOutputStream zipOut,
        Path file,
        String relative,
        byte[] buffer,
        BooleanSupplier cancelled,
        ArchiveProgress progress
    ) throws IOException {
        ZipEntry entry = new ZipEntry(relative);
        entry.setMethod(ZipEntry.DEFLATED);
        zipOut.putNextEntry(entry);
        copyWithProgress(file, zipOut, buffer, cancelled, progress);
        zipOut.closeEntry();
    }

    private static CrcAndSize crcAndSize(Path file, byte[] buffer, BooleanSupplier cancelled) throws IOException {
        CRC32 crc = new CRC32();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                crc.update(buffer, 0, read);
                size += read;
            }
        }
        return new CrcAndSize(crc.getValue(), size);
    }

    private static void copyWithProgress(
        Path file,
        OutputStream out,
        byte[] buffer,
        BooleanSupplier cancelled,
        ArchiveProgress progress
    ) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                out.write(buffer, 0, read);
                progress.add(read);
            }
        }
    }

    private static void extract(
        Path zip,
        Path destDir,
        String wrapperFolder,
        BooleanSupplier cancelled,
        ArchiveProgress progress
    ) throws IOException, WorldArchiveException {
        Path dest = destDir.toAbsolutePath().normalize();
        long uncompressed = 0;
        int entries = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zipIn = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (cancelled.getAsBoolean()) {
                    throw WorldArchiveException.cancelled();
                }
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new WorldArchiveException("Zip has too many entries");
                }
                String name = sanitizeEntryName(entry.getName(), wrapperFolder);
                if (name == null) {
                    zipIn.closeEntry();
                    continue;
                }
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) {
                    throw new WorldArchiveException("Zip contains a path outside the world folder");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    zipIn.closeEntry();
                    continue;
                }
                Files.createDirectories(out.getParent());
                long written = 0;
                long declared = entry.getSize();
                try (OutputStream fileOut = Files.newOutputStream(out)) {
                    int read;
                    while ((read = zipIn.read(buffer)) != -1) {
                        if (cancelled.getAsBoolean()) {
                            throw WorldArchiveException.cancelled();
                        }
                        written += read;
                        uncompressed += read;
                        if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                            throw new WorldArchiveException("Unzipped world is too large");
                        }
                        if (declared >= 0 && written > declared) {
                            throw new WorldArchiveException("Zip entry is larger than its declared size");
                        }
                        fileOut.write(buffer, 0, read);
                        progress.add(read);
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

    /**
     * Single pass over the zip's central directory: validates zip-slip / zip-bomb safety and the
     * single-world layout (root {@code level.dat} or one wrapper folder), and optionally captures the
     * {@code level.dat} / {@code icon.png} bytes as soon as the matching entry is seen.
     */
    private static ScanResult scan(Path zip, boolean capturePayloads) throws WorldArchiveException {
        boolean sawLevelAtRoot = false;
        String wrapper = null;
        boolean wrapperConflict = false;
        long uncompressed = 0;
        int entries = 0;
        byte[] levelDatBytes = null;
        byte[] rootIcon = null;
        byte[] wrapperIcon = null;
        String wrapperIconFolder = null;
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var enumeration = zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new WorldArchiveException("Zip has too many entries");
                }
                String raw = entry.getName().replace('\\', '/');
                rejectUnsafeName(raw);
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size > MAX_UNCOMPRESSED_BYTES) {
                    throw new WorldArchiveException("Zip entry is too large");
                }
                if (size > 0) {
                    uncompressed += size;
                    if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw new WorldArchiveException("Unzipped world would be too large");
                    }
                    if (compressed > 0 && size / Math.max(compressed, 1) > MAX_COMPRESSION_RATIO) {
                        throw new WorldArchiveException("Zip compression ratio is unsafe");
                    }
                }
                String normalized = trimSlashes(raw);
                if (normalized.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                if (LEVEL_DAT.equals(normalized)) {
                    sawLevelAtRoot = true;
                    if (entry.getSize() > MAX_LEVEL_DAT_BYTES) {
                        throw new WorldArchiveException("level.dat is too large");
                    }
                    if (capturePayloads && levelDatBytes == null) {
                        levelDatBytes = readZipEntry(zipFile, entry, MAX_LEVEL_DAT_BYTES);
                    }
                    continue;
                }
                int slash = normalized.indexOf('/');
                if (slash > 0 && LEVEL_DAT.equals(normalized.substring(slash + 1))) {
                    String folder = normalized.substring(0, slash);
                    if (wrapper == null) {
                        wrapper = folder;
                    } else if (!wrapper.equals(folder)) {
                        wrapperConflict = true;
                    }
                    if (entry.getSize() > MAX_LEVEL_DAT_BYTES) {
                        throw new WorldArchiveException("level.dat is too large");
                    }
                    if (capturePayloads && levelDatBytes == null && folder.equals(wrapper)) {
                        levelDatBytes = readZipEntry(zipFile, entry, MAX_LEVEL_DAT_BYTES);
                    }
                    continue;
                }
                if (capturePayloads && ICON_PNG.equals(normalized) && rootIcon == null && sizeOkForIcon(entry)) {
                    rootIcon = readZipEntry(zipFile, entry, MAX_ICON_BYTES);
                    continue;
                }
                if (capturePayloads && slash > 0 && normalized.indexOf('/', slash + 1) < 0 && ICON_PNG.equals(normalized.substring(slash + 1))) {
                    String folder = normalized.substring(0, slash);
                    if (wrapperIcon == null && sizeOkForIcon(entry)) {
                        wrapperIcon = readZipEntry(zipFile, entry, MAX_ICON_BYTES);
                        wrapperIconFolder = folder;
                    }
                }
            }
        } catch (IOException e) {
            throw new WorldArchiveException("Could not read zip", e);
        }
        if (entries == 0) {
            throw new WorldArchiveException("Zip is empty");
        }
        if (sawLevelAtRoot && wrapper != null) {
            throw new WorldArchiveException("Zip has more than one world (level.dat at root and in a folder)");
        }
        if (wrapperConflict) {
            throw new WorldArchiveException("Zip has more than one world folder");
        }
        if (!sawLevelAtRoot && wrapper == null) {
            throw new WorldArchiveException("Not a Minecraft world archive (no level.dat)");
        }
        byte[] icon = sawLevelAtRoot ? rootIcon : (wrapper != null && wrapper.equals(wrapperIconFolder) ? wrapperIcon : null);
        return new ScanResult(sawLevelAtRoot ? null : wrapper, levelDatBytes, icon, uncompressed);
    }

    private static boolean sizeOkForIcon(ZipEntry entry) {
        long size = entry.getSize();
        return size >= 0 && size <= MAX_ICON_BYTES;
    }

    private static byte[] readZipEntry(ZipFile zipFile, ZipEntry entry, long maxBytes) throws IOException, WorldArchiveException {
        try (InputStream in = zipFile.getInputStream(entry)) {
            return readLimited(in, maxBytes);
        }
    }

    /**
     * @return relative unix path inside the dest world, or {@code null} to skip
     */
    private static String sanitizeEntryName(String rawName, String wrapperFolder) throws WorldArchiveException {
        String raw = rawName.replace('\\', '/');
        rejectUnsafeName(raw);
        String name = trimSlashes(raw);
        if (name.isEmpty()) {
            return null;
        }
        if (wrapperFolder != null) {
            String prefix = wrapperFolder + "/";
            if (!name.equals(wrapperFolder) && !name.startsWith(prefix)) {
                throw new WorldArchiveException("Zip contains files outside the world folder");
            }
            if (name.equals(wrapperFolder)) {
                return null;
            }
            name = name.substring(prefix.length());
        }
        if (name.isEmpty() || SESSION_LOCK.equals(name)) {
            return null;
        }
        return name;
    }

    private static void rejectUnsafeName(String raw) throws WorldArchiveException {
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches("^[A-Za-z]:.*")) {
            throw new WorldArchiveException("Zip contains an absolute path");
        }
        for (String part : raw.replace('\\', '/').split("/")) {
            if ("..".equals(part)) {
                throw new WorldArchiveException("Zip contains a path traversal");
            }
        }
    }

    private static boolean shouldSkipWhenZipping(String relativeUnixPath) {
        String name = relativeUnixPath;
        int slash = name.lastIndexOf('/');
        String fileName = slash >= 0 ? name.substring(slash + 1) : name;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SESSION_LOCK.equals(fileName)
            || fileName.endsWith(".part")
            || "desktop.ini".equals(lower)
            || "thumbs.db".equals(lower);
    }

    /**
     * Region files, screenshots, sounds, and gzip NBT are already compressed — re-deflating them is
     * slow and barely shrinks them. Store as-is; DEFLATE json/txt/etc.
     */
    static boolean shouldStore(String relativeUnixPath) {
        String lower = relativeUnixPath.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String fileName = slash >= 0 ? lower.substring(slash + 1) : lower;
        return fileName.endsWith(".mca")
            || fileName.endsWith(".mcc")
            || fileName.endsWith(".png")
            || fileName.endsWith(".jpg")
            || fileName.endsWith(".jpeg")
            || fileName.endsWith(".ogg")
            || fileName.endsWith(".dat")
            || fileName.endsWith(".dat_old")
            || fileName.endsWith(".zip")
            || fileName.endsWith(".gz");
    }

    private static String unixPath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    private static String worldNameFromZip(Path zip) {
        return stripZipExtension(zip.getFileName().toString());
    }

    private static void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteTempDir(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                deleteRecursive(tempDir);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    /** Internal signal used to unwind {@link java.nio.file.FileVisitor} callbacks, which may only throw {@link IOException}. */
    private static final class CancelledException extends IOException {}

    private record CrcAndSize(long crc, long size) {}

    public record WorldPeek(Path zip, String folderName, String wrapperFolder) {}

    public record WorldPeekResult(WorldPeek peek, byte[] levelDat, byte[] iconPng, long uncompressedBytes) {}

    public record ZipResult(Path zip, long sourceBytes, long zipBytes) {
        public long savedBytes() {
            return Math.max(0L, this.sourceBytes - this.zipBytes);
        }
    }

    private record ScanResult(String wrapperFolder, byte[] levelDat, byte[] iconPng, long uncompressedBytes) {}
}
