package crate.elasticsearch.searchinto;

import crate.elasticsearch.action.searchinto.SearchIntoContext;
import crate.elasticsearch.searchinto.mapping.MappedFields;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.text.StringAndBytesText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.fieldvisitor.*;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.FieldMappers;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.source.FetchSourceContext;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHitField;

import java.io.IOException;
import java.util.*;

import static org.elasticsearch.common.collect.Lists.newArrayList;

public abstract class WriterCollector extends Collector {

	protected final ESLogger logger = ESLoggerFactory.getLogger(this.getClass().getName());
    
    private List<String> extractFieldNames;
    protected FieldsVisitor fieldsVisitor;
    protected SearchIntoContext context;
    protected MappedFields mappedFields;
    protected FetchSubPhase[] fetchSubPhases;
    boolean sourceRequested;
    private IndexReader currentReader;
    private long numExported = 0;

    private AtomicReaderContext arc;

    public WriterCollector() {
    }

    public abstract void open() throws WriterException;

    public abstract void close() throws WriterException;

    public abstract WriterResult getResult();

    public abstract void collectHit(SearchHit hit) throws IOException;


    @Override
    public void collect(int doc) throws IOException {
        fieldsVisitor.reset();
        currentReader.document(doc, fieldsVisitor);

        Map<String, SearchHitField> searchFields = null;
        if (fieldsVisitor.fields() != null) {
            searchFields = new HashMap<String, SearchHitField>(
                    fieldsVisitor.fields().size());
            for (Map.Entry<String, List<Object>> entry : fieldsVisitor
                    .fields().entrySet()) {
                searchFields.put(entry.getKey(), new InternalSearchHitField(
                        entry.getKey(), entry.getValue()));
            }
        }

        DocumentMapper documentMapper = context.mapperService().documentMapper(
                fieldsVisitor.uid().type());
        Text typeText;
        if (documentMapper == null) {
            typeText = new StringAndBytesText(fieldsVisitor.uid().type());
        } else {
            typeText = documentMapper.typeText();
        }

        InternalSearchHit searchHit = new InternalSearchHit(doc,
                fieldsVisitor.uid().id(), typeText,
                searchFields).sourceRef(fieldsVisitor.source());

        // it looks like it is safe to reuse the HitContext,
        // the cache is only used by the highlighter which we do not use.
        FetchSubPhase.HitContext hitContext = new FetchSubPhase.HitContext();
        for (FetchSubPhase fetchSubPhase : fetchSubPhases) {
            if (fetchSubPhase.hitExecutionNeeded(context)) {
                hitContext.reset(searchHit, arc, doc,
                        context.searcher().getIndexReader(), doc,
                        fieldsVisitor);
                fetchSubPhase.hitExecute(context, hitContext);
            }
        }
        searchHit.shardTarget(context.shardTarget());
        collectHit(searchHit);
        numExported++;
    }


    @Inject
    public WriterCollector(SearchIntoContext context,
            FetchSubPhase[] fetchSubPhases) {
        this.context = context;
        this.fetchSubPhases = fetchSubPhases;
        this.mappedFields = new MappedFields(context);
        if (!context.hasFieldNames()) {
            if (context.hasPartialFields()) {
                // partial fields need the source, so fetch it
                fieldsVisitor = new UidAndSourceFieldsVisitor();
            } else {
                // no fields specified, default to return source if no explicit indication
                if (!context.hasScriptFields() && !context.hasFetchSourceContext()) {
                    context.fetchSourceContext(new FetchSourceContext(true));
                }
                fieldsVisitor = context.sourceRequested() ? new UidAndSourceFieldsVisitor() : new JustUidFieldsVisitor();
            }
        } else if (context.fieldNames().isEmpty()) {
            if (context.sourceRequested()) {
                fieldsVisitor = new UidAndSourceFieldsVisitor();
            } else {
                fieldsVisitor = new JustUidFieldsVisitor();
            }
        } else {
            boolean loadAllStored = false;
            Set<String> fieldNames = null;
            for (String fieldName : context.fieldNames()) {
                if (fieldName.equals("*")) {
                    loadAllStored = true;
                    continue;
                }
                if (fieldName.equals(SourceFieldMapper.NAME)) {
                    if (context.hasFetchSourceContext()) {
                        context.fetchSourceContext().fetchSource(true);
                    } else {
                        context.fetchSourceContext(new FetchSourceContext(true));
                    }
                    continue;
                }
                FieldMappers x = context.smartNameFieldMappers(fieldName);
                if (x != null && x.mapper().fieldType().stored()) {
                    if (fieldNames == null) {
                        fieldNames = new HashSet<String>();
                    }
                    fieldNames.add(x.mapper().names().indexName());
                } else {
                    if (extractFieldNames == null) {
                        extractFieldNames = newArrayList();
                    }
                    extractFieldNames.add(fieldName);
                }
            }
            if (loadAllStored) {
                fieldsVisitor = new AllFieldsVisitor(); // load everything, including _source
            } else if (fieldNames != null) {
                boolean loadSource = extractFieldNames != null || context.sourceRequested();
                fieldsVisitor = new CustomFieldsVisitor(fieldNames, loadSource);
            } else if (extractFieldNames != null || context.sourceRequested()) {
                fieldsVisitor = new UidAndSourceFieldsVisitor();
            } else {
                fieldsVisitor = new JustUidFieldsVisitor();
            }
        }

    }


    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
        this.arc = context;
        this.currentReader = context.reader();
    }

    @Override
    public boolean acceptsDocsOutOfOrder() {
        return true;
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
    }


}
