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
package org.apache.iotdb.commons.udf.builtin.String;

import org.apache.iotdb.commons.udf.api.UDTF;
import org.apache.iotdb.commons.udf.api.access.Row;
import org.apache.iotdb.commons.udf.api.collector.PointCollector;
import org.apache.iotdb.commons.udf.api.customizer.config.UDTFConfigurations;
import org.apache.iotdb.commons.udf.api.customizer.parameter.UDFParameterValidator;
import org.apache.iotdb.commons.udf.api.customizer.parameter.UDFParameters;
import org.apache.iotdb.commons.udf.api.customizer.strategy.RowByRowAccessStrategy;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;

/*This function returns  0 if targets are the same, -1 if targtet1 is smaller than targtet2,
and NULL if either argument is NULL. It returns 1 otherwise.*/
public class UDTFStrCompare implements UDTF {

  @Override
  public void validate(UDFParameterValidator validator) throws Exception {
    validator
        .validateInputSeriesNumber(2)
        .validateInputSeriesDataType(0, TSDataType.TEXT)
        .validateInputSeriesDataType(1, TSDataType.TEXT);
  }

  @Override
  public void beforeStart(UDFParameters parameters, UDTFConfigurations configurations)
      throws Exception {
    configurations
        .setAccessStrategy(new RowByRowAccessStrategy())
        .setOutputDataType(TSDataType.INT32);
  }

  @Override
  public void transform(Row row, PointCollector collector) throws Exception {
    if (row.isNull(0) || row.isNull(1)) {
      return;
    }
    collector.putInt(row.getTime(), row.getString(0).compareTo(row.getString(1)));
  }
}
