# jenkins-atom-sonar-scan

SonarQube scan — port of **sonar** (45,088) as an atom.

Run `sonar-scanner`, read `.scannerwork/report-task.txt`, and hold the build for the quality gate. The token
is passed through the environment, not argv.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
