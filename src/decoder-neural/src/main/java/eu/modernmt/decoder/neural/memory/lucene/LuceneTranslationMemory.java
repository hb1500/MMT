package eu.modernmt.decoder.neural.memory.lucene;

import eu.modernmt.data.DataBatch;
import eu.modernmt.data.Deletion;
import eu.modernmt.data.TranslationUnit;
import eu.modernmt.decoder.neural.memory.ScoreEntry;
import eu.modernmt.decoder.neural.memory.TranslationMemory;
import eu.modernmt.decoder.neural.memory.lucene.query.DefaultQueryBuilder;
import eu.modernmt.decoder.neural.memory.lucene.query.QueryBuilder;
import eu.modernmt.decoder.neural.memory.lucene.query.rescoring.F1BleuRescorer;
import eu.modernmt.decoder.neural.memory.lucene.query.rescoring.Rescorer;
import eu.modernmt.lang.LanguagePair;
import eu.modernmt.model.ContextVector;
import eu.modernmt.model.Sentence;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Created by davide on 23/05/17.
 */
public class LuceneTranslationMemory implements TranslationMemory {

    private final int minQuerySize;
    private final Directory indexDirectory;
    private final QueryBuilder queryBuilder;
    private final Rescorer rescorer;
    private final IndexWriter indexWriter;

    private DirectoryReader _indexReader;
    private IndexSearcher _indexSearcher;
    private final Map<Short, Long> channels;

    private static File forceMkdir(File directory) throws IOException {
        if (!directory.isDirectory())
            FileUtils.forceMkdir(directory);
        return directory;
    }

    public LuceneTranslationMemory(File indexPath, int minQuerySize) throws IOException {
        this(indexPath, new F1BleuRescorer(), minQuerySize);
    }

    public LuceneTranslationMemory(Directory directory, int minQuerySize) throws IOException {
        this(directory, new F1BleuRescorer(), minQuerySize);
    }

    public LuceneTranslationMemory(File indexPath, Rescorer rescorer, int minQuerySize) throws IOException {
        this(FSDirectory.open(forceMkdir(indexPath)), rescorer, minQuerySize);
    }

    public LuceneTranslationMemory(File indexPath, QueryBuilder queryBuilder, Rescorer rescorer, int minQuerySize) throws IOException {
        this(FSDirectory.open(forceMkdir(indexPath)), queryBuilder, rescorer, minQuerySize);
    }

    public LuceneTranslationMemory(Directory directory, Rescorer rescorer, int minQuerySize) throws IOException {
        this(directory, new DefaultQueryBuilder(), rescorer, minQuerySize);
    }

    public LuceneTranslationMemory(Directory directory, QueryBuilder queryBuilder, Rescorer rescorer, int minQuerySize) throws IOException {
        this.indexDirectory = directory;
        this.queryBuilder = queryBuilder;
        this.rescorer = rescorer;
        this.minQuerySize = minQuerySize;

        // Index writer setup
        IndexWriterConfig indexConfig = new IndexWriterConfig(Version.LUCENE_4_10_4, Analyzers.getTrainAnalyzer());
        indexConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexConfig.setSimilarity(new CustomSimilarity());

        this.indexWriter = new IndexWriter(this.indexDirectory, indexConfig);

        // Ensure index exists
        if (!DirectoryReader.indexExists(directory))
            this.indexWriter.commit();

        // Read channels status
        IndexSearcher searcher = this.getIndexSearcher();

        Query query = new TermQuery(DocumentBuilder.makeChannelsTerm());
        TopDocs docs = searcher.search(query, 1);

        if (docs.scoreDocs.length > 0) {
            Document channelsDocument = searcher.doc(docs.scoreDocs[0].doc);
            this.channels = DocumentBuilder.asChannels(channelsDocument);
        } else {
            this.channels = new HashMap<>();
        }
    }

    protected synchronized IndexReader getIndexReader() throws IOException {
        if (this._indexReader == null) {
            this._indexReader = DirectoryReader.open(this.indexDirectory);
            this._indexReader.incRef();
            this._indexSearcher = new IndexSearcher(this._indexReader);
        } else {
            DirectoryReader reader = DirectoryReader.openIfChanged(this._indexReader);

            if (reader != null) {
                this._indexReader.close();
                this._indexReader = reader;
                this._indexReader.incRef();

                this._indexSearcher = new IndexSearcher(this._indexReader);
                this._indexSearcher.setSimilarity(new CustomSimilarity());
            }
        }

        return this._indexReader;
    }

    public IndexSearcher getIndexSearcher() throws IOException {
        getIndexReader();
        return this._indexSearcher;
    }

    public IndexWriter getIndexWriter() {
        return this.indexWriter;
    }

