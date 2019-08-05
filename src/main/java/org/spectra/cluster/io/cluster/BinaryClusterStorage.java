package org.spectra.cluster.io.cluster;

import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.map.ChronicleMapBuilder;
import org.bigbio.pgatk.io.common.MzIterableReader;
import org.bigbio.pgatk.io.common.spectra.Spectrum;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.spectra.cluster.model.cluster.GreedySpectralCluster;
import org.spectra.cluster.model.cluster.ICluster;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * This code is licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 *
 * The {@link BinaryClusterStorage} allows to storage in memory/disk a clusters to be analyzed. Two flavours can be use, the Dynamic Storage (no pre-allocation of the number of clusters is needed) and the Static Storage (pre-allocation of the number of clusters is needed).
 *
 * Some benchmarks has proved that the Static Storage is 4x faster than the Dynamic Storage. However this storage needs to be used only when its known the number of clusters.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *
 * @author ypriverol on 17/10/2018.
 */
@Slf4j
public class BinaryClusterStorage implements IClusterStorage, MzIterableReader {

    public static File dbFile = null;

    public static final long MAX_NUMBER_FEATURES = 1000000;

    private ConcurrentMap<String, ICluster> clusters;

    private DB levelDB = null;
    private int levelDBSize = 0;

    public boolean dynamic = false;

    public BinaryClusterStorage(boolean dynamic, long numberClusters, String fileName) throws IOException {

        if(fileName == null){
           fileName = "clustering-bin-" + System.nanoTime();
        }
        if(numberClusters == -1)
            numberClusters = MAX_NUMBER_FEATURES;

        // Create the file that will store the persistence database
        this.dynamic = dynamic;

        if(dynamic){
            log.info("----- LEVELDB MAP ------------------------");
            Path tempDirWithPrefix = Files.createTempDirectory(fileName);
            dbFile = tempDirWithPrefix.toFile();
            Options options = new Options();
            options.cacheSize(100 * 1048576); // 100MB cache
            options.createIfMissing(true);
            options.compressionType(CompressionType.SNAPPY);
            levelDB = Iq80DBFactory.factory.open(dbFile, options);

        }else{
            log.info("----- CHRONICLE MAP ------------------------");
            dbFile = File.createTempFile("clustering-bin-" + System.nanoTime(), ".db");
            dbFile.deleteOnExit();
            clusters =
                    ChronicleMapBuilder.of(String.class, ICluster.class)
                            .entries(numberClusters) //the maximum number of entries for the map
                            .averageKeySize(36 + (100 *2))
                            .averageValueSize(6000 + (200*10))
                            .createPersistedTo(dbFile);
        }

    }

    /**
     * BinaryClusterStorage Static with a predefined number of Clusters
     * @param numberClusters number of Clusters
     * @throws IOException
     */
    public BinaryClusterStorage(int numberClusters) throws IOException {
        log.info("----- CHRONICLE MAP ------------------------");
        dbFile = File.createTempFile("clustering-bin-" + System.nanoTime(), ".db");
        dbFile.deleteOnExit();
        clusters =
                ChronicleMapBuilder.of(String.class, ICluster.class)
                        .entries(numberClusters) //the maximum number of entries for the map
                        .averageKeySize(36 + (100 *2))
                        .averageValueSize(4000)  //Todo: Compute this value
                        .createPersistedTo(dbFile);
    }

    @Override
    public void storeCluster(String key, ICluster cluster) {
        if(dynamic){
            try {
                levelDB.put(Iq80DBFactory.bytes(key), cluster.toBytes());
                levelDBSize++;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else
            clusters.put(key, cluster);

    }

    @Override
    public Optional<ICluster> getCluster(String key) {
        ICluster value = null;
        if(dynamic) {
            try {
                value = GreedySpectralCluster.fromBytes(levelDB.get(Iq80DBFactory.bytes(key)));
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        else
            value = clusters.get(key);
        if(value == null)
            return Optional.empty();

        return Optional.of(value);
    }

    /**
     * Delete a Cluster by Key
     * @param key key to be deleted
     * @return True if the cluster has been deleted
     */
    @Override
    public boolean deleteCluster(String key) {
        if(dynamic){
            levelDB.delete(Iq80DBFactory.bytes(key));
            levelDBSize--;
        }
        else
            clusters.remove(key);
        return true;
    }

    /**
     * Delete a Cluster by Key
     * @param keys key to be deleted
     * @return True if the cluster has been deleted
     */
    @Override
    public boolean deleteCluster(String... keys) {
        Arrays.stream(keys).parallel().forEach(this::deleteCluster);
        return true;
    }

    @Override
    public int size() {
        if(dynamic)
            return levelDBSize;
        return clusters.size();
    }

    @Override
    public void saveToFile(String filePath) {

    }

    @Override
    public void readFromFile(String filePath) {

    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public Spectrum next() throws NoSuchElementException {
        return null;
    }

    /**
     * Close the DB on Disk and delete it.
     */
    public void close() {
        if(dynamic) {
            try {
                levelDB.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if(dbFile != null && dbFile.exists()){
            dbFile.deleteOnExit();
        }

    }
}
