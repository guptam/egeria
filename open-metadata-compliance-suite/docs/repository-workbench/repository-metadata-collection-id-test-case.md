<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- Copyright Contributors to the Egeria project. -->


# Repository metadata collection id test case

Validate the retrieval of the metadata collection id from the open metadata repository.

## Operation

This test uses the `../open-metadata/repository-services/metadata-collection-id`
operation to test that the repository knows its metadata collection id.

## Assertions

* **repository-metadata-collection-id-01** Metadata collection id retrieved from repository.

   The metadata collection id has successfully been retrieved from the server.
If this assertion fails, check that the server is started and the
open metadata services are activated.

* **repository-metadata-collection-id-02** Consistent metadata collection id retrieved from repository.

    The same metadata collection id was retrieved when the server was called
for a second time.


## Example output

```json
{
      "class" : "OpenMetadataTestCaseResult",
      "testCaseId" : "repository-metadata-collection",
      "testCaseName" : "Repository metadata collection id test case",
      "testCaseDescriptionURL" : "https://odpi.github.io/egeria/open-metadata-compliance-suite/docs/repository-workbench/repository-metadata-collection-test-case.md",
      "successMessage" : "Metadata collection id working consistently",
      "successfulAssertions" : [ "Metadata collection id retrieved from repository.", "Consistent metadata collection id retrieved from repository." ],
      "unsuccessfulAssertions" : [ ],
      "discoveredProperties" : {
        "metadata collection id" : "8b2aae04-c68c-47f8-a040-3ed12b82a50e"
      }
}
```