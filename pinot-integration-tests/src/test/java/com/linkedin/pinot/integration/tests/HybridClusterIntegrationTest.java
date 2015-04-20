package com.linkedin.pinot.integration.tests;

import com.linkedin.pinot.common.KafkaTestUtils;
import com.linkedin.pinot.common.ZkTestUtils;
import com.linkedin.pinot.common.utils.FileUploadUtils;
import com.linkedin.pinot.common.utils.TarGzCompressionUtils;
import com.linkedin.pinot.util.TestUtils;
import java.io.File;
import java.io.FileInputStream;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kafka.server.KafkaServerStartable;
import org.apache.commons.io.FileUtils;
import org.apache.helix.ExternalViewChangeListener;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.NotificationContext;
import org.apache.helix.model.ExternalView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Hybrid cluster integration test that uploads 8 months of data as offline and 6 months of data as realtime (with a
 * two month overlap).
 *
 * @author jfim
 */
public class HybridClusterIntegrationTest extends BaseClusterIntegrationTest {
  private final File _tmpDir = new File("/tmp/HybridClusterIntegrationTest");
  private static final String KAFKA_TOPIC = "hybrid-integration-test";

  private static final int SEGMENT_COUNT = 12;
  private static final int QUERY_COUNT = 1000;
  private static final int OFFLINE_SEGMENT_COUNT = 8;
  private static final int REALTIME_SEGMENT_COUNT = 6;

  private KafkaServerStartable kafkaStarter;

  @Override
  protected String getHelixClusterName() {
    return "HybridClusterIntegrationTest";
  }

  @BeforeClass
  public void setUp() throws Exception {
    // Start Zk and Kafka
    startZk();
    kafkaStarter = KafkaTestUtils.startServer(KafkaTestUtils.DEFAULT_KAFKA_PORT, KafkaTestUtils.DEFAULT_BROKER_ID,
        KafkaTestUtils.DEFAULT_ZK_STR, KafkaTestUtils.getDefaultKafkaConfiguration());

    // Create Kafka topic
    KafkaTestUtils.createTopic(KAFKA_TOPIC, KafkaTestUtils.DEFAULT_ZK_STR);

    // Start the Pinot cluster
    startController();
    startBroker();
    startServers(2);

    // Unpack the Avro files
    TarGzCompressionUtils.unTar(new File(TestUtils.getFileFromResourceUrl(
        OfflineClusterIntegrationTest.class.getClassLoader()
            .getResource("On_Time_On_Time_Performance_2014_100k_subset.tar.gz"))), _tmpDir);

    _tmpDir.mkdirs();

    final List<File> avroFiles = new ArrayList<File>(SEGMENT_COUNT);
    for (int segmentNumber = 1; segmentNumber <= SEGMENT_COUNT; ++segmentNumber) {
      avroFiles.add(new File(_tmpDir.getPath() + "/On_Time_On_Time_Performance_2014_" + segmentNumber + ".avro"));
    }

    // Create a data resource
    createHybridResource("myresource", "DaysSinceEpoch", "daysSinceEpoch", KafkaTestUtils.DEFAULT_ZK_STR, KAFKA_TOPIC, avroFiles.get(0));

    // Add table to resource
    addTableToHybridResource("myresource", "mytable", "DaysSinceEpoch", "daysSinceEpoch");

    // Create a subset of the first 8 segments (for offline) and the last 6 segments (for realtime)
    final List<File> offlineAvroFiles = new ArrayList<File>(OFFLINE_SEGMENT_COUNT);
    for (int i = 0; i < OFFLINE_SEGMENT_COUNT; i++) {
      offlineAvroFiles.add(avroFiles.get(i));
    }

    final List<File> realtimeAvroFiles = new ArrayList<File>(REALTIME_SEGMENT_COUNT);
    for (int i = SEGMENT_COUNT - REALTIME_SEGMENT_COUNT; i < SEGMENT_COUNT; i++) {
      realtimeAvroFiles.add(avroFiles.get(i));
    }

    // Load data into H2
    ExecutorService executor = Executors.newCachedThreadPool();
    Class.forName("org.h2.Driver");
    _connection = DriverManager.getConnection("jdbc:h2:mem:");
    executor.execute(new Runnable() {
      @Override
      public void run() {
        createH2SchemaAndInsertAvroFiles(avroFiles, _connection);
      }
    });

    // Create segments from Avro data
    buildSegmentsFromAvro(offlineAvroFiles, executor, 0, _tmpDir);

    // Initialize query generator
    executor.execute(new Runnable() {
      @Override
      public void run() {
        _queryGenerator = new QueryGenerator(avroFiles, "'myresource.mytable'", "mytable");
      }
    });

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.MINUTES);

