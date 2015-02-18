package com.linkedin.pinot.core.realtime.converter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.linkedin.pinot.common.data.FieldSpec.DataType;
import com.linkedin.pinot.common.data.GranularitySpec;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.data.TimeFieldSpec;
import com.linkedin.pinot.core.data.extractors.FieldExtractor;
import com.linkedin.pinot.core.data.extractors.FieldExtractorFactory;
import com.linkedin.pinot.core.data.readers.FileFormat;
import com.linkedin.pinot.core.data.readers.RecordReader;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.indexsegment.generator.SegmentVersion;
import com.linkedin.pinot.core.realtime.impl.RealtimeSegmentImpl;
import com.linkedin.pinot.core.segment.creator.impl.SegmentIndexCreationDriverImpl;


public class RealtimeSegmentConverter {

  private RealtimeSegmentImpl realtimeSegmentImpl;
  private String outputPath;
  private Schema dataSchema;
  private String resourceName;
  private String tableName;

  public RealtimeSegmentConverter(RealtimeSegmentImpl realtimeSegment, String outputPath, Schema schema,
      String resourceName, String tableName) {
    realtimeSegmentImpl = realtimeSegment;
    this.outputPath = outputPath;
    if (new File(outputPath).exists())
      throw new IllegalAccessError("path already exists");
    this.resourceName = resourceName;
    this.tableName = tableName;

    TimeFieldSpec original = schema.getTimeSpec();
    GranularitySpec incoming = original.getIncominGranularutySpec();
    incoming.setdType(DataType.LONG);

    TimeFieldSpec newTimeSpec = new TimeFieldSpec(incoming);

    Schema newSchema = new Schema();
    for (String dimension : schema.getDimensions())
      newSchema.addSchema(dimension, schema.getFieldSpecFor(dimension));
    for (String metic : schema.getMetrics())
      newSchema.addSchema(metic, schema.getFieldSpecFor(metic));

    newSchema.addSchema(newTimeSpec.getName(), newTimeSpec);
    this.dataSchema = newSchema;
  }

  public void build() throws Exception {
    // lets create a record reader
    RecordReader reader = new RealtimeSegmentRecordReader(realtimeSegmentImpl, dataSchema);
    FieldExtractor extractor = FieldExtractorFactory.getPlainFieldExtractor(dataSchema);

    SegmentGeneratorConfig genConfig = new SegmentGeneratorConfig(dataSchema);
    genConfig.setInputFilePath(null);

    genConfig.setTimeColumnName(dataSchema.getTimeSpec().getOutGoingTimeColumnName());

    genConfig.setTimeUnitForSegment(dataSchema.getTimeSpec().getOutgoingGranularitySpec().getTimeType());
    genConfig.setInputFileFormat(FileFormat.REALTIME);
    genConfig.setSegmentVersion(SegmentVersion.v1);
    genConfig.setResourceName(resourceName);
    genConfig.setTableName(tableName);
    genConfig.setIndexOutputDir(outputPath);

    final SegmentIndexCreationDriverImpl driver = new SegmentIndexCreationDriverImpl();
    driver.init(genConfig, reader);
    driver.build();
  }
}