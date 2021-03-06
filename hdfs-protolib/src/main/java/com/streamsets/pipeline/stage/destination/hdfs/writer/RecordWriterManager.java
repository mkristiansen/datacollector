/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.hdfs.writer;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.EventRecord;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.WholeFileExistsAction;
import com.streamsets.pipeline.lib.el.FakeRecordEL;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.stage.destination.hdfs.Errors;
import com.streamsets.pipeline.stage.destination.hdfs.HdfsFileType;
import com.streamsets.pipeline.stage.destination.hdfs.HdfsTarget;
import com.streamsets.pipeline.stage.destination.hdfs.IdleClosedException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class RecordWriterManager {
  private final static Logger LOG = LoggerFactory.getLogger(RecordWriterManager.class);
  private final static String TMP_FILE_PREFIX = "_tmp_";

  private FileSystem fs;
  private Configuration hdfsConf;
  private String uniquePrefix;
  private boolean dirPathTemplateInHeader;
  private String dirPathTemplate;
  private PathResolver pathResolver;
  private TimeZone timeZone;
  private long cutOffMillis;
  private long cutOffSize;
  private long cutOffRecords;
  private HdfsFileType fileType;
  private CompressionCodec compressionCodec;
  private SequenceFile.CompressionType compressionType;
  private String keyEL;
  private DataGeneratorFactory generatorFactory;
  private Target.Context context;
  private long idleTimeoutSeconds = -1L;
  private final boolean rollIfHeader;
  private final String rollHeaderName;
  private final FsHelper fsHelper;
  private ConcurrentLinkedQueue<Path> closedPaths;

  public RecordWriterManager(FileSystem fs, Configuration hdfsConf, String uniquePrefix, boolean dirPathTemplateInHeader,
      String dirPathTemplate, TimeZone timeZone, long cutOffSecs, long cutOffSizeBytes, long cutOffRecords,
      HdfsFileType fileType, CompressionCodec compressionCodec, SequenceFile.CompressionType compressionType, String keyEL,
      boolean rollIfHeader, String rollHeaderName, String fileNameEL, WholeFileExistsAction wholeFileAlreadyExistsAction,
      DataGeneratorFactory generatorFactory, Target.Context context, String config) {
    this.fs = fs;
    this.hdfsConf = hdfsConf;
    this.uniquePrefix = uniquePrefix;
    this.dirPathTemplateInHeader = dirPathTemplateInHeader;
    this.dirPathTemplate = dirPathTemplate;
    this.timeZone = timeZone;
    this.cutOffMillis = preventOverflow(cutOffSecs * 1000);
    this.cutOffSize = cutOffSizeBytes;
    this.cutOffRecords = cutOffRecords;
    this.fileType = fileType;
    this.compressionCodec = compressionCodec;
    this.compressionType = compressionType;
    this.keyEL = keyEL;
    this.generatorFactory = generatorFactory;
    this.context = context;
    this.rollIfHeader = rollIfHeader;
    this.rollHeaderName = rollHeaderName;
    pathResolver = new PathResolver(context, config, dirPathTemplate, timeZone);
    fsHelper = getFsHelper(context, fileNameEL, wholeFileAlreadyExistsAction);
    this.closedPaths = new ConcurrentLinkedQueue<>();
  }

  public boolean validateDirTemplate(
      String group,
      String config,
      String qualifiedConfigName,
      List<Stage.ConfigIssue> issues
  ) {
    return pathResolver.validate(group, config, qualifiedConfigName, issues);
  }

  public void setIdleTimeoutSeconds(long idleTimeoutSeconds) {
    this.idleTimeoutSeconds = idleTimeoutSeconds;
  }

  public long getCutOffMillis() {
    return cutOffMillis;
  }

  public long getCutOffSizeBytes() {
    return cutOffSize;
  }

  public long getCutOffRecords() {
    return cutOffRecords;
  }

  /**
   * Returns directory path for given record and date.
   */
  String getDirPath(Date date, Record record) throws StageException {
    if(dirPathTemplateInHeader) {
      // We're not validating if the header exists as that job is already done
      return record.getHeader().getAttribute(HdfsTarget.TARGET_DIRECTORY_HEADER);
    }
    return pathResolver.resolvePath(date, record);
  }

  String getExtension() {
    return (compressionCodec == null) ? "" : compressionCodec.getDefaultExtension();
  }

  String getTempFileName() {
    return TMP_FILE_PREFIX + uniquePrefix + getExtension();
  }

  public String getDirPath(Date date) throws StageException {
    return pathResolver.resolvePath(date, null);
  }

  public Path getPath(Date recordDate, Record record) throws StageException, IOException {
    return fsHelper.getPath(recordDate, record);
  }

  /**
   * This method should be called every time we finish writing into a file and consider it "done".
   */
  Path renameToFinalName(FileSystem fs, Path tempPath) throws IOException, StageException {
    Path finalPath = fsHelper.getRenamableFinalFilePath(tempPath);
    if (!fs.rename(tempPath, finalPath)) {
      throw new IOException(Utils.format("Could not rename '{}' to '{}'", tempPath, finalPath));
    }

    // Store closed path so that we can generate event for it later
    closedPaths.add(finalPath);

    return finalPath;
  }

  private void produceCloseFileEvent(FileSystem fs, Path finalPath) throws IOException {
    FileStatus status = fs.getFileStatus(finalPath);
    EventRecord event = context.createEventRecord("file-closed", 1);
    Map<String, Field> map = new ImmutableMap.Builder<String, Field>()
      .put("filepath", Field.create(Field.Type.STRING, finalPath.toString()))
      .put("length", Field.create(Field.Type.LONG, status.getLen()))
      .build();
    event.set(Field.create(map));
    context.toEvent(event);
  }

  /**
   * Produce events that were cached during the batch processing.
   */
  public void issueCachedEvents() throws IOException {
    Path closedPath;
    while((closedPath = closedPaths.poll()) != null) {
      produceCloseFileEvent(fs, closedPath);
    }
  }

  long getTimeToLiveMillis(Date now, Date recordDate) {
    // Getting max date doesn't make sense when path is in record. We're retuning Long.MAX_VALUE which is the same case
    // as when the target path doesn't contain any date-related information.
    if(dirPathTemplateInHeader) {
      return Long.MAX_VALUE;
    }
    // we up the record date to the greatest one based on the template
    recordDate = pathResolver.getCeilingDate(recordDate);
    if (recordDate != null) {
      return preventOverflow(recordDate.getTime() + cutOffMillis) - now.getTime();
    } else {
      return Long.MAX_VALUE;
    }
  }

  RecordWriter createWriter(FileSystem fs, Path path, long timeToLiveMillis) throws StageException, IOException {
    switch (fileType) {
      case WHOLE_FILE:
        OutputStream wholeFileOs = fsHelper.create(fs, path);
        //No need to set idle timeout, because it does not make sense
        //we are copying the whole file.
        return new RecordWriter(path, timeToLiveMillis, wholeFileOs, generatorFactory);
      case TEXT:
        OutputStream os = fsHelper.create(fs, path);
        if (compressionCodec != null) {
          try {
            os = compressionCodec.createOutputStream(os);
          } catch (UnsatisfiedLinkError unsatisfiedLinkError) {
            throw new StageException(Errors.HADOOPFS_46, compressionType.name(), unsatisfiedLinkError,
              unsatisfiedLinkError);
          }
        }
        RecordWriter recordWriter = new RecordWriter(path, timeToLiveMillis, os, generatorFactory);
        if (idleTimeoutSeconds != -1) {
          recordWriter.setIdleTimeout(idleTimeoutSeconds);
        }
        return recordWriter;
      case SEQUENCE_FILE:
        Utils.checkNotNull(compressionType, "compressionType");
        Utils.checkNotNull(keyEL, "keyEL");
        Utils.checkArgument(compressionCodec == null || compressionType != SequenceFile.CompressionType.NONE,
                            "if using a compressionCodec, compressionType cannot be NULL");
        try {
          SequenceFile.Writer writer = SequenceFile.createWriter(fs, hdfsConf, path, Text.class, Text.class,
                                                                 compressionType, compressionCodec);
          RecordWriter seqRecordWriter =
              new RecordWriter(path, timeToLiveMillis, writer, keyEL, generatorFactory, context);
          if (idleTimeoutSeconds != -1) {
            seqRecordWriter.setIdleTimeout(idleTimeoutSeconds);
          }
          return seqRecordWriter;
        } catch (UnsatisfiedLinkError unsatisfiedLinkError) {
          throw new StageException(Errors.HADOOPFS_46, compressionType.name(), unsatisfiedLinkError,
            unsatisfiedLinkError);
        }
      default:
        throw new UnsupportedOperationException(Utils.format("Unsupported file Type '{}'", fileType));
    }
  }

  public RecordWriter getWriter(Date now, Date recordDate, Record record) throws StageException, IOException {
    RecordWriter writer = null;
    long writerTimeToLive = getTimeToLiveMillis(now, recordDate);
    Path tempPath = getPath(recordDate, record);
    if (writerTimeToLive >= 0) {
      if (fs.exists(tempPath)) {
        fsHelper.handleAlreadyExistingFile(fs, tempPath);
      }
      LOG.debug("Path[{}] - Create writer,  time to live '{}ms'", tempPath, writerTimeToLive);
      writer = createWriter(fs, tempPath, writerTimeToLive);
    } else {
      LOG.warn("Path[{}] - Cannot not create writer, requested date already cut off", tempPath);
    }
    return writer;
  }

  /**
   * This method must always be called after the closeLock() method on the writer has been called.
   */
  public Path commitWriter(RecordWriter writer) throws IOException, StageException {
    Path path = null;
    if (!writer.isClosed() || writer.isIdleClosed()) {
      // Unset the interrupt flag before close(). InterruptedIOException makes close() fail
      // resulting that the tmp file never gets renamed when stopping the pipeline.
      boolean interrupted = Thread.interrupted();
      try {
        // Since this method is always called from exactly one thread, and
        // we checked to make sure that it was not closed or it was idle closed, this method either closes
        // the file or pushes us into the catch block.
        writer.close();
      } catch (IdleClosedException e) {
        LOG.info("Writer for {} was idle closed, renaming.." , writer.getPath());
      }

      LOG.debug("Path[{}] - Committing Writer", writer.getPath());
      path = renameToFinalName(fs, writer.getPath());
      LOG.debug("Path[{}] - Committed Writer to '{}'", writer.getPath(), path);
      // Reset the interrupt flag back.
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return path;
  }

  /**
   * Return true if this record should be written into a new file regardless whether we have a file for the record
   * currently opened or not.
   */
  public boolean shouldRoll(Record record) {
    if(rollIfHeader && record.getHeader().getAttribute(rollHeaderName) != null) {
      return true;
    }
    return false;
  }

  public boolean isOverThresholds(RecordWriter writer) throws IOException {
    boolean overLength = (cutOffSize > 0) && writer.getLength() >= cutOffSize;
    boolean overRecords = (cutOffRecords > 0) && writer.getRecords() >= cutOffRecords;
    boolean over = overLength || overRecords;
    if (over) {
      LOG.debug("Path[{}] - Over threshold, length={} records={}", writer.getPath(), overLength, overRecords);
    }
    return over;
  }

  Date incrementDate(Date date, int timeIncrement) {
    Calendar calendar = Calendar.getInstance(timeZone);
    calendar.setTime(date);
    calendar.add(timeIncrement, 1);
    return calendar.getTime();
  }

  String createGlob(Calendar calendar) throws ELEvalException {
    ELVars vars = context.createELVars();
    ELEval elEval = context.createELEval("dirPathTemplate", FakeRecordEL.class);
    TimeEL.setCalendarInContext(vars, calendar);
    String dirGlob = elEval.eval(vars, dirPathTemplate, String.class);
    return dirGlob + "/" + getTempFileName();
  }

  List<String> getGlobs() throws ELEvalException {
    List<String> globs = new ArrayList<>();
    Calendar endCalendar = Calendar.getInstance(timeZone);
    if (pathResolver.getTimeIncrementUnit() > -1) {

      // we need to scan dirs from last batch minus the cutOff time
      Calendar calendar = Calendar.getInstance(timeZone);
      calendar.setTime(new Date(context.getLastBatchTime()));
      calendar.add(Calendar.MILLISECOND, (int) -cutOffMillis);
      // adding an extra hour to scan
      calendar.add(Calendar.HOUR, -1);

      // set the calendar to the floor date of the computed start time
      calendar.setTime(pathResolver.getFloorDate(calendar.getTime()));

      LOG.info("Looking for uncommitted files from '{}' onwards", calendar.getTime());

      // iterate from last batch time until now at the dir template minimum time precision increments
      // we create a glob for each template time tick
      for (; calendar.compareTo(endCalendar) < 0;
           calendar.add(pathResolver.getTimeIncrementUnit(), pathResolver.getTimeIncrementValue())) {
        globs.add(createGlob(calendar));
      }
    } else {
      // in this case we don't use the calendar at all as the dir template does not use time functions
      globs.add(createGlob(endCalendar));
    }
    return globs;
  }

  public void commitOldFiles(FileSystem fs) throws IOException, StageException {
    fsHelper.commitOldFiles(fs);
  }

  private long preventOverflow(long valueToVerify) {
    return (valueToVerify > 0) ? valueToVerify : Long.MAX_VALUE;
  }

  private interface FsHelper {
    Path getPath(Date recordDate, Record record) throws StageException, IOException;
    void commitOldFiles(FileSystem fs) throws StageException, IOException;
    void handleAlreadyExistingFile(FileSystem fs, Path tempPath) throws IOException, StageException;
    Path getRenamableFinalFilePath(Path tempPath) throws IOException, StageException;
    OutputStream create(FileSystem fs, Path path) throws IOException;
  }

  private FsHelper getFsHelper(final Stage.Context context, final String fileNameEL, final WholeFileExistsAction wholeFileAlreadyExistsAction) {
    if (!fileNameEL.isEmpty()) {
      //WHOLE_FILE
      return new FsHelper() {
        private String getTempFile(Date recordDate, Record record) throws StageException {
          ELEval elEval = context.createELEval("fileNameEL");
          ELVars vars = context.createELVars();
          RecordEL.setRecordInContext(vars, record);
          TimeNowEL.setTimeNowInContext(vars, recordDate);
          return TMP_FILE_PREFIX + uniquePrefix + elEval.eval(vars, fileNameEL, String.class);
        }

        private void checkAndHandleWholeFileExistence(Path renamableFinalPath) throws IOException, OnRecordErrorException {
          if (fs.exists(renamableFinalPath)) {
            //whole file exists.
            if (wholeFileAlreadyExistsAction == WholeFileExistsAction.OVERWRITE) {
              fs.delete(renamableFinalPath, false);
              LOG.debug(Utils.format(Errors.HADOOPFS_54.getMessage(), renamableFinalPath) + "so deleting it");
            } else {
              throw new OnRecordErrorException(Errors.HADOOPFS_54, renamableFinalPath);
            }
          }
        }

        @Override
        public Path getPath(Date recordDate, Record record) throws StageException, IOException {
          //Check whether the real file already exists
          Path path = new Path(getDirPath(recordDate, record), getTempFile(recordDate, record));
          //this will check the file exists.
          getRenamableFinalFilePath(path);
          //This is going to be done only once per record, so skipping cache save
          //because the path is going to be used only once (because only one record is used).
          return path;
        }

        @Override
        public void commitOldFiles(FileSystem fs) throws ELEvalException, IOException {
          //Nothing to do, we will simply overwrite the tmp files with new file.
          //As a performance feature, we could have marker files which tells
          //whether the file is completely copied and rename failed on previous
          //run (but there is a problem our op to delete the marker file may still fail)
        }

        @Override
        public void handleAlreadyExistingFile(FileSystem fs, Path tempPath) {
          //NOOP createWriter will overwrite existing tmp file
        }

        @Override
        public Path getRenamableFinalFilePath(Path tempPath) throws IOException, StageException {
          String finalFileName = tempPath.getName().replaceFirst(TMP_FILE_PREFIX, "");
          Path finalPath = new Path(tempPath.getParent(), finalFileName);
          //Checks during rename.
          checkAndHandleWholeFileExistence(finalPath);
          return finalPath;
        }

        @Override
        public OutputStream create(FileSystem fs, Path path) throws IOException {
          //Make sure if the tmp file already exists, overwrite it
          return fs.create(path, true);
        }
      };
    } else {
      //Other Data Formats
      return new FsHelper() {
        // we use/reuse Path as they are expensive to create (it increases the performance by at least 3%)
        private final Path tempFilePath = new Path(getTempFileName());
        private final LoadingCache<String, Path> dirPathCache =
            CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build(
                new CacheLoader<String, org.apache.hadoop.fs.Path>() {
                  @Override
                  public Path load(String key) throws Exception {
                    return new Path(key, tempFilePath);
                  }
                });

        @Override
        public Path getPath(Date recordDate, Record record) throws StageException, IOException {
          // runUuid is fixed for the current pipeline run. it avoids collisions with other SDCs running the same/similar
          // pipeline
          try {
            return dirPathCache.get(getDirPath(recordDate, record));
          } catch (ExecutionException ex) {
            if (ex.getCause() instanceof StageException) {
              throw (StageException) ex.getCause();
            } else{
              throw new StageException(Errors.HADOOPFS_24, ex.toString(), ex);
            }
          }
        }

        @Override
        public void commitOldFiles(FileSystem fs) throws StageException, IOException {
          if (context.getLastBatchTime() > 0) {
            for (String glob : getGlobs()) {
              LOG.debug("Looking for uncommitted files using glob '{}'", glob);
              FileStatus[] globStatus = fs.globStatus(new Path(glob));
              if (globStatus != null) {
                for (FileStatus status : globStatus) {
                  LOG.debug("Found uncommitted file '{}'", status.getPath());
                  renameToFinalName(fs, status.getPath());
                }
              }
            }
          }
        }

        @Override
        public void handleAlreadyExistingFile(FileSystem fs, Path tempPath) throws StageException, IOException {
          Path path = renameToFinalName(fs, tempPath);
          LOG.warn("Path[{}] - Found previous file '{}', committing it", tempPath, path);
        }

        @Override
        public Path getRenamableFinalFilePath(Path tempPath) {
          return new Path(tempPath.getParent(), uniquePrefix + "_" + UUID.randomUUID().toString() + getExtension());
        }

        @Override
        public OutputStream create(FileSystem fs, Path path) throws IOException {
          return fs.create(path, false);
        }
      };
    }
  }
}
