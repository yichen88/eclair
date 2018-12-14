Micro-benchmarking the eclair router


To build the project run "mvn clean install"
To run the benchmark use "java -jar target/benchmark.jar -f 1 -i 1" this is the simplest benchmark run, use -h to discover new options


Custom implementation results: (custom_graph: 1e8be66)
# Run complete. Total time: 00:03:18

Benchmark                         Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath           avgt       0.539           s/op
GraphBenchmark.routerLoadingTime  avgt       2.717           s/op

JGRAPH: (master: 521aa8a)
# Run complete. Total time: 00:03:15

Benchmark                         Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath           avgt       0.011           s/op
GraphBenchmark.routerLoadingTime  avgt       2.567           s/op
