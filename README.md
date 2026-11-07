# jenkins-atom-sonar

SonarQube scan — port of **sonar** (45,088) as an atom.

Run `sonarner`, read `.scannerwork/report-task.txt`, and hold the build for the quality gate. The token
is passed through the environment, not argv.

## Build and test

```bash
mvn verify          # -> dist/sonar/, plus SonarScanIT in src/test/java/atom
```
