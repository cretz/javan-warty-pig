## Example: HTML Sanitizer

This takes the [HTML santizer project](https://github.com/OWASP/java-html-sanitizer) and runs it through the fuzzer.
This example demonstrates using persisted hash caches and input queues. It also demonstrates using a dictionary in AFL
format. To run the example, execute the following from the repo root:

    path/to/gradle --no-daemon :examples:htmlsan:run

This will run for 1 minute and store some cache files at `examples/build/tmp`. When executed a second time, it will pick
up from where it left off. The example is set to output any parameter that causes an exception to be thrown, but the
project is carefully written to not allow that to happen so it is unlikely any will occur.