    // Set up a Helix spectator to count the number of segments that are uploaded and unlock the latch once 12 segments are online
    final CountDownLatch latch = new CountDownLatch(1);
    HelixManager manager =
        HelixManagerFactory.getZKHelixManager(getHelixClusterName(), "test_instance", InstanceType.SPECTATOR,
            ZkTestUtils.DEFAULT_ZK_STR);
    manager.connect();
    manager.addExternalViewChangeListener(new ExternalViewChangeListener() {
      @Override
      public void onExternalViewChange(List<ExternalView> externalViewList, NotificationContext changeContext) {
        for (ExternalView externalView : externalViewList) {
          if(externalView.getId().contains("myresource")) {

            Set<String> partitionSet = externalView.getPartitionSet();
            if (partitionSet.size() == OFFLINE_SEGMENT_COUNT) {
              int onlinePartitionCount = 0;

              for (String partitionId : partitionSet) {
                Map<String, String> partitionStateMap = externalView.getStateMap(partitionId);
                if (partitionStateMap.containsValue("ONLINE")) {
                  onlinePartitionCount++;
                }
              }

              if (onlinePartitionCount == OFFLINE_SEGMENT_COUNT) {
                System.out.println("Got " + OFFLINE_SEGMENT_COUNT + " online resources, unlatching the main thread");
                latch.countDown();
              }
            }
          }
        }
      }
    });

    // Upload the segments
    for(int i = 1; i <= OFFLINE_SEGMENT_COUNT; ++i) {
      System.out.println("Uploading segment " + i);
      File file = new File(_tmpDir, "myresource_mytable_" + i);
      FileUploadUtils.sendFile("localhost", "8998", "myresource_mytable_" + i, new FileInputStream(file), file.length());
    }

    // Wait for all offline segments to be online
    latch.await();

    // Load realtime data into Kafka
    pushAvroIntoKafka(realtimeAvroFiles, KafkaTestUtils.DEFAULT_KAFKA_BROKER, KAFKA_TOPIC);

    // Wait until the Pinot event count matches with the number of events in the Avro files
    int pinotRecordCount, h2RecordCount;
    Statement statement = _connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    do {
      Thread.sleep(5000L);

      // Run the query
      JSONObject response = postQuery("select count(*) from 'myresource.mytable'");
      JSONArray aggregationResultsArray = response.getJSONArray("aggregationResults");
      JSONObject firstAggregationResult = aggregationResultsArray.getJSONObject(0);
      String pinotValue = firstAggregationResult.getString("value");
      pinotRecordCount = Integer.parseInt(pinotValue);

      statement.execute("select count(*) from mytable");
      ResultSet rs = statement.getResultSet();
      rs.first();
      h2RecordCount = rs.getInt(1);
      rs.close();
      System.out.println("H2 record count: " + h2RecordCount + "\tPinot record count: " + pinotRecordCount);
    } while (h2RecordCount != pinotRecordCount);
  }

  @Test
  public void testMultipleQueries() throws Exception {
    QueryGenerator.Query[] queries = new QueryGenerator.Query[QUERY_COUNT];
    for (int i = 0; i < queries.length; i++) {
      queries[i] = _queryGenerator.generateQuery();
    }

    for (QueryGenerator.Query query : queries) {
      System.out.println(query.generatePql());

      runQuery(query.generatePql(), query.generateH2Sql());
    }
  }

  @AfterClass
  public void tearDown() throws Exception {
    stopBroker();
    stopController();
    stopOfflineServer();
    KafkaTestUtils.stopServer(kafkaStarter);
    stopZk();
    FileUtils.deleteDirectory(_tmpDir);
  }
}