    public void dump(Consumer<ScoreEntry> consumer) throws IOException {
        IndexSearcher searcher = getIndexSearcher();
        IndexReader reader = getIndexReader();

        int size = reader.numDocs();
        if (size == 0)
            return;

        TopDocs docs = searcher.search(new MatchAllDocsQuery(), size);

        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            Document document = reader.document(scoreDoc.doc);
            if (DocumentBuilder.getMemory(document) > 0) {
                ScoreEntry entry = DocumentBuilder.asEntry(document);
                consumer.accept(entry);
            }
        }
    }

    // TranslationMemory

    @Override
    public ScoreEntry[] search(UUID user, LanguagePair direction, Sentence source, int limit) throws IOException {
        return search(user, direction, source, null, this.rescorer, limit);
    }

    public ScoreEntry[] search(UUID user, LanguagePair direction, Sentence source, Rescorer rescorer, int limit) throws IOException {
        return search(user, direction, source, null, rescorer, limit);
    }

    @Override
    public ScoreEntry[] search(UUID user, LanguagePair direction, Sentence source, ContextVector contextVector, int limit) throws IOException {
        return search(user, direction, source, contextVector, this.rescorer, limit);
    }

    public ScoreEntry[] search(UUID user, LanguagePair direction, Sentence source, ContextVector contextVector, Rescorer rescorer, int limit) throws IOException {
        Query query = this.queryBuilder.bestMatchingSuggestion(user, direction, source, contextVector);

        IndexSearcher searcher = getIndexSearcher();

        int queryLimit = Math.max(this.minQuerySize, limit * 2);
        ScoreDoc[] docs = searcher.search(query, queryLimit).scoreDocs;

        ScoreEntry[] entries = new ScoreEntry[docs.length];
        for (int i = 0; i < docs.length; i++) {
            entries[i] = DocumentBuilder.asEntry(searcher.doc(docs[i].doc), direction);
            entries[i].score = docs[i].score;
        }

        if (rescorer != null)
            entries = rescorer.rescore(direction, source, entries, contextVector);

        if (entries.length > limit) {
            ScoreEntry[] temp = new ScoreEntry[limit];
            System.arraycopy(entries, 0, temp, 0, limit);
            entries = temp;
        }

        return entries;
    }

    // DataListener

    @Override
    public void onDataReceived(DataBatch batch) throws IOException {
        boolean success = false;

        try {
            this.onTranslationUnitsReceived(batch.getTranslationUnits());
            this.onDeletionsReceived(batch.getDeletions());

            // Writing channels
            HashMap<Short, Long> newChannels = new HashMap<>(this.channels);
            for (Map.Entry<Short, Long> entry : batch.getChannelPositions().entrySet()) {
                Long position = entry.getValue();
                Long existingPosition = newChannels.get(entry.getKey());

                if (existingPosition == null || existingPosition < position)
                    newChannels.put(entry.getKey(), position);
            }

            Document channelsDocument = DocumentBuilder.newChannelsInstance(newChannels);
            this.indexWriter.updateDocument(DocumentBuilder.makeChannelsTerm(), channelsDocument);
            this.indexWriter.commit();

            this.channels.putAll(newChannels);

            success = true;
        } finally {
            if (!success)
                this.indexWriter.rollback();
        }
    }

    @Override
    public boolean needsProcessing() {
        return true;
    }

    @Override
    public boolean needsAlignment() {
        return true;
    }

    private void onTranslationUnitsReceived(Collection<TranslationUnit> units) throws IOException {
        for (TranslationUnit unit : units) {
            Long currentPosition = this.channels.get(unit.channel);

            if (currentPosition == null || currentPosition < unit.channelPosition) {
                if (unit.rawPreviousSentence != null && unit.rawPreviousTranslation != null) {
                    String hash = HashGenerator.hash(unit.rawPreviousSentence, unit.rawPreviousTranslation);
                    Query hashQuery = this.queryBuilder.getByHash(unit.memory, hash);

                    this.indexWriter.deleteDocuments(hashQuery);
                }

                Document document = DocumentBuilder.newInstance(unit);
                this.indexWriter.addDocument(document);
            }
        }
    }

    private void onDeletionsReceived(Collection<Deletion> deletions) throws IOException {
        for (Deletion deletion : deletions) {
            Long currentPosition = this.channels.get(deletion.channel);

            if (currentPosition == null || currentPosition < deletion.channelPosition)
                this.indexWriter.deleteDocuments(DocumentBuilder.makeMemoryTerm(deletion.memory));
        }
    }

    @Override
    public Map<Short, Long> getLatestChannelPositions() {
        return channels;
    }

    // Closeable

    @Override
    public void close() {
        IOUtils.closeQuietly(this._indexReader);
        IOUtils.closeQuietly(this.indexWriter);
        IOUtils.closeQuietly(this.indexDirectory);
    }

}
