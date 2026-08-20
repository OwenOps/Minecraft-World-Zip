package com.worldzip.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldArchiveTest {

    @TempDir
    Path temp;

    @Test
    void roundTripReplacesFolderWithZipAndBack() throws Exception {
        Path world = this.temp.resolve("MyWorld");
        writeMinimalWorld(world);
        Files.writeString(world.resolve("notes.txt"), "hello");

        WorldArchive.ZipResult result = WorldArchive.zipReplace(world);
        assertFalse(Files.exists(world));
        assertTrue(Files.isRegularFile(result.zip()));
        assertTrue(result.sourceBytes() > 0);

        Path dest = WorldArchive.unzipReplace(result.zip());
        assertEquals(world, dest);
        assertTrue(Files.isRegularFile(world.resolve(WorldArchive.LEVEL_DAT)));
        assertEquals("hello", Files.readString(world.resolve("notes.txt")));
        assertFalse(Files.exists(result.zip()));
    }

    @Test
    void unzipFlattensSingleWrapperFolder() throws Exception {
        Path zip = this.temp.resolve("Wrapped.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "Inner/level.dat", new byte[] {1, 2, 3});
            put(out, "Inner/notes.txt", "nether".getBytes(StandardCharsets.UTF_8));
        }

        Path dest = WorldArchive.unzipReplace(zip);
        assertTrue(Files.isRegularFile(dest.resolve(WorldArchive.LEVEL_DAT)));
        assertEquals("nether", Files.readString(dest.resolve("notes.txt")));
        assertFalse(Files.exists(dest.resolve("Inner")));
    }

    @Test
    void peekRejectsPathTraversal() throws Exception {
        Path zip = this.temp.resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "level.dat", new byte[] {1});
            put(out, "../escape.txt", new byte[] {9});
        }
        WorldArchiveException e = assertThrows(WorldArchiveException.class, () -> WorldArchive.peek(zip));
        assertTrue(e.getMessage().toLowerCase().contains("traversal"));
    }

    @Test
    void peekRejectsTwoWorlds() throws Exception {
        Path zip = this.temp.resolve("two.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "level.dat", new byte[] {1});
            put(out, "Other/level.dat", new byte[] {2});
        }
        assertThrows(WorldArchiveException.class, () -> WorldArchive.peek(zip));
    }

    @Test
    void regionFilesAreStoredNotDeflated() throws Exception {
        Path world = this.temp.resolve("Regions");
        writeMinimalWorld(world);
        Path region = world.resolve("region");
        Files.createDirectories(region);
        byte[] mca = new byte[4096];
        for (int i = 0; i < mca.length; i++) {
            mca[i] = (byte) (i * 31);
        }
        Files.write(region.resolve("r.0.0.mca"), mca);
        Files.writeString(world.resolve("pack.json"), "{\"x\":1}");

        WorldArchive.ZipResult result = WorldArchive.zipReplace(world);
        try (ZipFile zipFile = new ZipFile(result.zip().toFile())) {
            ZipEntry mcaEntry = zipFile.getEntry("region/r.0.0.mca");
            assertNotNull(mcaEntry);
            assertEquals(ZipEntry.STORED, mcaEntry.getMethod());
            ZipEntry json = zipFile.getEntry("pack.json");
            assertNotNull(json);
            assertEquals(ZipEntry.DEFLATED, json.getMethod());
        }
    }

    @Test
    void cancelLeavesOriginalFolderAndDeletesTempZip() throws Exception {
        Path world = this.temp.resolve("CancelMe");
        writeMinimalWorld(world);
        for (int i = 0; i < 20; i++) {
            Files.write(world.resolve("chunk" + i + ".bin"), new byte[8192]);
        }
        ArchiveProgress progress = new ArchiveProgress();
        WorldArchiveException e = assertThrows(
            WorldArchiveException.class,
            () -> WorldArchive.zipReplace(world, () -> progress.done() > 16_384, progress)
        );
        assertTrue(e.isCancelled());
        assertTrue(Files.isDirectory(world));
        assertTrue(Files.isRegularFile(world.resolve(WorldArchive.LEVEL_DAT)));
        assertFalse(Files.exists(this.temp.resolve("CancelMe.zip")));
        assertFalse(Files.exists(this.temp.resolve("CancelMe.zip.part")));
    }

    @Test
    void peekCapturesIconAndLevelDatInOnePass() throws Exception {
        Path zip = this.temp.resolve("IconWorld.zip");
        byte[] levelDat = {10, 20, 30};
        byte[] icon = {80, 81, 82, 83};
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "level.dat", levelDat);
            put(out, "icon.png", icon);
        }
        WorldArchive.WorldPeekResult result = WorldArchive.peekWithLevelDat(zip);
        assertEquals("IconWorld", result.peek().folderName());
        org.junit.jupiter.api.Assertions.assertArrayEquals(levelDat, result.levelDat());
        org.junit.jupiter.api.Assertions.assertArrayEquals(icon, result.iconPng());
        assertTrue(result.uncompressedBytes() >= levelDat.length + icon.length);
    }

    @Test
    void orphanTempDetection() {
        assertTrue(WorldArchive.isOrphanTemp(Path.of("New World.zip.part")));
        assertTrue(WorldArchive.isOrphanTemp(Path.of("New World.unzip.part")));
        assertFalse(WorldArchive.isOrphanTemp(Path.of("New World.zip")));
        assertFalse(WorldArchive.isOrphanTemp(Path.of("New World")));
    }

    @Test
    void shouldStoreAlreadyCompressedTypes() {
        assertTrue(WorldArchive.shouldStore("region/r.0.0.mca"));
        assertTrue(WorldArchive.shouldStore("icon.png"));
        assertTrue(WorldArchive.shouldStore("level.dat"));
        assertFalse(WorldArchive.shouldStore("data/world.json"));
        assertFalse(WorldArchive.shouldStore("session.lock"));
    }

    @Test
    void unzipDestinationStripsExtension() {
        Path zip = this.temp.resolve("New World.zip");
        assertEquals(this.temp.resolve("New World"), WorldArchive.unzipDestination(zip));
    }

    @Test
    void byteFormatUsesBinaryUnits() {
        assertEquals("512 B", ByteFormat.human(512));
        assertEquals("1.00 KB", ByteFormat.human(1024));
        assertEquals("1.00 MB", ByteFormat.human(1024 * 1024));
    }

    private static void writeMinimalWorld(Path world) throws IOException {
        Files.createDirectories(world);
        Files.write(world.resolve(WorldArchive.LEVEL_DAT), new byte[] {1, 2, 3, 4});
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
