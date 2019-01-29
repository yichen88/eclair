Micro-benchmarking the eclair router


To build the project run "mvn clean install"
To run the benchmark use "java -jar target/benchmark.jar -f 1 -i 1" this is the simplest benchmark run, use -h to discover new options


# Run complete. Total time: 00:07:23 (master: a91cd63)

Benchmark                                   Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath                     avgt       0.021           s/op
GraphBenchmark.findPathWithExtraChannels    avgt       0.033           s/op
GraphBenchmark.findPathWithIgnoredChannels  avgt       0.044           s/op
GraphBenchmark.routerLoadingTime            avgt       2.677           s/op


######

JGRAPH: (master: 521aa8a)
# Run complete. Total time: 00:07:19

Benchmark                                   Mode  Cnt  Score   Error  Units
GraphBenchmark.findPath                     avgt    2  0.014           s/op
GraphBenchmark.findPathWithExtraChannels    avgt    2  0.077           s/op
GraphBenchmark.findPathWithIgnoredChannels  avgt    2  0.114           s/op
