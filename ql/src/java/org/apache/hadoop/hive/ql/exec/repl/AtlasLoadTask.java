/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.hadoop.hive.ql.exec.repl;

import org.apache.atlas.model.impexp.AtlasImportRequest;
import org.apache.atlas.model.impexp.AtlasImportResult;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.repl.atlas.AtlasReplInfo;
import org.apache.hadoop.hive.ql.exec.repl.atlas.AtlasRequestBuilder;
import org.apache.hadoop.hive.ql.exec.repl.atlas.AtlasRestClientBuilder;
import org.apache.hadoop.hive.ql.exec.repl.util.ReplUtils;
import org.apache.hadoop.hive.ql.parse.EximUtil;
import org.apache.hadoop.hive.ql.parse.SemanticException;

import org.apache.hadoop.hive.ql.parse.repl.load.log.AtlasLoadLogger;
import org.apache.hadoop.hive.ql.plan.api.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Atlas Metadata Replication Load Task.
 **/
public class AtlasLoadTask extends Task<AtlasLoadWork> implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final transient Logger LOG = LoggerFactory.getLogger(AtlasLoadTask.class);

  @Override
  public int execute() {
    try {
      AtlasReplInfo atlasReplInfo  = createAtlasReplInfo();
      LOG.info("Loading atlas metadata from srcDb: {} to tgtDb: {} from staging: {}",
              atlasReplInfo.getSrcDB(), atlasReplInfo.getTgtDB(), atlasReplInfo.getStagingDir());
      AtlasLoadLogger replLogger = new AtlasLoadLogger(atlasReplInfo.getSrcDB(), atlasReplInfo.getTgtDB(),
              atlasReplInfo.getStagingDir().toString());
      replLogger.startLog();
      int importCount = importAtlasMetadata(atlasReplInfo);
      replLogger.endLog(importCount);
      LOG.info("Atlas entities import count {}", importCount);
      return 0;
    } catch (Exception e) {
      LOG.error("Exception while loading atlas metadata", e);
      setException(e);
      return ErrorMsg.getErrorMsg(e.getMessage()).getErrorCode();
    }
  }

  public AtlasReplInfo createAtlasReplInfo() throws SemanticException, MalformedURLException {
    String errorFormat = "%s is mandatory config for Atlas metadata replication";
    //Also validates URL for endpoint.
    String endpoint = new URL(ReplUtils.getNonEmpty(HiveConf.ConfVars.REPL_ATLAS_ENDPOINT.varname, conf, errorFormat))
            .toString();
    String srcCluster = ReplUtils.getNonEmpty(HiveConf.ConfVars.REPL_SOURCE_CLUSTER_NAME.varname, conf, errorFormat);
    String tgtCluster = ReplUtils.getNonEmpty(HiveConf.ConfVars.REPL_TARGET_CLUSTER_NAME.varname, conf, errorFormat);
    AtlasReplInfo atlasReplInfo = new AtlasReplInfo(endpoint, work.getSrcDB(), work.getTgtDB(),
            srcCluster, tgtCluster, work.getStagingDir(), conf);
    atlasReplInfo.setSrcFsUri(getStoredFsUri(atlasReplInfo.getStagingDir()));
    atlasReplInfo.setTgtFsUri(conf.get(ReplUtils.DEFAULT_FS_CONFIG));
    return atlasReplInfo;
  }

  private String getStoredFsUri(Path atlasDumpDir) throws SemanticException {
    Path metadataPath = new Path(atlasDumpDir, EximUtil.METADATA_NAME);
    BufferedReader br = null;
    try {
      FileSystem fs = metadataPath.getFileSystem(conf);
      br = new BufferedReader(new InputStreamReader(fs.open(metadataPath), Charset.defaultCharset()));
      String line = br.readLine();
      if (line == null) {
        throw new SemanticException("Could not read stored src FS Uri from atlas metadata file");
      }
      String[] lineContents = line.split("\t", 5);
      return lineContents[0];
    } catch (Exception ex) {
      throw new SemanticException(ex);
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          throw new SemanticException(e);
        }
      }
    }
  }

  public int importAtlasMetadata(AtlasReplInfo atlasReplInfo) throws Exception {
    AtlasRequestBuilder atlasRequestBuilder = new AtlasRequestBuilder();
    AtlasImportRequest importRequest = atlasRequestBuilder.createImportRequest(
            atlasReplInfo.getSrcDB(), atlasReplInfo.getTgtDB(),
            atlasReplInfo.getSrcCluster(), atlasReplInfo.getTgtCluster(),
            atlasReplInfo.getSrcFsUri(), atlasReplInfo.getTgtFsUri());
    AtlasImportResult result = new AtlasRestClientBuilder(atlasReplInfo.getAtlasEndpoint())
            .getClient(atlasReplInfo.getConf()).importData(importRequest, atlasReplInfo);
    if (result == null || result.getProcessedEntities() == null) {
      LOG.info("No Atlas entity found");
      return 0;
    }
    return result.getProcessedEntities().size();
  }

  @Override
  public StageType getType() {
    return StageType.ATLAS_LOAD;
  }

  @Override
  public String getName() {
    return "ATLAS_LOAD";
  }

  @Override
  public boolean canExecuteInParallel() {
    return false;
  }
}
