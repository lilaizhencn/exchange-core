# Production simulation benchmarks

The standalone JMH suite measures the overhead added by the production
simulation layer:

- `SymbolPartitionOrderingBenchmark` measures partition-lane publication.
- `ProductionSimulationBenchmark` measures an end-to-end protected IOC.
- `ProductionAccountingBenchmark` measures full equity risk, a user report,
  and an in-memory Emporia portfolio publication.
- `ProductionCheckpointBenchmark` measures a committed native-shard and DMA
  lifecycle checkpoint at two lifecycle sizes.

Build with Java 21:

```shell
mvn -DskipTests install
mvn -f benchmarks/pom.xml clean package
```

Run all benchmarks:

```shell
java -jar benchmarks/target/benchmarks.jar
```

Run a short smoke measurement:

```shell
java -jar benchmarks/target/benchmarks.jar \
  SymbolPartitionOrderingBenchmark \
  -p partitions=4 -wi 1 -i 1 -w 100ms -r 100ms -f 1
```

Record the JVM, CPU topology, power policy and filesystem with published
results. The checkpoint benchmark uses temporary local storage and deletes it
after each invocation.
