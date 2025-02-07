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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.itbase.runtime;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ParallelRequestDelegate will handle requests in parallel. It's more efficient when requests
 * contain network communication.
 */
public class ParallelRequestDelegate<T> extends RequestDelegate<T> {
  private final int taskTimeoutSeconds;

  public ParallelRequestDelegate(List<String> endpoints, int taskTimeoutSeconds) {
    super(endpoints);
    this.taskTimeoutSeconds = taskTimeoutSeconds;
  }

  public List<T> requestAll() throws SQLException {
    List<Future<T>> resultFutures = new ArrayList<>(getRequests().size());
    for (Callable<T> request : getRequests()) {
      Future<T> f = RequestThreadPool.submit(request);
      resultFutures.add(f);
    }
    List<T> results = new ArrayList<>(getRequests().size());
    for (int i = 0; i < getEndpoints().size(); i++) {
      try {
        results.add(resultFutures.get(i).get(taskTimeoutSeconds, TimeUnit.SECONDS));
      } catch (Exception e) {
        for (int j = i + 1; j < getEndpoints().size(); j++) {
          resultFutures.get(j).cancel(true);
        }
        throw new SQLException(
            String.format("Waiting for query results of %s failed", getEndpoints().get(i)), e);
      }
    }
    return results;
  }
}
