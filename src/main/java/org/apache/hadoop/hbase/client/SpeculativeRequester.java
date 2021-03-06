/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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
package org.apache.hadoop.hbase.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SpeculativeRequester<T extends Object> {

  long waitTimeBeforeRequestingFailover;
  long waitTimeBeforeAcceptingResults;
  AtomicLong lastPrimaryFail;
  long waitTimeFromLastPrimaryFail;
  
  static final Log LOG = LogFactory.getLog(SpeculativeRequester.class);

  static ExecutorService exe = Executors.newFixedThreadPool(200);
  
  public SpeculativeRequester(long waitTimeBeforeRequestingFailover,
      long waitTimeBeforeAcceptingResults,
      AtomicLong lastPrimaryFail,
      long waitTimeFromLastPrimaryFail
    ) {
    this.waitTimeBeforeRequestingFailover = waitTimeBeforeRequestingFailover;
    this.waitTimeBeforeAcceptingResults = waitTimeBeforeAcceptingResults;
    this.lastPrimaryFail = lastPrimaryFail;
    this.waitTimeFromLastPrimaryFail = waitTimeFromLastPrimaryFail;
  }

  public ResultWrapper<T> request(final HBaseTableFunction<T> function, 
      final HTableInterface primaryTable,
      final Collection<HTableInterface> failoverTables) {
    
    ExecutorCompletionService<ResultWrapper<T>> exeS = new ExecutorCompletionService<ResultWrapper<T>>(exe);
    
    final AtomicBoolean isPrimarySuccess = new AtomicBoolean(false);
    final long startTime = System.currentTimeMillis();

    ArrayList<Callable<ResultWrapper<T>>> callables = new ArrayList<Callable<ResultWrapper<T>>>();

    if (System.currentTimeMillis() - lastPrimaryFail.get() > waitTimeFromLastPrimaryFail) {
      callables.add(new Callable<ResultWrapper<T>>() {
        public ResultWrapper<T> call() throws Exception {
          try {
            T t = function.call(primaryTable);
            isPrimarySuccess.set(true);
            return new ResultWrapper(true, t);
          } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            lastPrimaryFail.set(System.currentTimeMillis());
            Thread.currentThread().interrupt();
          }
          return null;
        }
      });
    }

    for (final HTableInterface failoverTable : failoverTables) {
      callables.add(new Callable<ResultWrapper<T>>() {

        public ResultWrapper<T> call() throws Exception {
          
          long waitToRequest = (System.currentTimeMillis() - lastPrimaryFail.get() > waitTimeFromLastPrimaryFail)?
              waitTimeBeforeRequestingFailover - (System.currentTimeMillis() - startTime): 0;
              
          
          if (waitToRequest > 0) {
            Thread.sleep(waitToRequest);
          }
          if (isPrimarySuccess.get() == false) {
            T t = function.call(failoverTable);

            long waitToAccept = (System.currentTimeMillis() - lastPrimaryFail.get() > waitTimeFromLastPrimaryFail)?
                waitTimeBeforeAcceptingResults - (System.currentTimeMillis() - startTime): 0;
            if (isPrimarySuccess.get() == false) {
              if (waitToAccept > 0) {
                Thread.sleep(waitToAccept);
              }
            }

            return new ResultWrapper(false, t);
          } else {
            throw new RuntimeException("Not needed");
          }

        }
      });
    }
    try {

      //ResultWrapper<T> t = exe.invokeAny(callables);
      for (Callable<ResultWrapper<T>> call: callables) {
        exeS.submit(call);
      }
      
      ResultWrapper<T> result = exeS.take().get();
      //exe.shutdownNow();
      
      return result; 
    } catch (InterruptedException e) {
      e.printStackTrace();
      LOG.error(e);
    } catch (ExecutionException e) {
      e.printStackTrace();
      LOG.error(e);
    }
    return null;
    
  }
  
  public static class ResultWrapper<T> {
    public Boolean isPrimary;
    public T t;
    
    public ResultWrapper(Boolean isPrimary, T t) {
      this.isPrimary = isPrimary;
      this.t = t;
    }
  }
  
 
}
