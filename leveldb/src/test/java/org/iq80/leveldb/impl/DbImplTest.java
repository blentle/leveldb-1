/**
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.impl;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import org.iq80.leveldb.SeekingIterator;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.table.Options;
import org.iq80.leveldb.util.FileUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.iq80.leveldb.util.Buffers;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Random;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.immutableEntry;
import static java.util.Arrays.asList;
import static org.iq80.leveldb.impl.DbConstants.NUM_LEVELS;
import static org.iq80.leveldb.table.BlockHelper.after;
import static org.iq80.leveldb.table.BlockHelper.assertSequence;
import static org.iq80.leveldb.table.BlockHelper.before;
import static org.iq80.leveldb.table.CompressionType.NONE;
import static org.iq80.leveldb.util.SeekingIterators.transformKeys;
import static org.iq80.leveldb.util.SeekingIterators.transformValues;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class DbImplTest
{
    private File databaseDir;

    @Test
    public void testEmpty()
            throws Exception
    {
        Options options = new Options();
        File databaseDir = this.databaseDir;
        DbStringWrapper db = new DbStringWrapper(options, databaseDir);
        assertNull(db.get("foo"));
    }

    @Test
    public void testReadWrite()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        assertEquals(db.get("foo"), "v1");
        db.put("bar", "v2");
        db.put("foo", "v3");
        assertEquals(db.get("foo"), "v3");
        assertEquals(db.get("bar"), "v2");
    }

    @Test
    public void testPutDeleteGet()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        assertEquals(db.get("foo"), "v1");
        db.put("foo", "v2");
        assertEquals(db.get("foo"), "v2");
        db.delete("foo");
        assertNull(db.get("foo"));
    }

    @Test
    public void testGetFromImmutableLayer()
            throws Exception
    {
        // create db with small write buffer
        DbStringWrapper db = new DbStringWrapper(new Options().setWriteBufferSize(100000), databaseDir);
        db.put("foo", "v1");
        assertEquals(db.get("foo"), "v1");

        // todo Block sync calls

        // Fill memtable
        db.put("k1", longString(100000, 'x'));
        // Trigger compaction
        db.put("k2", longString(100000, 'y'));
        assertEquals(db.get("foo"), "v1");

        // todo Release sync calls
    }

    @Test
    public void testGetFromVersions()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        db.compactMemTable();
        assertEquals(db.get("foo"), "v1");
    }

    @Test
    public void testGetSnapshot()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        // Try with both a short key and a long key
        for (int i = 0; i < 2; i++) {
            String key = (i == 0) ? "foo" : longString(200, 'x');
            db.put(key, "v1");
            Snapshot s1 = db.getSnapshot();
            db.put(key, "v2");
            assertEquals(db.get(key), "v2");
            assertEquals(db.get(key, s1), "v1");

            db.compactMemTable();
            assertEquals(db.get(key), "v2");
            assertEquals(db.get(key, s1), "v1");
            s1.release();
        }
    }

    @Test
    public void testGetLevel0Ordering()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        // Check that we process level-0 files in correct order.  The code
        // below generates two level-0 files where the earlier one comes
        // before the later one in the level-0 file list since the earlier
        // one has a smaller "smallest" key.
        db.put("bar", "b");
        db.put("foo", "v1");
        db.compactMemTable();
        db.put("foo", "v2");
        db.compactMemTable();
        assertEquals(db.get("foo"), "v2");
    }

    @Test
    public void testGetOrderedByLevels()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        db.compact("a", "z");
        assertEquals(db.get("foo"), "v1");
        db.put("foo", "v2");
        assertEquals(db.get("foo"), "v2");
        db.compactMemTable();
        assertEquals(db.get("foo"), "v2");
    }

    @Test
    public void testGetPicksCorrectFile()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("a", "va");
        db.compact("a", "b");
        db.put("x", "vx");
        db.compact("x", "y");
        db.put("f", "vf");
        db.compact("f", "g");

        assertEquals(db.get("a"), "va");
        assertEquals(db.get("f"), "vf");
        assertEquals(db.get("x"), "vx");

    }

    @Test
    public void testEmptyIterator()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        SeekingIterator<String, String> iterator = db.iterator();

        iterator.seekToFirst();
        assertNoNextElement(iterator);

        iterator.seek("foo");
        assertNoNextElement(iterator);
    }

    @Test
    public void testIteratorSingle()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("a", "va");

        assertSequence(db.iterator(), immutableEntry("a", "va"));
    }

    @Test
    public void testIteratorMultiple()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("a", "va");
        db.put("b", "vb");
        db.put("c", "vc");

        SeekingIterator<String, String> iterator = db.iterator();
        assertSequence(iterator,
                immutableEntry("a", "va"),
                immutableEntry("b", "vb"),
                immutableEntry("c", "vc"));

        // Make sure iterator stays at snapshot
        db.put("a", "va2");
        db.put("a2", "va3");
        db.put("b", "vb2");
        db.put("c", "vc2");

        iterator.seekToFirst();
        assertSequence(iterator,
                immutableEntry("a", "va"),
                immutableEntry("b", "vb"),
                immutableEntry("c", "vc"));
    }

    @Test
    public void testRecover()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        db.put("baz", "v5");

        db.reopen();

        assertEquals(db.get("foo"), "v1");
        assertEquals(db.get("baz"), "v5");
        db.put("bar", "v2");
        db.put("foo", "v3");

        db.reopen();

        assertEquals(db.get("foo"), "v3");
        db.put("foo", "v4");
        assertEquals(db.get("foo"), "v4");
        assertEquals(db.get("bar"), "v2");
        assertEquals(db.get("baz"), "v5");

    }

    @Test
    public void testRecoveryWithEmptyLog()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        db.put("foo", "v2");
        db.reopen();
        db.reopen();
        db.put("foo", "v3");
        db.reopen();
        assertEquals(db.get("foo"), "v3");
    }

    @Test
    public void testRecoverDuringMemtableCompaction()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setWriteBufferSize(1000000), databaseDir);

        // Trigger a long memtable compaction and reopen the database during it
        db.put("foo", "v1");                        // Goes to 1st log file
        db.put("big1", longString(10000000, 'x'));  // Fills memtable
        db.put("big2", longString(1000, 'y'));      // Triggers compaction
        db.put("bar", "v2") ;                       // Goes to new log file

        db.reopen();
        assertEquals(db.get("foo"), "v1");
        assertEquals(db.get("bar"), "v2");
        assertEquals(db.get("big1"), longString(10000000, 'x'));
        assertEquals(db.get("big2"), longString(1000, 'y'));

    }

    @Test
    public void testMinorCompactionsHappen()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setWriteBufferSize(10000), databaseDir);

        int n = 500;
        int startingNumTables = db.totalTableFiles();
        for (int i = 0; i < n; i++) {
            db.put(key(i), key(i) + longString(1000, 'v'));
        }
        int endingNumTables = db.totalTableFiles();
        assertTrue(endingNumTables > startingNumTables);

        for (int i = 0; i < n; i++) {
            assertEquals(db.get(key(i)), key(i) + longString(1000, 'v'));
        }
        db.compactMemTable();

        for (int i = 0; i < n; i++) {
            assertEquals(db.get(key(i)), key(i) + longString(1000, 'v'));
        }

        db.reopen();
        for (int i = 0; i < n; i++) {
            assertEquals(db.get(key(i)), key(i) + longString(1000, 'v'));
        }

    }

    @Test
    public void testRecoverWithLargeLog()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("big1", longString(200000, '1'));
        db.put("big2", longString(200000, '2'));
        db.put("small3", longString(10, '3'));
        db.put("small4", longString(10, '4'));
        assertEquals(db.numberOfFilesInLevel(0), 0);

        db.reopen(new Options().setWriteBufferSize(100000));
        assertEquals(db.numberOfFilesInLevel(0), 3);
        assertEquals(db.get("big1"), longString(200000, '1'));
        assertEquals(db.get("big2"), longString(200000, '2'));
        assertEquals(db.get("small3"), longString(10, '3'));
        assertEquals(db.get("small4"), longString(10, '4'));
        assertTrue(db.numberOfFilesInLevel(0) > 1);
    }

    @Test
    public void testCompactionsGenerateMultipleFiles()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setWriteBufferSize(100000000), databaseDir);

        // Write 8MB (80 values, each 100K)
        assertEquals(db.numberOfFilesInLevel(0), 0);
        Random random = new Random(301);
        List<String> values = newArrayList();
        for (int i = 0; i < 80; i++) {
            String value = randomString(random, 100000);
            db.put(key(i), value);
            values.add(value);
        }

        // Reopening moves updates to level-0
        db.reopen();
        db.compactRange(0, "", key(100000));

        assertEquals(db.numberOfFilesInLevel(0), 0);
        assertTrue(db.numberOfFilesInLevel(1) > 1);
        for (int i = 0; i < 80; i++) {
            assertEquals(db.get(key(i)), values.get(i));
        }
    }

    @Test
    public void testRepeatedWritesToSameKey()
            throws Exception
    {
        Options options = new Options().setWriteBufferSize(100000);
        DbStringWrapper db = new DbStringWrapper(options, databaseDir);

        // We must have at most one file per level except for level-0,
        // which may have up to kL0_StopWritesTrigger files.
        int maxFiles = NUM_LEVELS + DbConstants.L0_STOP_WRITES_TRIGGER;

        Random random = new Random(301);
        String value = randomString(random, 2 * options.getWriteBufferSize());
        for (int i = 0; i < 5 * maxFiles; i++) {
            db.put("key", value);
            assertTrue(db.totalTableFiles() < maxFiles);
        }

        db.close();
    }

    @Test
    public void testSparseMerge()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setCompressionType(NONE), databaseDir);

        fillLevels(db, "A", "Z");

        // Suppose there is:
        //    small amount of data with prefix A
        //    large amount of data with prefix B
        //    small amount of data with prefix C
        // and that recent updates have made small changes to all three prefixes.
        // Check that we do not do a compaction that merges all of B in one shot.
        String value = longString(1000, 'x');
        db.put("A", "va");

        // Write approximately 100MB of "B" values
        for (int i = 0; i < 100000; i++) {
            String key = String.format("B%010d", i);
            db.put(key, value);
        }
        db.put("C", "vc");
        db.compactMemTable();
        db.compactRange(0, "A", "Z");

        // Make sparse update
        db.put("A", "va2");
        db.put("B100", "bvalue2");
        db.put("C", "vc2");
        db.compactMemTable();

        // Compactions should not cause us to create a situation where
        // a file overlaps too much data at the next level.
        assertTrue(db.getMaxNextLevelOverlappingBytes() <= 20*1048576);
        db.compactRange(0, "", "z");
        assertTrue(db.getMaxNextLevelOverlappingBytes() <= 20*1048576);
        db.compactRange(1, "", "z");
        assertTrue(db.getMaxNextLevelOverlappingBytes() <= 20*1048576);
    }

    @Test
    public void testApproximateSizes()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setWriteBufferSize(100000000).setCompressionType(NONE), databaseDir);

        assertBetween(db.size("", "xyz"), 0, 0);
        db.reopen();
        assertBetween(db.size("", "xyz"), 0, 0);

        // Write 8MB (80 values, each 100K)
        assertEquals(db.numberOfFilesInLevel(0), 0);
        int n = 80;
        Random random = new Random(301);
        for (int i = 0; i < n; i++) {
            db.put(key(i), randomString(random, 100000));
        }

        // 0 because GetApproximateSizes() does not account for memtable space
        assertBetween(db.size("", key(50)), 0, 0);

        // Check sizes across recovery by reopening a few times
        for (int run = 0; run < 3; run++) {
            db.reopen();

            for (int compactStart = 0; compactStart < n; compactStart += 10) {
                for (int i = 0; i < n; i += 10) {
                    assertBetween(db.size("", key(i)), 100000 * i, 100000 * i + 10000);
                    assertBetween(db.size("", key(i) + ".suffix"), 100000 * (i + 1), 100000 * (i + 1) + 10000);
                    assertBetween(db.size(key(i), key(i + 10)), 100000 * 10, 100000 * 10 + 10000);
                }
                assertBetween(db.size("", key(50)), 5000000, 5010000);
                assertBetween(db.size("", key(50) + ".suffix"), 5100000, 5110000);

                db.compactRange(0, key(compactStart), key(compactStart + 9));
            }

            assertEquals(db.numberOfFilesInLevel(0), 0);
            assertTrue(db.numberOfFilesInLevel(1) > 0);
        }
    }

    @Test
    public void testApproximateSizesMixOfSmallAndLarge()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options().setCompressionType(NONE), databaseDir);
        Random random = new Random(301);
        String big1 = randomString(random, 100000);
        db.put(key(0), randomString(random, 10000));
        db.put(key(1), randomString(random, 10000));
        db.put(key(2), big1);
        db.put(key(3), randomString(random, 10000));
        db.put(key(4), big1);
        db.put(key(5), randomString(random, 10000));
        db.put(key(6), randomString(random, 300000));
        db.put(key(7), randomString(random, 10000));

        // Check sizes across recovery by reopening a few times
        for (int run = 0; run < 3; run++) {
            db.reopen();

            assertBetween(db.size("", key(0)), 0, 0);
            assertBetween(db.size("", key(1)), 10000, 11000);
            assertBetween(db.size("", key(2)), 20000, 21000);
            assertBetween(db.size("", key(3)), 120000, 121000);
            assertBetween(db.size("", key(4)), 130000, 131000);
            assertBetween(db.size("", key(5)), 230000, 231000);
            assertBetween(db.size("", key(6)), 240000, 241000);
            assertBetween(db.size("", key(7)), 540000, 541000);
            assertBetween(db.size("", key(8)), 550000, 551000);

            assertBetween(db.size(key(3), key(5)), 110000, 111000);

            db.compactRange(0, key(0), key(100));
        }
    }

    @Test
    public void testIteratorPinsRef()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "hello");

        SeekingIterator<String, String> iterator = db.iterator();

        db.put("foo", "newvalue1");
        for (int i = 0; i < 100; i++) {
            db.put(key(i), key(i) + longString(100000, 'v'));
        }
        db.put("foo", "newvalue1");

        assertSequence(iterator, immutableEntry("foo", "hello"));
    }

    @Test
    public void testSnapshot()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        db.put("foo", "v1");
        Snapshot s1 = db.getSnapshot();
        db.put("foo", "v2");
        Snapshot s2 = db.getSnapshot();
        db.put("foo", "v3");
        Snapshot s3 = db.getSnapshot();

        db.put("foo", "v4");

        assertEquals("v1", db.get("foo", s1));
        assertEquals("v2", db.get("foo", s2));
        assertEquals("v3", db.get("foo", s3));
        assertEquals("v4", db.get("foo"));

        s3.release();
        assertEquals("v1", db.get("foo", s1));
        assertEquals("v2", db.get("foo", s2));
        assertEquals("v4", db.get("foo"));

        s1.release();
        assertEquals("v2", db.get("foo", s2));
        assertEquals("v4", db.get("foo"));

        s2.release();
        assertEquals("v4", db.get("foo"));
    }

    @Test
    public void testHiddenValuesAreRemoved()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        Random random = new Random(301);
        fillLevels(db, "a", "z");

        String big = randomString(random, 50000);
        db.put("foo", big);
        db.put("pastFoo", "v");

        Snapshot snapshot = db.getSnapshot();

        db.put("foo", "tiny");
        db.put("pastFoo2", "v2");  // Advance sequence number one more

        db.compactMemTable();
        assertTrue(db.numberOfFilesInLevel(0) > 0);

        assertEquals(big, db.get("foo", snapshot));
        assertBetween(db.size("", "pastFoo"), 50000, 60000);
        snapshot.release();
        assertEquals(db.allEntriesFor("foo"), asList("tiny", big));
        db.compactRange(0, "", "x");
        assertEquals(db.allEntriesFor("foo"), asList("tiny"));
        assertEquals(db.numberOfFilesInLevel(0), 0);
        assertTrue(db.numberOfFilesInLevel(1) >= 1);
        db.compactRange(1, "", "x");
        assertEquals(db.allEntriesFor("foo"), asList("tiny"));

        assertBetween(db.size("", "pastFoo"), 0, 1000);
    }

    @Test
    public void testDeletionMarkers1()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        db.put("foo", "v1");
        db.compactMemTable();

        int last = DbConstants.MAX_MEM_COMPACT_LEVEL;
        assertEquals(db.numberOfFilesInLevel(last), 1); // foo => v1 is now in last level

        // Place a table at level last-1 to prevent merging with preceding mutation
        db.put("a", "begin");
        db.put("z", "end");
        db.compactMemTable();
        assertEquals(db.numberOfFilesInLevel(last), 1);
        assertEquals(db.numberOfFilesInLevel(last - 1), 1);

        db.delete("foo");
        db.put("foo", "v2");
        assertEquals(db.allEntriesFor("foo"), asList("v2", "DEL", "v1"));
        db.compactMemTable();  // Moves to level last-2
        assertEquals(db.allEntriesFor("foo"), asList("v2", "DEL", "v1"));
        db.compactRange(last - 2, "", "z");

        // DEL eliminated, but v1 remains because we aren't compacting that level
        // (DEL can be eliminated because v2 hides v1).
        assertEquals(db.allEntriesFor("foo"), asList("v2", "v1"));
        db.compactRange(last - 1, "", "z");

        // Merging last-1 w/ last, so we are the base level for "foo", so
        // DEL is removed.  (as is v1).
        assertEquals(db.allEntriesFor("foo"), asList("v2"));
    }

    @Test
    public void testDeletionMarkers2()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        db.put("foo", "v1");
        db.compactMemTable();

        int last = DbConstants.MAX_MEM_COMPACT_LEVEL;
        assertEquals(db.numberOfFilesInLevel(last), 1); // foo => v1 is now in last level

        // Place a table at level last-1 to prevent merging with preceding mutation
        db.put("a", "begin");
        db.put("z", "end");
        db.compactMemTable();
        assertEquals(db.numberOfFilesInLevel(last), 1);
        assertEquals(db.numberOfFilesInLevel(last - 1), 1);

        db.delete("foo");

        assertEquals(db.allEntriesFor("foo"), asList("DEL", "v1"));
        db.compactMemTable();  // Moves to level last-2
        assertEquals(db.allEntriesFor("foo"), asList("DEL", "v1"));
        db.compactRange(last - 2, "", "z");

        // DEL kept: "last" file overlaps
        assertEquals(db.allEntriesFor("foo"), asList("DEL", "v1"));
        db.compactRange(last - 1, "", "z");

        // Merging last-1 w/ last, so we are the base level for "foo", so
        // DEL is removed.  (as is v1).
        assertEquals(db.allEntriesFor("foo"), asList());
    }

    @Test
    public void testEmptyDb()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        testDb(db);
    }

    @Test
    public void testSingleEntrySingle()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
        testDb(db, immutableEntry("name", "dain sundstrom"));
    }

    @Test
    public void testMultipleEntries()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        List<Entry<String, String>> entries = Arrays.asList(
                immutableEntry("beer/ale", "Lagunitas  Little Sumpin’ Sumpin’"),
                immutableEntry("beer/ipa", "Lagunitas IPA"),
                immutableEntry("beer/stout", "Lagunitas Imperial Stout"),
                immutableEntry("scotch/light", "Oban 14"),
                immutableEntry("scotch/medium", "Highland Park"),
                immutableEntry("scotch/strong", "Lagavulin"));

        testDb(db, entries);
    }

    @Test
    public void testMultiPassMultipleEntries()
            throws Exception
    {
        DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

        List<Entry<String, String>> entries = Arrays.asList(
                immutableEntry("beer/ale", "Lagunitas  Little Sumpin’ Sumpin’"),
                immutableEntry("beer/ipa", "Lagunitas IPA"),
                immutableEntry("beer/stout", "Lagunitas Imperial Stout"),
                immutableEntry("scotch/light", "Oban 14"),
                immutableEntry("scotch/medium", "Highland Park"),
                immutableEntry("scotch/strong", "Lagavulin"));

        for (int i = 1; i < entries.size(); i++) {
            testDb(db, entries);
        }
    }

    private void testDb(DbStringWrapper db, Entry<String, String>... entries)
            throws IOException
    {
        testDb(db, asList(entries));
    }

    private void testDb(DbStringWrapper db, List<Entry<String, String>> entries)
            throws IOException
    {

        for (Entry<String, String> entry : entries) {
            db.put(entry.getKey(), entry.getValue());
        }

        for (Entry<String, String> entry : entries) {
            String actual = db.get(entry.getKey());
            assertEquals(actual, entry.getValue(), "Key: " + entry.getKey());
        }

        SeekingIterator<String, String> seekingIterator = db.iterator();
        assertSequence(seekingIterator, entries);

        seekingIterator.seekToFirst();
        assertSequence(seekingIterator, entries);

        for (Entry<String, String> entry : entries) {
            List<Entry<String, String>> nextEntries = entries.subList(entries.indexOf(entry), entries.size());
            seekingIterator.seek(entry.getKey());
            assertSequence(seekingIterator, nextEntries);

            seekingIterator.seek(before(entry));
            assertSequence(seekingIterator, nextEntries);

            seekingIterator.seek(after(entry));
            assertSequence(seekingIterator, nextEntries.subList(1, nextEntries.size()));
        }

        ChannelBuffer endKey = Buffers.wrappedBuffer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        seekingIterator.seek(endKey.toString(Charsets.UTF_8));
        assertSequence(seekingIterator, Collections.<Entry<String, String>>emptyList());
    }

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        databaseDir = FileUtils.createTempDir("leveldb");
    }

    @AfterMethod
    public void tearDown()
            throws Exception
    {

        FileUtils.deleteRecursively(databaseDir);
    }

    private void assertBetween(long actual, int smallest, int greatest)
    {
        if (!between(actual, smallest, greatest))  {
            fail(String.format("Expected: %s to be between %s and %s", actual, smallest, greatest));
        }
    }

    private void assertNoNextElement(SeekingIterator<String, String> iterator)
    {
        assertFalse(iterator.hasNext());
        try {
            iterator.next();
            fail("Expected NoSuchElementException");
        }
        catch (NoSuchElementException expected) {
        }
        try {
            iterator.peek();
            fail("Expected NoSuchElementException");
        }
        catch (NoSuchElementException expected) {
        }
    }

    static ChannelBuffer toChannelBuffer(String value)
    {
        return Buffers.wrappedBuffer(value.getBytes(UTF_8));
    }


    private static String randomString(Random random, int length)
    {
        char[] chars = new char[length];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (' ' + random.nextInt(95));
        }
        return new String(chars);

    }

    private static String longString(int length, char character)
    {
        char[] chars = new char[length];
        Arrays.fill(chars, character);
        return new String(chars);
    }

    public static String key(int i)
    {
        return String.format("key%06d", i);
    }

    private boolean between(long size, long left, long right)
    {
        return left <= size && size <= right;
    }

    private void fillLevels(DbStringWrapper db, String smallest, String largest)
    {
        for (int level = 0; level < NUM_LEVELS; level++) {
            db.put(smallest, "begin");
            db.put(largest, "end");
            db.compactMemTable();
        }
    }

    private static class DbStringWrapper
    {
        private final Options options;
        private final File databaseDir;
        private DbImpl db;

        private DbStringWrapper(Options options, File databaseDir)
                throws IOException
        {
            this.options = options.setVerifyChecksums(true).setCreateIfMissing(true).setErrorIfExists(true);
            this.databaseDir = databaseDir;
            this.db = new DbImpl(options, databaseDir);
        }

        public String get(String key)
        {
            ChannelBuffer channelBuffer = db.get(toChannelBuffer(key));
            if (channelBuffer == null) {
                return null;
            }
            return channelBuffer.toString(Charsets.UTF_8);
        }

        public String get(String key, Snapshot snapshot)
        {
            ChannelBuffer channelBuffer = db.get(new ReadOptions().setSnapshot(snapshot), toChannelBuffer(key));
            if (channelBuffer == null) {
                return null;
            }
            return channelBuffer.toString(Charsets.UTF_8);
        }

        public void put(String key, String value)
        {
            db.put(toChannelBuffer(key), toChannelBuffer(value));
        }

        public void delete(String key)
        {
            db.delete(toChannelBuffer(key));
        }

        public SeekingIterator<String, String> iterator()
        {
            return transformValues(transformKeys(db.iterator(), new ChannelBufferToString(), new StringToChannelBuffer()), new ChannelBufferToString());
        }

        public Snapshot getSnapshot()
        {
            return db.getSnapshot();
        }

        public void close()
        {
            db.close();
        }

        public void compactMemTable()
        {
            db.flushMemTable();
        }

        public void compactRange(int level, String start, String limit)
        {
            db.compactRange(level, toChannelBuffer(start), toChannelBuffer(limit));
        }

        public void compact(String start, String limit)
        {
            db.flushMemTable();
            int maxLevelWithFiles = 1;
            for (int level = 2; level < NUM_LEVELS; level++) {
                if (db.numberOfFilesInLevel(level) > 0) {
                    maxLevelWithFiles = level;
                }
            }
            for (int level = 0; level < maxLevelWithFiles; level++) {
                db.compactRange(level, toChannelBuffer(""), toChannelBuffer("~"));
            }

        }

        public int numberOfFilesInLevel(int level)
        {
            return db.numberOfFilesInLevel(level);
        }

        public int totalTableFiles()
        {
            int result = 0;
            for (int level = 0; level < NUM_LEVELS; level++) {
                result += db.numberOfFilesInLevel(level);
            }
            return result;
        }

        public long size(String start, String limit)
        {
            return db.getApproximateSizes(toChannelBuffer(start), toChannelBuffer(limit));
        }

        public long getMaxNextLevelOverlappingBytes()
        {
            return db.getMaxNextLevelOverlappingBytes();
        }

        public void reopen()
                throws IOException
        {
            reopen(options);
        }

        public void reopen(Options options)
                throws IOException
        {
            db.close();
            db = new DbImpl(options.setVerifyChecksums(true).setCreateIfMissing(false).setErrorIfExists(false), databaseDir);
        }

        private static class ChannelBufferToString implements Function<ChannelBuffer, String>
        {
            @Override
            public String apply(ChannelBuffer input)
            {
                return input.toString(Charsets.UTF_8);
            }
        }

        private static class StringToChannelBuffer implements Function<String, ChannelBuffer>
        {
            @Override
            public ChannelBuffer apply(String input)
            {
                return toChannelBuffer(input);
            }
        }

        private List<String> allEntriesFor(String userKey)
        {
            ImmutableList.Builder<String> result = ImmutableList.builder();
            for (Entry<InternalKey, ChannelBuffer> entry : db.internalIterable()) {
                String entryKey = entry.getKey().getUserKey().toString(UTF_8);
                if (entryKey.equals(userKey)) {
                    if (entry.getKey().getValueType() == ValueType.VALUE) {
                        result.add(entry.getValue().toString(UTF_8));
                    }
                    else {
                        result.add("DEL");
                    }
                }
            }
            return result.build();
        }

    }
}