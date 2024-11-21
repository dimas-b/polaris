/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.test;

import static org.apache.iceberg.types.Types.NestedField.required;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.iceberg.BaseTransaction;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;

public class TestCLI {
  private static final String PREFIX = "w_";

  private static final Schema SCHEMA =
      new Schema(required(1, "id", Types.IntegerType.get(), "unique ID"));

  private static Namespace ns;
  private static int numTables;
  private static int numWorkers = 2;
  private static RESTCatalog catalog;

  public static void main(String[] args) {
    int idx = 0;
    String uri = args[idx++];
    numTables = Integer.parseInt(args[idx++]);
    ns = Namespace.of(args[idx++]);
    String cmd = args[idx++];

    catalog = new RESTCatalog();
    catalog.initialize(
        "test",
        Map.of(
            "uri", uri,
            "token", "principal:root;password:fake;realm:default-realm;role:ALL",
            "warehouse", "polaris2"));

    if (cmd.equals("create")) {
      create();
    } else if (cmd.equals("update")) {
      String prefix = args[idx++];
      update(prefix);
    }
  }

  private static void create() {
    catalog.createNamespace(ns);

    List<Namespace> namespaces = catalog.listNamespaces(Namespace.empty());
    System.out.println("namespaces: " + namespaces);

    for (int i = 0; i < numTables; i++) {
      TableIdentifier id = TableIdentifier.of(ns, "t" + i);
      Table t = catalog.createTable(id, SCHEMA);

      UpdateSchema upd = t.updateSchema();
      for (int j = 0; j < numWorkers; j++) {
        upd.addColumn("w_" + j + "_0", Types.IntegerType.get());
      }
      upd.commit();

      System.out.println("created: " + t);
      System.out.println("schema: " + t.schema());
    }
  }

  private static void update(String prefix) {

    Set<Integer> all = new TreeSet<>();
    for (int n = 1; n < 1_000_000; ) {
      Set<Integer> values = new HashSet<>();
      TableCommit[] commits = new TableCommit[numTables];
      for (int i = 0; i < numTables; i++) {
        TableIdentifier id = TableIdentifier.of(ns, "t" + i);
        Table t = catalog.loadTable(id);

        Schema schema = t.schema();
        Types.NestedField field =
            schema.columns().stream().filter(c -> c.name().startsWith(prefix)).findFirst().get();
        String name = field.name();
        int v = Integer.parseInt(name.substring(prefix.length()));
        values.add(v);

        BaseTransaction tx = (BaseTransaction) t.newTransaction();
        UpdateSchema u1 = tx.updateSchema().renameColumn(name, prefix + (v + 1));
        u1.commit();

        commits[i] = TableCommit.create(id, tx.startMetadata(), tx.currentMetadata());
      }

      if (values.size() != 1) {
        throw new IllegalStateException("update skew: " + values);
      }

      try {
        catalog.commitTransaction(commits);
      } catch (CommitFailedException e) {
        System.out.println("CF: " + e);
        continue;
      }

      int current = values.iterator().next();
      all.add(current);
      System.out.println("committed: " + current);

      if (all.size() != n) {
        throw new IllegalStateException("sequence skew: " + all);
      }

      n++;
    }
  }
}
