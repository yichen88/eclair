Micro-benchmarking the eclair router


To build the project run "mvn clean install"
To run the benchmark use "java -jar target/benchmark.jar -f 1 -i 1" this is the simplest benchmark run, use -h to discover new options


Custom implementation results: (custom_graph with java.util.PriorityQueue)
# Run complete. Total time: 00:03:53

Benchmark                         Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath           avgt    2  0.304           s/op
GraphBenchmark.routerLoadingTime  avgt       2.359           s/op


Custom implementation results: (custom_graph with custom queue)
# Run complete. Total time: 00:04:02

Benchmark                         Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath           avgt    2  0.023           s/op
GraphBenchmark.routerLoadingTime  avgt       2.430           s/op

Custom implementation results: (custom_graph with jheaps)
# Run complete. Total time: 00:06:52

Benchmark                                   Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath                     avgt    2  0.075           s/op
GraphBenchmark.findPathWithExtraChannels    avgt    2  0.018           s/op
GraphBenchmark.findPathWithIgnoredChannels  avgt    2  0.368           s/op


######

JGRAPH: (master: 521aa8a)
# Run complete. Total time: 00:03:15

Benchmark                         Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath           avgt       0.011           s/op
GraphBenchmark.routerLoadingTime  avgt       2.567           s/op
