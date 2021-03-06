package de.exciteproject.pdf_evaluation.refextract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pl.edu.icm.cermine.bibref.BibReferenceExtractor;
import pl.edu.icm.cermine.bibref.extraction.features.PrevEndsWithDotFeature;
import pl.edu.icm.cermine.bibref.extraction.features.PrevRelativeLengthFeature;
import pl.edu.icm.cermine.bibref.extraction.features.RelativeStartTresholdFeature;
import pl.edu.icm.cermine.bibref.extraction.features.SpaceBetweenLinesFeature;
import pl.edu.icm.cermine.bibref.extraction.features.StartsWithNumberFeature;
import pl.edu.icm.cermine.bibref.extraction.model.BxDocumentBibReferences;
import pl.edu.icm.cermine.bibref.extraction.tools.BibRefExtractionUtils;
import pl.edu.icm.cermine.content.cleaning.ContentCleaner;
import pl.edu.icm.cermine.exception.AnalysisException;
import pl.edu.icm.cermine.structure.model.BxDocument;
import pl.edu.icm.cermine.structure.model.BxLine;
import pl.edu.icm.cermine.tools.classification.clustering.KMeansWithInitialCentroids;
import pl.edu.icm.cermine.tools.classification.general.FeatureCalculator;
import pl.edu.icm.cermine.tools.classification.general.FeatureVector;
import pl.edu.icm.cermine.tools.classification.general.FeatureVectorBuilder;
import pl.edu.icm.cermine.tools.distance.FeatureVectorDistanceMetric;
import pl.edu.icm.cermine.tools.distance.FeatureVectorEuclideanMetric;

/**
 * Clustering-based bibliographic reference extractor.
 *
 * @author Dominika Tkaczyk (d.tkaczyk@icm.edu.pl)
 */
public class CermineModKMeansBibReferenceExtractor implements BibReferenceExtractor {

    public static final int MAX_REF_LINES_COUNT = 10000000;

    public static final int MAX_REFS_COUNT = 100000;

    public static final int MAX_REF_LENGTH = 1500;

    private static final FeatureVectorBuilder<BxLine, BxDocumentBibReferences> VECTOR_BUILDER = new FeatureVectorBuilder<BxLine, BxDocumentBibReferences>();
    static {
        VECTOR_BUILDER.setFeatureCalculators(Arrays.<FeatureCalculator<BxLine, BxDocumentBibReferences>> asList(
                new PrevEndsWithDotFeature(), new PrevRelativeLengthFeature(), new RelativeStartTresholdFeature(),
                new SpaceBetweenLinesFeature(), new StartsWithNumberFeature()));
    }

    /**
     * Extracts individual bibliographic references from the document. The
     * references lines are clustered based on feature vector computed for them.
     * The cluster containing the first line is then assumed to be the set of
     * all first lines, which allows for splitting references blocks into
     * individual references.
     *
     * @param document
     *            document
     * @return an array of extracted references
     * @throws AnalysisException
     *             AnalysisException
     */
    @Override
    public String[] extractBibReferences(BxDocument document) throws AnalysisException {
        BxDocumentBibReferences documentReferences = BibRefExtractionUtils.extractBibRefLines(document);
        documentReferences.limit(MAX_REF_LINES_COUNT);

        List<String> lines = new ArrayList<String>();
        List<FeatureVector> instances = new ArrayList<FeatureVector>();
        FeatureVectorDistanceMetric metric = new FeatureVectorEuclideanMetric();
        FeatureVector farthestInstance = null;
        double farthestDistance = 0;
        for (BxLine line : documentReferences.getLines()) {
            lines.add(ContentCleaner.clean(line.toText()));
            FeatureVector featureVector = VECTOR_BUILDER.getFeatureVector(line, documentReferences);
            instances.add(featureVector);
            if (farthestInstance == null) {
                farthestInstance = instances.get(0);
            }
            double distance = metric.getDistance(instances.get(0), featureVector);
            if (distance > farthestDistance) {
                farthestInstance = featureVector;
                farthestDistance = distance;
            }
        }

        if ((lines.size() <= 1) || (farthestDistance < 0.001)) {
            if (lines.size() > MAX_REFS_COUNT) {
                return new String[] {};
            } else {
                return lines.toArray(new String[lines.size()]);
            }
        }

        KMeansWithInitialCentroids clusterer = new KMeansWithInitialCentroids(2);
        FeatureVector[] centroids = new FeatureVector[2];
        centroids[0] = instances.get(0);
        centroids[1] = farthestInstance;
        clusterer.setCentroids(centroids);
        List<FeatureVector>[] clusters = clusterer.cluster(instances);

        int firstInstanceClusterNum = 0;
        if (clusters[1].contains(instances.get(0))) {
            firstInstanceClusterNum = 1;
        }

        List<String> references = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            if (clusters[firstInstanceClusterNum].contains(instances.get(i))) {
                this.addReferenceToList("B-REF\t" + lines.get(i) + "\n", references);
            } else {
                this.addReferenceToList("I-REF\t" + lines.get(i) + "\n", references);
            }
        }

        if (references.size() > MAX_REFS_COUNT) {
            references.clear();
        }
        return references.toArray(new String[references.size()]);
    }

    private void addReferenceToList(String stringToAdd, List<String> list) {
        if (!stringToAdd.isEmpty() && stringToAdd.replaceAll("\n", " ").matches(".*[0-9].*")
                && stringToAdd.replaceAll("\n", " ").matches(".*[a-zA-Z].*")
                && (stringToAdd.length() < MAX_REF_LENGTH)) {
            list.add(stringToAdd.replaceFirst("\\n$", ""));
        }
    }

}