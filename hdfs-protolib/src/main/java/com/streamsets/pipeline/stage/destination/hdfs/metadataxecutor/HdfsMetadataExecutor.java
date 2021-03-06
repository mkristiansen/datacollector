/**
 * Copyright 2016 StreamSets Inc.
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
package com.streamsets.pipeline.stage.destination.hdfs.metadataxecutor;

import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class HdfsMetadataExecutor extends BaseTarget {

  private static final Logger LOG = LoggerFactory.getLogger(HdfsMetadataExecutor.class);

  private final HdfsConnectionConfig hdfsConnection;
  private final HdfsActionsConfig actions;
  private ErrorRecordHandler errorRecordHandler;
  private Map<String, ELEval> evals;

  public HdfsMetadataExecutor(HdfsConnectionConfig hdfsConnection, HdfsActionsConfig actions) {
    this.hdfsConnection = hdfsConnection;
    this.actions = actions;
  }

  @Override
  public List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    hdfsConnection.init(getContext(), "connection", issues);
    actions.init(getContext(), "actions", issues);
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());
    this.evals = new HashMap<>();
    return issues;
  }

  private String evaluate(ELVars variables, String name, String expression) throws ELEvalException {
    ELEval eval = evals.get(name);
    if(eval == null) {
      eval = getContext().createELEval(name);
      evals.put(name, eval);
    }

    return eval.eval(variables, expression, String.class);
  }

  @Override
  public void write(Batch batch) throws StageException {
    final ELVars variables = getContext().createELVars();
    final FileSystem fs = hdfsConnection.getFs();

    Iterator<Record> it = batch.getRecords();
    while(it.hasNext()) {
      Record record = it.next();
      RecordEL.setRecordInContext(variables, record);

      // Execute all configured HDFS metadata operations as target user
      try {
        hdfsConnection.getUGI().doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            Path workingFile = new Path(evaluate(variables, "filePath", actions.filePath));
            LOG.info("Working on file: " + workingFile);

            if(actions.shouldMoveFile) {
              Path destinationFile = new Path(evaluate(variables, "newLocation", actions.newLocation));

              Path destinationParent = destinationFile.getParent();
              if(!fs.exists(destinationParent)) {
                LOG.debug("Creating parent directory for destination file: {}", destinationParent);
                if(!fs.mkdirs(destinationParent)) {
                  throw new IOException("Can't create directory: " + destinationParent);
                }
              }

              LOG.debug("Renaming to: {}", destinationFile);
              if(!fs.rename(workingFile, destinationFile)) {
                throw new IOException("Can't rename file to: " + destinationFile);
              }
              workingFile = destinationFile;
            }

            if(actions.shouldChangeOwnership) {
              String newOwner = evaluate(variables, "newOwner", actions.newOwner);
              String newGroup = evaluate(variables, "newGroup", actions.newGroup);
              LOG.debug("Applying ownership: user={} and group={}", newOwner, newGroup);
              fs.setOwner(workingFile, newOwner, newGroup);
            }

            if(actions.shouldSetPermissions) {
              String stringPerms = evaluate(variables, "newPermissions", actions.newPermissions);
              FsPermission fsPerms = new FsPermission(stringPerms);
              LOG.debug("Applying permissions: {} loaded from value '{}'", fsPerms, stringPerms);
              fs.setPermission(workingFile, fsPerms);
            }

            LOG.debug("Done changing metadata on file: {}", workingFile);
            return null;
          }
        });
      } catch (Exception e) {
        LOG.error("Failure when applying metadata changes to HDFS", e);
        errorRecordHandler.onError(new OnRecordErrorException(record, HdfsMetadataErrors.HDFS_METADATA_000, e.getMessage()));
      }
    }
  }

  @Override
  public void destroy() {
    hdfsConnection.destroy();
    actions.destroy();
  }

}
