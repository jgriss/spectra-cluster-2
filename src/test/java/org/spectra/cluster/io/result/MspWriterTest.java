package org.spectra.cluster.io.result;

import io.github.bigbio.pgatk.io.objectdb.LongObject;
import io.github.bigbio.pgatk.io.objectdb.ObjectsDB;
import io.github.bigbio.pgatk.io.properties.IPropertyStorage;
import io.github.bigbio.pgatk.io.properties.InMemoryPropertyStorage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.spectra.cluster.cdf.MinNumberComparisonsAssessor;
import org.spectra.cluster.consensus.AverageConsensusSpectrumBuilder;
import org.spectra.cluster.engine.GreedyClusteringEngine;
import org.spectra.cluster.filter.binaryspectrum.HighestPeakPerBinFunction;
import org.spectra.cluster.filter.rawpeaks.*;
import org.spectra.cluster.io.cluster.ObjectDBGreedyClusterStorage;
import org.spectra.cluster.io.spectra.MzSpectraReader;
import org.spectra.cluster.model.cluster.GreedySpectralCluster;
import org.spectra.cluster.model.cluster.ICluster;
import org.spectra.cluster.model.cluster.IClusterProperties;
import org.spectra.cluster.model.consensus.GreedyConsensusSpectrum;
import org.spectra.cluster.normalizer.BasicIntegerNormalizer;
import org.spectra.cluster.normalizer.MaxPeakNormalizer;
import org.spectra.cluster.normalizer.TideBinner;
import org.spectra.cluster.predicates.ShareHighestPeaksClusterPredicate;
import org.spectra.cluster.similarity.CombinedFisherIntensityTest;
import org.spectra.cluster.util.ClusteringParameters;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class MspWriterTest {
    Path testDir;

    @Before
    public void setUp() throws Exception {
        testDir = Files.createTempDirectory("clusters-");
    }

    @Test
    public void testMspWriting() throws Exception {
        // ignore the property storage for now
        IPropertyStorage propertyStorage = new InMemoryPropertyStorage();

        IRawSpectrumFunction loadingFilter = new RemoveImpossiblyHighPeaksFunction()
                .specAndThen(new RemovePrecursorPeaksFunction(0.5))
                .specAndThen(new RawPeaksWrapperFunction(new KeepNHighestRawPeaks(40)));

        // create a basic clustering engine for testing
        GreedyClusteringEngine engine = new GreedyClusteringEngine(BasicIntegerNormalizer.MZ_CONSTANT,
                1, 0.99f, 5, new CombinedFisherIntensityTest(),
                new MinNumberComparisonsAssessor(10000), new ShareHighestPeaksClusterPredicate(5),
                GreedyConsensusSpectrum.NOISE_FILTER_INCREMENT);

        URI[] mgfFiles = new URI[] {
                getClass().getClassLoader().getResource("same_sequence_cluster.mgf").toURI(),
                getClass().getClassLoader().getResource("synthetic_mixed_runs.mgf").toURI()};
        File[] inFiles = Arrays.stream(mgfFiles).map(File::new).toArray(File[]::new);

        // read all files at once
        MzSpectraReader reader = new MzSpectraReader(new TideBinner(), new MaxPeakNormalizer(),
                new BasicIntegerNormalizer(), new HighestPeakPerBinFunction(), loadingFilter,
                GreedyClusteringEngine.COMPARISON_FILTER, engine, inFiles);

        // create the iterator
        Iterator<ICluster> iterator = reader.readClusterIterator(propertyStorage);

        // keep track of the cluster ids
        List<IClusterProperties> clusterProperties = new ArrayList<>(10_000);

        // create the output file
        Path clusteringResult = Paths.get(testDir.toString(), "clustering_result.cls");
        ObjectDBGreedyClusterStorage clusterStorage = new ObjectDBGreedyClusterStorage(new ObjectsDB(clusteringResult.toString(), true));

        while (iterator.hasNext()) {
            GreedySpectralCluster c = (GreedySpectralCluster) iterator.next();
            clusterStorage.addGreedySpectralCluster(LongObject.asLongHash(c.getId()), c);
        }

        clusterStorage.writeDBMode();
        clusterStorage.flush();

        // convert
        Path mspFile = Paths.get(testDir.toString(), "clusters.msp");

        MspWriter resultWriter = new MspWriter(new AverageConsensusSpectrumBuilder(new ClusteringParameters()));
        resultWriter.writeResult(mspFile, clusterStorage, propertyStorage);

        // check that everything worked
        Assert.assertTrue(Files.exists(mspFile));

        List<String> lines = Files.readAllLines(mspFile);
        Assert.assertEquals("Name: +42.011EVQLVETGGGLIQPGGSLR/2", lines.get(0));
        Assert.assertEquals("Comment: Spec=Consensus Parent=977.0230 Mods=1(0,[,Acetyl) Nreps=1 Naa=26 MaxRatio=1.000", lines.get(1));
        Assert.assertEquals("Num peaks: 50", lines.get(2));
    }

    @Test
    public void testExtractPtms() {
        MspWriter writer = new MspWriter(new AverageConsensusSpectrumBuilder(new ClusteringParameters()));
        String sequence = "+42.011EVQLVET+42.011GGGLIQPGGSLR+42.011";

        List<MspWriter.MspMod> mods = writer.extractModsFromSequence(sequence);

        Assert.assertEquals(3, mods.size());

        Assert.assertEquals(0, mods.get(0).getPosition());
        Assert.assertEquals("Acetyl", mods.get(0).getName());
        Assert.assertEquals("[", mods.get(0).getAminoAcid());
    }

    @Test
    public void testGetModString() {
        MspWriter writer = new MspWriter(new AverageConsensusSpectrumBuilder(new ClusteringParameters()));
        String sequence = "+42.011EVQLVET+42.011GGGLIQPGGSLR+42.011";

        String modString = writer.getModString(sequence);

        Assert.assertEquals("3(0,[,Acetyl)(7,T,Acetyl)(19,],Acetyl)", modString);
    }
}