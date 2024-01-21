package com.serwylo.lexica.language;

import com.serwylo.lexica.game.Board;
import com.serwylo.lexica.game.CharProbGenerator;
import com.serwylo.lexica.lang.Language;
import com.serwylo.lexica.trie.util.LetterFrequency;

import net.healeys.trie.StringTrie;
import net.healeys.trie.Trie;
import net.healeys.trie.WordFilter;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeneticAlgorithm {

    private static final int NUM_OF_FITTEST_TO_COPY = 1;

    /**
     * In case a run gets stuck in a local optimum that it can't break out of, start an entire
     * fresh run with new random genomes this many times.
     */
    private static final int SEPARATE_RUNS = 5;
    private static final int ITERATIONS = 1000;
    private static final int NUM_OF_GENOMES = 20;
    public static final int FITNESS_CALC_BOARDS_TO_GENERATE = 100;
    private static final double RATE_OF_MUTATION = 0.05;
    private static final double RATE_OF_NEW_RANDOM_GENOMES = 0.05;
    private static final int MAX_BEST_TO_KEEP = NUM_OF_GENOMES / 4;
    private static final boolean PENALISE_INFREQUENT_LETTERS = false;

    /**
     * From experience, it seems that the more we focus on a low standard deviation, the better
     * quality boards we get (as long as the mean number of words is high enough). However, if
     * we weigh the standard deviation too high, then it will always result in boards with a score
     * of zero, which breaks the roulette wheel selection in the next stage. In that case, reduce
     * this to a lower number.
     */
    private static final double FITNESS_CALC_STANDARD_DEVIATION_MULTIPLIER = 2;

    public void run(File trieDir, File dictionaryDir, File outputDir, Language language) throws IOException, InterruptedException {
        for (int i = 0; i < SEPARATE_RUNS; i++) {
            Genome best = generateProbabilityDistribution(trieDir, dictionaryDir, language);

            System.out.println("[" + language.getName() + ", run " + (i + 1) + "]");
            System.out.println(best.toString());
            System.out.println("Random board:");
            System.out.println(renderBoardToString(best.toCharProbGenerator().generateFourByFourBoard()));

            writeDistribution(language, outputDir, best);
        }
    }

    private void writeDistribution(Language language, File outputDir, Genome genome) throws IOException {

        String fileName = language.getName() + " " + "Min: " + (int) genome.getFitness().stats.getMin() + " " + "Mean: " + (int) genome.getFitness().stats.getMean() + " " + "Max: " + (int) genome.getFitness().stats.getMax() + " " + "SD: " + (int) genome.getFitness().stats.getStandardDeviation() + " " + "Score: " + (int) genome.getFitness().getScore();

        File output = new File(outputDir, fileName);
        FileWriter writer = new FileWriter(output);
        writer.write("# " + "\n" + "# " + language.getName() + " language letter distributions for Lexica\n" + "# " + "\n" + "# Automatically generated by a genetic algorithm run with " + ITERATIONS + " iterations\n" + "#  - Each iteration, " + FITNESS_CALC_BOARDS_TO_GENERATE + " 4 x 4 Lexica boards were generated.\n" + "#  - The fitness function looks at how many words can be played on each of these boards.\n" + "#  - Fitness is defined as \"(Mean * Mean) - (Standard Deviation * Standard Deviation).\n" + "#\n" + "# The goal is boards that on average have a high number of words available (boards with zero or only a small number of words are not good),\n" + "# but the standard deviation is low (some languages tend to result in boards with hundreds of words, which is also not particularly great).\n" + "#\n" + "# Fitness for this probability:\n" + "#   " + genome.getFitness().toString() + "\n" + "# " + "\n" + "\n" + genome.toString() + "\n");
        writer.close();
    }

    private static String renderBoardToString(Board board) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < board.getWidth(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                String value = board.valueAt(x + y * board.getWidth());
                sb.append(value).append("\t");
            }
            sb.append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private Genome generateProbabilityDistribution(File trieDir, File dictionaryDir, Language language) throws IOException, InterruptedException {

        List<Genome> currentPopulation = new ArrayList<>();
        for (int genomeNum = 0; genomeNum < NUM_OF_GENOMES; genomeNum++) {
            currentPopulation.add(Genome.createRandom(trieDir, dictionaryDir, language));
        }

        sortInPlaceByFitness(currentPopulation);

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {

            List<Genome> nextPopulation = new ArrayList<>(currentPopulation.size());

            SummaryStatistics stats = summariseGenomeScores(currentPopulation);
            for (Genome genome : currentPopulation) {
                // Add the best scoring genome straight into the new population
                if ((int) genome.getFitness().getScore() == (int) stats.getMax() && nextPopulation.size() < MAX_BEST_TO_KEEP) {
                    nextPopulation.add(genome);
                }
            }

            while (nextPopulation.size() < currentPopulation.size()) {
                // 10% of the time throw in a new random genome to refresh the population.
                if (Math.random() < 0.1) {
                    nextPopulation.add(Genome.createRandom(trieDir, dictionaryDir, language));
                } else {
                    Genome mother = selectByFitness(currentPopulation);
                    Genome father = selectByFitness(currentPopulation);
                    nextPopulation.add(mother.breedWith(dictionaryDir, father));
                }
            }

            sortInPlaceByFitness(nextPopulation);

            Fitness best = nextPopulation.get(nextPopulation.size() - 1).getFitness();

            System.out.println("Iteration: " + (iteration + 1) + " (" + (int) best.getScore() + ") [" + best + "]");

            currentPopulation = nextPopulation;
        }

        return currentPopulation.get(currentPopulation.size() - 1);
    }

    private static class CalcFitness implements Callable<Fitness> {

        private final Genome genome;

        private CalcFitness(Genome genome) {
            this.genome = genome;
        }

        @Override
        public Fitness call() throws Exception {
            return genome.getFitness();
        }
    }

    private static SummaryStatistics summariseGenomeScores(List<Genome> genomes) throws IOException {
        SummaryStatistics stats = new SummaryStatistics();
        for (Genome genome : genomes) {
            stats.addValue(genome.getFitness().getScore());
        }
        return stats;
    }

    private static Genome selectByFitness(List<Genome> genomes) throws IOException {
        double total = 0;
        for (Genome genome : genomes) {
            total += genome.getFitness().getScore();
        }

        double selection = Math.random() * total;

        double tally = 0;
        for (Genome genome : genomes) {
            tally += genome.getFitness().getScore();
            if (tally > selection) {
                return genome;
            }
        }

        throw new IllegalStateException("Should have chosen one of the genomes, but didn't.");
    }

    private static void sortInPlaceByFitness(List<Genome> genomes) throws InterruptedException {

        Collection<Callable<Fitness>> tasks = new ArrayList<>(genomes.size());
        for (Genome genome : genomes) {
            tasks.add(new CalcFitness(genome));
        }
        ExecutorService executor = Executors.newFixedThreadPool(20);
        executor.invokeAll(tasks);
        executor.shutdown();

        Collections.sort(genomes, (lhs, rhs) -> {
            try {
                return (int) (lhs.getFitness().getScore() - rhs.getFitness().getScore());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

    }

    public static class Fitness {

        private final SummaryStatistics stats;

        public static Fitness calc(File trieDir, CharProbGenerator charProbGenerator, Language language) throws IOException {
            return calc(trieDir, charProbGenerator, language, FITNESS_CALC_BOARDS_TO_GENERATE);
        }

        public static Fitness calc(File trieDir, CharProbGenerator charProbGenerator, Language language, int work) throws IOException {
            return new Fitness(generateStats(trieDir, charProbGenerator, language, work));
        }

        private static Map<Language, byte[]> cachedTries = new HashMap<>();

        private static InputStream trieReader(File trieDir, Language language) throws IOException {
            if (!cachedTries.containsKey(language)) {
                byte[] buffer = new byte[1024 * 1024 * 10]; // 10MiB - Needs to be able to fit the largest "words_*.bin file in memory.

                File trieFile = new File(trieDir, language.getTrieFileName());
                InputStream stream = new FileInputStream(trieFile);
                int read = stream.read(buffer);
                byte[] total = new byte[read];
                System.arraycopy(buffer, 0, total, 0, read);
                cachedTries.put(language, total);
            }

            return new ByteArrayInputStream(cachedTries.get(language));
        }

        private static SummaryStatistics generateStats(File trieDir, CharProbGenerator charProbGenerator, Language language, int iterations) throws IOException {
            SummaryStatistics stats = new SummaryStatistics();
            for (int i = 0; i < iterations; i++) {
                Board board = new CharProbGenerator(charProbGenerator).generateFourByFourBoard();
                InputStream stream = trieReader(trieDir, language);
                Trie dict = new StringTrie.Deserializer().deserialize(stream, board, language);
                int numWords = dict.solver(board, new WordFilter.MinLength(3)).size();
                stats.addValue(numWords);
            }
            return stats;
        }

        Fitness(SummaryStatistics stats) {
            this.stats = stats;
        }

        double getScore() {

            return Math.max(1, stats.getMean() * stats.getMean() - stats.getStandardDeviation() * stats.getStandardDeviation() * FITNESS_CALC_STANDARD_DEVIATION_MULTIPLIER);

        }

        private String cachedStringRepresentation = null;

        public String toString() {
            if (cachedStringRepresentation == null) {
                cachedStringRepresentation = "Min: " + (int) stats.getMin() + ", mean: " + (int) stats.getMean() + ", max: " + (int) stats.getMax() + ", stddev: " + (int) stats.getStandardDeviation() + ", score: " + (int) getScore();
            }

            return cachedStringRepresentation;
        }
    }

    static class Gene {

        public static Gene createRandom(String letter, LetterFrequency frequencies) {
            int initialMax = PENALISE_INFREQUENT_LETTERS
                    ? (int)( (double)frequencies.getTotalCountForLetter(letter) / frequencies.getMaxCount() * 99)
                    : 99;

            int currentCount = (int) (Math.random() * Math.max(initialMax, 10)) + 1; // 1 to [max|99] inclusive.
            List<Integer> occurrences = new ArrayList<>();

            for (int i = 1; i <= frequencies.getCountsForLetter(letter).size(); i++) {
                occurrences.add(currentCount);
                int max = Math.max(0, currentCount - (i * 10));
                currentCount = Math.max(1, (int) (Math.random() * max));
            }

            return new Gene(letter, occurrences);
        }

        final String letter;
        final List<Integer> occurrences;

        public Gene(String letter, List<Integer> occurrences) {
            this.letter = letter;
            this.occurrences = occurrences;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(letter);
            for (int count : occurrences) {
                sb.append(' ').append(count);
            }
            return sb.toString();
        }

    }

    static class Genome {

        static Genome createRandom(File trieDir, File dictionaryDir, Language language) throws IOException {
            LetterFrequency letters = allLetters(dictionaryDir, language);
            List<Gene> genes = new ArrayList<>(letters.getLetters().size());
            for (String letter : letters.getLetters()) {
                genes.add(Gene.createRandom(letter, letters));
            }

            return new Genome(trieDir, language, genes);
        }

        static Map<Language, LetterFrequency> letterFrequencies = new HashMap<>();

        private static LetterFrequency allLetters(File dictionaryDir, Language language) throws IOException {
            LetterFrequency cachedFrequencies = letterFrequencies.get(language);
            if (cachedFrequencies != null) {
                return cachedFrequencies;
            }

            File dictionaryFile = new File(dictionaryDir, language.getDictionaryFileName());
            InputStream stream = new FileInputStream(dictionaryFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")));
            LetterFrequency letters = new LetterFrequency(language);

            String line;
            while ((line = br.readLine()) != null) {
                String word = line.toLowerCase(language.getLocale());
                letters.addWord(word);
            }

            letterFrequencies.put(language, letters);
            return letters;
        }

        final File trieDir;
        final Language language;
        final List<Gene> genes;
        private String cachedStringRepresentation = null;

        Genome(File trieDir, Language language, List<Gene> genes) {
            this.trieDir = trieDir;
            this.language = language;
            this.genes = genes;
        }

        public String toString() {
            if (cachedStringRepresentation == null) {
                StringBuilder sb = new StringBuilder(500);
                boolean first = true;
                for (Gene gene : genes) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append('\n');
                    }

                    sb.append(gene.letter);
                    for (Integer occurrence : gene.occurrences) {
                        sb.append(' ');
                        sb.append(occurrence);
                    }
                }
                cachedStringRepresentation = sb.toString();
            }

            return cachedStringRepresentation;
        }

        private CharProbGenerator toCharProbGenerator() {
            return new CharProbGenerator(new ByteArrayInputStream(toString().getBytes()), language);
        }

        private Fitness cachedFitness = null;

        Fitness getFitness() throws IOException {
            if (cachedFitness == null) {
                cachedFitness = Fitness.calc(trieDir, toCharProbGenerator(), language);
            }

            return cachedFitness;
        }

        public Genome breedWith(File dictionaryDir, Genome mate) throws IOException {
            List<Gene> child = new ArrayList<>(genes.size());
            for (int i = 0; i < genes.size(); i++) {
                double random = Math.random();
                if (random < 0.05) {
                    child.add(Gene.createRandom(genes.get(i).letter, allLetters(dictionaryDir, language)));
                } else if (random < 0.5) {
                    child.add(genes.get(i));
                } else {
                    child.add(mate.genes.get(i));
                }
            }
            return new Genome(trieDir, language, child);
        }
    }
}
