package com.linkedin.pinot.raw.record.readers;

import com.linkedin.pinot.segments.generator.SegmentGeneratorConfiguration;


public class RecordReaderFactory {
  public static RecordReader get(final SegmentGeneratorConfiguration segmentCreationSpec) throws Exception {
    if (segmentCreationSpec.getInputFileFormat() == null) {
      throw new UnsupportedOperationException("No input format property!");
    }
    if (segmentCreationSpec.getInputFileFormat() == FileFormat.avro) {
      System.out.println("creating avro");
      return new AvroRecordReader(segmentCreationSpec);
    }
    throw new UnsupportedOperationException("Not support input format: " + segmentCreationSpec.getInputFileFormat());
  }
}