package crate.elasticsearch.module.import_.test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsIndices;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.junit.Before;
import org.junit.Test;


import crate.elasticsearch.action.export.ExportAction;
import crate.elasticsearch.action.export.ExportRequest;
import crate.elasticsearch.action.export.ExportResponse;
import crate.elasticsearch.action.import_.ImportAction;
import crate.elasticsearch.action.import_.ImportRequest;
import crate.elasticsearch.action.import_.ImportResponse;
import crate.elasticsearch.module.AbstractRestActionTest;

import static org.elasticsearch.common.io.Streams.copyToStringFromClasspath;

public class RestImportActionTest extends AbstractRestActionTest {

	//private final static Logger logger = Logger.getLogger(RestImportActionTest.class);
	protected final ESLogger logger = ESLoggerFactory.getLogger(this.getClass().getName());

    @Override
    protected int defaultShardCount() {
        return 1;
    }

    @Override
    protected int defaultNodeCount() {
        return 1;
    }

    /**
     * An import directory must be specified in the post data of the request, otherwise
     * an 'No directory defined' exception is delivered in the output.
     */
    @Test
    public void testNoDirectory() {
        ImportResponse response = executeImportRequest("{}");
        assertEquals(0, getImports(response).size());
        List<Map<String, Object>> failures = getImportFailures(response);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).toString().contains("No directory defined"));
    }

    /**
     * A normal import on a single node delivers the node ids of the executing nodes,
     * the time in milliseconds for each node, and the imported files of each nodes
     * with numbers of successful and failing import objects.
     */
    @Test
    public void testImportWithIndexAndType() {
        ClusterStatsIndices cnt = cluster().masterClient().admin().cluster().prepareClusterStats().execute().actionGet().getIndicesStats();
        String path = getClass().getResource("/importdata/import_1").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_1/import_1.json, successes=2, failures=0\\}\\]"));
        assertTrue(existsWithField("102", "name", "102"));
        assertTrue(existsWithField("103", "name", "103"));
    }

    /**
     * If the type or the index are not given whether in the request URI nor
     * in the import line, the corresponding objects are not imported.
     */
    @Test
    public void testImportWithoutIndexOrType() {
        String path = getClass().getResource("/importdata/import_2").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_2/import_2.json, successes=1, failures=3\\}\\]"));
        assertTrue(existsWithField("202", "name", "202"));
        assertFalse(existsWithField("203", "name", "203"));
        assertFalse(existsWithField("204", "name", "204"));
        assertFalse(existsWithField("205", "name", "205"));
    }

    /**
     * Test import using a script tag to modify field
     */
    @Test
    public void testImportWithScriptElementModifyingField() {
        String path = getClass().getResource("/importdata/import_2").getPath();

        ImportResponse response = executeImportRequest("test","d","{\"directory\": \"" + path + "\", \"script\": \"ctx._source.name += ' scripted'; \"}");
        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_2/import_2.json, successes=4, failures=0\\}\\]"));
        assertTrue(existsWithField("202", "name", "202 scripted"));
        assertTrue(existsWithField("203", "name", "203 scripted"));
        assertTrue(existsWithField("204", "name", "204 scripted"));
        assertTrue(existsWithField("205", "name", "205 scripted"));
    }

    /**
     * Test import using a script tag to add field
     */
    @Test
    public void testImportWithScriptElementAddingField() {
        String path = getClass().getResource("/importdata/import_2").getPath();
        ImportResponse response = executeImportRequest("test","d", "{\"directory\": \"" + path + "\", \"script\": \"ctx._source.name2 = ctx._source.name + ' scripted'; \"}");
        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_2/import_2.json, successes=4, failures=0\\}\\]"));
        assertTrue(existsWithField("202", "name", "202"));
        assertTrue(existsWithField("203", "name", "203"));
        assertTrue(existsWithField("204", "name", "204"));
        assertTrue(existsWithField("205", "name", "205"));
        assertTrue(existsWithField("202", "name2", "202 scripted"));
        assertTrue(existsWithField("203", "name2", "203 scripted"));
        assertTrue(existsWithField("204", "name2", "204 scripted"));
        assertTrue(existsWithField("205", "name2", "205 scripted"));
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        prepareCreate("test")
                .setSettings(ImmutableSettings.builder().put("index.number_of_shards", 1).build())
                .addMapping("d",  "{\"d\": {\"_timestamp\": {\"enabled\": true, \"store\": \"yes\"}}}")
                .execute().actionGet();
        this.ensureGreen("test");
        refresh();
    }

    /**
     * Test import using a script tag to modify timestamp and ttl
     */
    @Test
    public void testImportWithScriptElementModifyingTimestampAndTtl() {
        long ts = System.currentTimeMillis();
        long ttl = 60*60*1000;
        long tenSecs = 10*1000;

        String path = getClass().getResource("/importdata/import_4").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\",  \"script\": \"ctx._timestamp = "+ts+"L; ctx._ttl = '60m'; \"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_4/import_4.json, successes=2, failures=0, invalidated=1}]"));

        GetResponse res = get("test", "d", "402", "_ttl", "_timestamp");
        assertEquals(ts, res.getField("_timestamp").getValue());
        assertTrue(ttl > ((Number)res.getField("_ttl").getValue()).longValue());
        assertTrue(ttl - ((Number)res.getField("_ttl").getValue()).longValue() < tenSecs);

        res = get("test", "d", "403", "_ttl", "_timestamp");
        assertEquals(ts, res.getField("_timestamp").getValue());
        assertTrue(ttl > ((Number)res.getField("_ttl").getValue()).longValue());
        assertTrue(ttl - ((Number)res.getField("_ttl").getValue()).longValue() < tenSecs);
    }


    /**
     * Test import using a script tag to delete a record
     */
    @Test
    public void testImportWithScriptElementDeletingRecord() {
        String path = getClass().getResource("/importdata/import_2").getPath();
        ImportResponse response = executeImportRequest("test","d","{\"directory\": \"" + path + "\", \"script\": \"if (ctx._id == '204') ctx.op = 'delete'; \"}");
        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_2/import_2.json, successes=3, failures=0, deletes=1\\}\\]"));
        assertTrue(existsWithField("202", "name", "202"));
        assertTrue(existsWithField("203", "name", "203"));
        assertFalse(exists("204"));
        assertTrue(existsWithField("205", "name", "205"));
    }

    /**
     * If the index and/or type are given in the URI, all objects are imported
     * into the given index/type.
     */
    @Test
    public void testImportIntoIndexAndType() {
        String path = getClass().getResource("/importdata/import_2").getPath();

        ImportResponse response = executeImportRequest("another_index", "e", "{\"directory\": \"" + path + "\"}");

        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_2/import_2.json, successes=4, failures=0\\}\\]"));
        assertTrue(existsWithField("202", "name", "202", "another_index", "e"));
        assertTrue(existsWithField("203", "name", "203", "another_index", "e"));
        assertTrue(existsWithField("204", "name", "204", "another_index", "e"));
        assertTrue(existsWithField("205", "name", "205", "another_index", "e"));
    }

    /**
     * On bad import files, only the readable lines will be imported, the rest is
     * put to the failure count. (e.g. empty lines, or bad JSON structure)
     */
    @Test
    public void testCorruptFile() {
        String path = getClass().getResource("/importdata/import_3").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        assertTrue(imports.get(0).get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_3/import_3.json, successes=3, failures=2\\}\\]"));
    }

    /**
     * The fields _routing, _ttl and _timestamp can be imported. The ttl value
     * is always from now to the end date, no matter if a time stamp value is set.
     * Invalidated objects will not be imported (when actual time is above ttl time stamp).
     */
    @Test
    public void testFields() {
        long now = new Date().getTime();
        long ttl = 1867329687097L - now;
        String path = getClass().getResource("/importdata/import_4").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)/importdata/import_4/import_4.json, successes=2, failures=0, invalidated=1}]"));

        GetResponse res = get("test", "d", "402", "_ttl", "_timestamp", "_routing");
        assertEquals("the_routing", res.getField("_routing").getValue());
        assertTrue(ttl - Long.valueOf(res.getField("_ttl").getValue().toString()) < 10000);
        assertEquals(1367329785380L, res.getField("_timestamp").getValue());

        res = get("test", "d", "403", "_ttl", "_timestamp");
        assertTrue(ttl - Long.valueOf(res.getField("_ttl").getValue().toString()) < 10000);
        assertTrue(now - Long.valueOf(res.getField("_timestamp").getValue().toString()) < 10000);

        assertFalse(existsWithField("404", "name", "404"));
    }

    /**
     * With multiple nodes every node is handled and delivers correct JSON. Every
     * found file in the given directory on the node's system is handled.
     * Note that this test runs two nodes on the same file system, so the same
     * files are imported twice.
     */
    @Test
    public void testMultipleFilesAndMultipleNodes() {
        cluster().ensureAtMostNumNodes(2);
        cluster().ensureAtLeastNumNodes(2);
        String path = getClass().getResource("/importdata/import_5").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(2, imports.size());

        String result = "\\[\\{file_name=(.*)/importdata/import_5/import_5_[ab].json, successes=1, failures=0\\}, \\{file_name=(.*)import_5_[ab].json, successes=1, failures=0\\}\\]";
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(result));
        nodeInfo = imports.get(1);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(result));

        assertTrue(existsWithField("501", "name", "501"));
        assertTrue(existsWithField("511", "name", "511"));
    }

    /**
     * Some failures may occur in the bulk request results, like Version conflicts.
     * The failures are counted correctly.
     */
    @Test
    public void testFailures() {
        String path = getClass().getResource("/importdata/import_6").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\"}");
        List<Map<String, Object>> imports = getImports(response);
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)import_6.json, successes=1, failures=1\\}\\]"));
    }

    /**
     * With the compression flag set to 'gzip' zipped files will be unzipped before
     * importing.
     */
    @Test
    public void testCompression() {
        String path = getClass().getResource("/importdata/import_7").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"compression\":\"gzip\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        Map<String, Object> nodeInfo = imports.get(0);
        assertNotNull(nodeInfo.get("node_id"));
        assertTrue(Long.valueOf(nodeInfo.get("took").toString()) > 0);
        assertTrue(nodeInfo.get("imported_files").toString().matches(
                "\\[\\{file_name=(.*)import_7.json.gz, successes=2, failures=0\\}\\]"));
        assertTrue(existsWithField("102", "name", "102"));
        assertTrue(existsWithField("103", "name", "103"));
    }

    /**
     * Using a relative directory leads to an import from within each node's export
     * directory in the data path. This test also covers the export - import combination.
     */
    @Test
    public void testImportRelativeFilename() throws IOException {
        cluster().ensureAtMostNumNodes(2);
        cluster().ensureAtLeastNumNodes(2);

        // create sample data
        setupTestIndexLikeUsers("other", true);

        makeNodeDataLocationDirectories("myExport");

        // export data and recreate empty index
        ExportRequest exportRequest = new ExportRequest("other");
        exportRequest.source("{\"output_file\": \"myExport/export.${shard}.${index}.json\", \"fields\": [\"_source\", \"_id\", \"_index\", \"_type\"], \"force_overwrite\": true}");
        cluster().masterClient().execute(ExportAction.INSTANCE, exportRequest).actionGet();

        wipeIndices("other");
        setupTestIndexLikeUsers("other", false);

        // run import with relative directory
        ImportResponse response = executeImportRequest("{\"directory\": \"myExport\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(2, imports.size());
        String regex = "\\[\\{file_name=(.*)/nodes/(\\d)/myExport/export.(\\d).other.json, successes=4, failures=0\\}\\]";
        assertTrue(imports.get(0).get("imported_files").toString().matches(regex) || imports.get(1).get("imported_files").toString().matches(regex));

        assertTrue(existsWithField("1", "name", "car", "other", "d"));
        assertTrue(existsWithField("2", "name", "bike", "other", "d"));
        assertTrue(existsWithField("3", "name", "train", "other", "d"));
        assertTrue(existsWithField("4", "name", "bus", "other", "d"));
    }

    /**
     * A file pattern can be specified to filter only for files with a given regex.
     * The other files are not imported.
     */
    @Test
    public void testFilePattern() {
        String path = getClass().getResource("/importdata/import_8").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"file_pattern\": \"index_test_(.*).json\"}");
        List<Map<String, Object>> imports = getImports(response);
        assertEquals(1, imports.size());
        Map<String, Object> nodeInfo = imports.get(0);
        List imported = (List) nodeInfo.get("imported_files");
        assertTrue(imported.size() == 1);
        assertTrue(imported.get(0).toString().matches(
                "\\{file_name=(.*)/importdata/import_8/index_test_1.json, successes=2, failures=0\\}"));
        assertTrue(existsWithField("802", "name", "802", "test", "d"));
        assertTrue(existsWithField("803", "name", "803", "test", "d"));
        assertFalse(existsWithField("811", "name", "811", "test", "d"));
        assertFalse(existsWithField("812", "name", "812", "test", "d"));
    }

    /**
     * A bad regex pattern leads to a failure response.
     */
    @Test
    public void testBadFilePattern() {
        String path = getClass().getResource("/importdata/import_8").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"file_pattern\": \"(.*(d|||\"}");
        List<Map<String, Object>> failures = getImportFailures(response);
        assertEquals(1, failures.size());
        assertTrue(failures.toString().contains("PatternSyntaxException: Unclosed group near index"));
    }

    @Test
    public void testSettings() {
        String path = getClass().getResource("/importdata/import_9").getPath();
        executeImportRequest("{\"directory\": \"" + path + "\", \"settings\": true}");

        ClusterStateRequest clusterStateRequest = Requests.clusterStateRequest().metaData(true).indices("index1");
        IndexMetaData stats = admin().cluster().state(clusterStateRequest).actionGet().getState().metaData().index("index1");
        assertEquals(2, stats.numberOfShards());
        assertEquals(1, stats.numberOfReplicas());
    }

    @Test
    public void testSettingsNotFound() {
        String path = getClass().getResource("/importdata/import_1").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"settings\": true}");
        List<Map<String, Object>> failures = getImportFailures(response);
        assertTrue(failures.get(0).get("reason").toString().matches(
                "(.*)Settings file (.*)/importdata/import_1/import_1.json.settings could not be found.(.*)"));
    }

    @Test
    public void testMappingsWithoutIndex() {
        String path = getClass().getResource("/importdata/import_9").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"mappings\": true}");
        List<Map<String, Object>> failures = getImportFailures(response);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).get("reason").toString().contains("Unable to create mapping. Index index1 missing."));
    }

    @Test
    public void testMappings() {
        createIndex("index1");
        ensureGreen("index1");

        String path = getClass().getResource("/importdata/import_9").getPath();
        executeImportRequest("{\"directory\": \"" + path + "\", \"mappings\": true}");

        ClusterStateRequest clusterStateRequest = Requests.clusterStateRequest().metaData(true).indices("index1");
        ImmutableOpenMap<String, MappingMetaData> mappings =
            admin().cluster().state(clusterStateRequest).actionGet().getState().metaData().index("index1").getMappings();
        assertEquals("{\"1\":{\"_timestamp\":{\"enabled\":true,\"store\":true},\"_ttl\":{\"enabled\":true,\"default\":86400000},\"properties\":{\"name\":{\"type\":\"string\",\"store\":true}}}}",
                mappings.get("1").source().toString());
    }

    @Test
    public void testMappingNotFound() {
        createIndex("index1");
        ensureGreen("index1");

        String path = getClass().getResource("/importdata/import_1").getPath();
        ImportResponse response = executeImportRequest("{\"directory\": \"" + path + "\", \"mappings\": true}");
        List<Map<String, Object>> failures = getImportFailures(response);
        assertTrue(failures.get(0).get("reason").toString().matches(
                "(.*)Mapping file (.*)/importdata/import_1/import_1.json.mapping could not be found.(.*)"));
    }

    /**
     * Make a subdirectory in each node's node data location.
     * @param directory
     */
    private void makeNodeDataLocationDirectories(String directory) {
        ExportRequest exportRequest = new ExportRequest();
        exportRequest.source("{\"output_file\": \"" + directory + "\", \"fields\": [\"_source\", \"_id\", \"_index\", \"_type\"], \"force_overwrite\": true, \"explain\": true}");
        ExportResponse explain = cluster().masterClient().execute(ExportAction.INSTANCE, exportRequest).actionGet();

        try {
            Map<String, Object> res = toMap(explain);
            List<Map<String, String>> list = (ArrayList<Map<String, String>>) res.get("exports");
            for (Map<String, String> map : list) {
                new File(map.get("output_file").toString()).mkdir();
            }
        } catch (IOException e) {
        }
    }

    private boolean existsWithField(String id, String field, String value) {
        return existsWithField(id, field, value, "test", "d");
    }

    private boolean existsWithField(String id, String field, String value, String index, String type) {
        GetResponse res = get(index, type, id); // rb.setType(type).setId(id).execute().actionGet();
        return res.isExists() && res.getSourceAsMap().get(field).equals(value);
    }

    private boolean exists(String id) {
        return exists(id, "test", "d");
    }

    private boolean exists(String id, String index, String type) {
        GetResponse res = get(index, type, id); // rb.setType(type).setId(id).execute().actionGet();
        return res.isExists();
    }

    private static List<Map<String, Object>> getImports(ImportResponse resp) {
        return get(resp, "imports");
    }

    private static List<Map<String, Object>> getImportFailures(ImportResponse resp) {
        return get(resp, "failures");
    }

    private static List<Map<String, Object>> get(ImportResponse resp, String key) {
        Map<String, Object> res = null;
        try {
            res = toMap(resp);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return (List<Map<String, Object>>) res.get(key);
    }

    private ImportResponse executeImportRequest(String source) {
        ImportRequest request = new ImportRequest();
        request.source(source);
        return cluster().masterClient().execute(ImportAction.INSTANCE, request).actionGet();
    }

    private ImportResponse executeImportRequest(String index, String type, String source) {
        ImportRequest request = new ImportRequest();
        request.index(index);
        request.type(type);
        request.source(source);
        return cluster().masterClient().execute(ImportAction.INSTANCE, request).actionGet();
    }


}
